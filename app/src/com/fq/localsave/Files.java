package com.fq.localsave;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

/**
 * 落盘层。
 *
 * 为什么不直接 new FileOutputStream("/sdcard/Download/...")：
 * targetSdk >= 29 时宿主受分区存储约束，直接写公共目录会被 EACCES 拒绝。
 * 唯一不依赖任何额外权限的正规路径是走 MediaStore（Downloads 集合 + RELATIVE_PATH）。
 * 这里按「Downloads+pending → Downloads → Files → 直接 File」四级降级，
 * 保证在任何 ROM / 任何 targetSdk 上都能落地。
 *
 * 注意「覆盖写」而不是「删除后新建」：实测部分 ROM 的 delete 匹配不到
 * RELATIVE_PATH（有的存 "a/b/"，有的存 "a/b"），于是 insert 会新建，
 * MediaStore 自动把文件名改成 "xxx (1).txt"，日志文件会疯狂增殖。
 * 所以先按 RELATIVE_PATH+DISPLAY_NAME 查既有条目，查到就 openOutputStream("wt") 覆盖。
 */
public final class Files {

    public static final Charset UTF8 = Charset.forName("UTF-8");

    /** 输出根目录（MediaStore 的 RELATIVE_PATH，相对公共 Download） */
    public static final String ROOT = "Download/番茄本地";
    public static final String LOG_NAME = "_运行日志.txt";

    private Files() {
    }

    /** 取宿主上下文；handleLoadPackage 阶段 Application 常常还没建好，必须懒获取 */
    public static Context app() {
        return Store.app();
    }

    // ══════════════════════════════════════════════════════════
    //  写入
    // ══════════════════════════════════════════════════════════

    public static boolean write(String rel, String name, byte[] data, String mime) {
        if (app() == null || data == null) return false;
        String r = rel.replaceAll("/+$", "");
        if (Build.VERSION.SDK_INT >= 29) {
            if (insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, r, name, data, mime, true, "Downloads+pending")) {
                return true;
            }
            if (insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, r, name, data, mime, false, "Downloads")) {
                return true;
            }
            if (insert(MediaStore.Files.getContentUri("external"), r, name, data, mime, false, "Files")) {
                return true;
            }
        }
        // 兜底：直接写文件（API<29，或宿主已持有 MANAGE_EXTERNAL_STORAGE）
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), r);
            if (!dir.exists() && !dir.mkdirs()) {
                Logs.e("直接写盘 建目录失败: " + dir);
                return false;
            }
            OutputStream os = new FileOutputStream(new File(dir, name));
            try {
                os.write(data);
                os.flush();
            } finally {
                os.close();
            }
            return true;
        } catch (Throwable t) {
            Logs.e("直接写盘失败 " + r + "/" + name, t);
            return false;
        }
    }

    private static Uri findExisting(ContentResolver cr, Uri coll, String rel, String name) {
        Cursor c = null;
        try {
            c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{rel + "/", name}, null);
            if (c != null && c.moveToFirst()) {
                return Uri.withAppendedPath(coll, String.valueOf(c.getLong(0)));
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static boolean insert(Uri coll, String rel, String name, byte[] data,
                                  String mime, boolean pending, String tag) {
        ContentResolver cr = app().getContentResolver();
        try {
            Uri exist = findExisting(cr, coll, rel, name);
            if (exist != null) {
                OutputStream os = cr.openOutputStream(exist, "wt");
                if (os != null) {
                    try {
                        os.write(data);
                        os.flush();
                    } finally {
                        os.close();
                    }
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            if (pending) cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(coll, cv);
            if (uri == null) return false;
            OutputStream os = cr.openOutputStream(uri, "w");
            if (os == null) {
                cr.delete(uri, null, null);
                return false;
            }
            try {
                os.write(data);
                os.flush();
            } finally {
                os.close();
            }
            if (pending) {
                ContentValues cv2 = new ContentValues();
                cv2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, cv2, null, null);
            }
            return true;
        } catch (Throwable t) {
            Logs.e("写盘[" + tag + "] " + rel + "/" + name, t);
            return false;
        }
    }

    public static byte[] read(String rel, String name) {
        if (app() == null) return null;
        String r = rel.replaceAll("/+$", "");
        if (Build.VERSION.SDK_INT >= 29) {
            for (Uri coll : new Uri[]{MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.Files.getContentUri("external")}) {
                Cursor c = null;
                try {
                    ContentResolver cr = app().getContentResolver();
                    c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                            MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                                    + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            new String[]{r + "/", name}, null);
                    if (c != null && c.moveToFirst()) {
                        Uri u = Uri.withAppendedPath(coll, String.valueOf(c.getLong(0)));
                        InputStream is = cr.openInputStream(u);
                        if (is != null) {
                            try {
                                return drain(is);
                            } finally {
                                is.close();
                            }
                        }
                    }
                } catch (Throwable ignore) {
                } finally {
                    if (c != null) c.close();
                }
            }
        }
        try {
            File f = new File(new File(Environment.getExternalStorageDirectory(), r), name);
            if (f.isFile()) return readFile(f);
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 追加写：读旧 + 拼新 + 整体覆盖（自产文件可读可写） */
    public static boolean append(String rel, String name, byte[] add, String mime) {
        byte[] old = read(rel, name);
        byte[] all;
        if (old != null && old.length > 0) {
            if (old.length > 4 * 1024 * 1024) {
                all = add;                       // 过大就截断重来
            } else {
                all = new byte[old.length + add.length];
                System.arraycopy(old, 0, all, 0, old.length);
                System.arraycopy(add, 0, all, old.length, add.length);
            }
        } else {
            all = add;
        }
        return write(rel, name, all, mime);
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════

    public static byte[] drain(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int k;
        while ((k = is.read(buf)) > 0) bo.write(buf, 0, k);
        return bo.toByteArray();
    }

    public static byte[] readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                return drain(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isGzip(byte[] b) {
        return b != null && b.length > 3 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    public static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    public static byte[] gunzip(byte[] in) {
        try {
            GZIPInputStream g = new GZIPInputStream(new java.io.ByteArrayInputStream(in));
            try {
                return drain(g);
            } finally {
                g.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static String md5hex(String s) {
        try {
            return md5hex(s.getBytes(UTF8));
        } catch (Throwable t) {
            return null;
        }
    }

    public static String md5hex(byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(raw);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 文件名安全化：去掉路径分隔与非法字符，限制长度 */
    public static String safe(String name, int max) {
        if (name == null || name.isEmpty()) return "_未命名";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c == '\n' || c == '\r' || c == '\t'
                    || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) s = "_" + s.substring(1);
        if (s.isEmpty()) s = "_未命名";
        if (s.length() > max) s = s.substring(0, max);
        return s;
    }

    public static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
