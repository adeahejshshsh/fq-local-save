package com.fq.localsave;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DEX 字符串表扫描 —— 版本自适应兜底。
 *
 * 番茄每次大版本升级都可能把类挪包 / 改名（reader.* → kmp.reader.*，混淆名换字母）。
 * 硬编码类名一旦对不上，模块就整条链路失效，而用户只会看到「没反应」。
 *
 * 所以除了硬编码候选表，这里再直接读宿主 APK 里 classes*.dex 的 string_ids：
 * 只要字符串表里出现长得像目标类的描述符，就把它当候选试一遍。
 * 纯 Java 实现，不引入 DexKit（那需要额外塞 .so，还会因为 LSPosed 注入进程里
 * 没人 System.loadLibrary 而炸 UnsatisfiedLinkError）。
 */
public final class Dex {

    private static final Set<String> CACHE = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
    private static volatile boolean scanned = false;

    private Dex() {
    }

    /** 已知的所有类描述符（com.dragon.read.* 下），只在第一次调用时扫一遍 */
    public static Set<String> classes() {
        if (scanned) return CACHE;
        synchronized (Dex.class) {
            if (scanned) return CACHE;
            try {
                scan();
            } catch (Throwable t) {
                Logs.e("DEX 扫描失败（不影响硬编码候选）", t);
            }
            scanned = true;
        }
        return CACHE;
    }

    /** 按「简单类名」找候选：simpleName 例如 CryptManager / DecryptKey / ChapterInfo */
    public static List<String> bySimpleName(String simpleName) {
        List<String> out = new ArrayList<String>();
        for (String desc : classes()) {
            String dot = descToDot(desc);
            if (dot == null) continue;
            int i = dot.lastIndexOf('.');
            String sn = i < 0 ? dot : dot.substring(i + 1);
            if (sn.equals(simpleName)) out.add(dot);
        }
        // 名字里带 reader 的排前面：同名类可能有多个，阅读链路那个才是我们要的
        Collections.sort(out, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int ra = a.contains(".reader") ? 0 : 1;
                int rb = b.contains(".reader") ? 0 : 1;
                if (ra != rb) return ra - rb;
                return a.compareTo(b);
            }
        });
        return out;
    }

    /** 按后缀找候选（例如 "ChapterInfo"，能覆盖 XxxChapterInfo 这类） */
    public static List<String> bySimpleSuffix(String suffix) {
        List<String> out = new ArrayList<String>();
        for (String desc : classes()) {
            String dot = descToDot(desc);
            if (dot == null) continue;
            int i = dot.lastIndexOf('.');
            String sn = i < 0 ? dot : dot.substring(i + 1);
            if (sn.endsWith(suffix)) out.add(dot);
        }
        Collections.sort(out);
        return out;
    }

    private static String descToDot(String desc) {
        if (desc == null || desc.length() < 3) return null;
        if (desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') return null;
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }

    private static void scan() {
        Context ctx = Store.app();
        if (ctx == null) {
            Logs.w("DEX 扫描跳过：上下文还没就绪");
            return;
        }
        String apk = null;
        try {
            apk = ctx.getApplicationInfo().sourceDir;
        } catch (Throwable t) {
            Logs.e("取 sourceDir 失败", t);
        }
        if (apk == null) {
            Logs.w("DEX 扫描跳过：拿不到宿主 APK 路径");
            return;
        }
        long t0 = System.currentTimeMillis();
        int dexCount = 0, strCount = 0;
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.endsWith(".dex")) continue;
                if (name.contains("/")) continue;          // 只要根目录的 classes*.dex
                dexCount++;
                InputStream in = null;
                try {
                    in = zip.getInputStream(e);
                    byte[] buf = Files.drain(in);
                    strCount += scanOneDex(buf);
                } catch (Throwable t) {
                    Logs.e("扫 " + name + " 失败", t);
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) {
            Logs.e("打开宿主 APK 失败: " + apk, t);
        } finally {
            if (zip != null) try { zip.close(); } catch (Throwable ignore) {}
        }
        Logs.i("DEX 扫描完成: " + dexCount + " 个 dex / 命中 " + CACHE.size() + " 个类 / "
                + strCount + " 条字符串 / " + (System.currentTimeMillis() - t0) + "ms");
    }

    private static int scanOneDex(byte[] d) {
        if (d == null || d.length < 112) return 0;
        // magic: "dex\n035\0"
        if (d[0] != 'd' || d[1] != 'e' || d[2] != 'x' || d[3] != '\n') return 0;
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = bb.getInt(56);
        int stringIdsOff = bb.getInt(60);
        if (stringIdsSize <= 0 || stringIdsOff <= 0) return 0;
        if (stringIdsOff + stringIdsSize * 4 > d.length) return 0;
        int hit = 0;
        for (int i = 0; i < stringIdsSize; i++) {
            int off = bb.getInt(stringIdsOff + i * 4);
            if (off <= 0 || off >= d.length) continue;
            int p = off;
            // uleb128 utf16_size
            int shift = 0;
            while (p < d.length) {
                int b = d[p++] & 0xFF;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) break;
            }
            if (p >= d.length) continue;
            // MUTF-8 以 0 结尾
            int q = p;
            while (q < d.length && d[q] != 0) q++;
            if (q <= p) continue;
            int len = q - p;
            if (len < 8 || len > 220) continue;
            char c0 = (char) (d[p] & 0xFF);
            if (c0 != 'L') continue;
            if ((d[q - 1] & 0xFF) != ';') continue;
            String s = new String(d, p, len, Files.UTF8);
            if (!s.startsWith("Lcom/dragon/read/")) continue;
            CACHE.add(s);
            hit++;
        }
        return hit;
    }

    /** 目标方法找不到时，把该类上「形状相同」的方法名写进日志，方便下次直接改候选表 */
    public static String describeMethods(Class<?> cls, int argCount) {
        if (cls == null) return "";
        try {
            java.lang.reflect.Method[] ms = cls.getDeclaredMethods();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (java.lang.reflect.Method m : ms) {
                if (m.isSynthetic()) continue;
                if (argCount >= 0 && m.getParameterTypes().length != argCount) continue;
                if (n++ > 0) sb.append(", ");
                sb.append(m.getName()).append('/').append(m.getParameterTypes().length);
                if (n >= 24) {
                    sb.append(" …");
                    break;
                }
            }
            return sb.length() == 0 ? "(该类没有 " + argCount + " 参方法)" : sb.toString();
        } catch (Throwable t) {
            return "(列方法失败: " + t + ")";
        }
    }

    /** 把 class 上所有 public static 方法签名写出来（调试 decrypt 用） */
    public static String dumpStatics(Class<?> cls) {
        if (cls == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(")->").append(m.getReturnType().getSimpleName()).append("  ");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(dump 失败: " + t + ")";
        }
    }

    /** 宿主 APK 里所有 classes*.dex 的大小（排查 dex 加密/加固用） */
    public static String dexSummary() {
        Context ctx = Store.app();
        if (ctx == null) return "无上下文";
        StringBuilder sb = new StringBuilder();
        try {
            File apk = new File(ctx.getApplicationInfo().sourceDir);
            ZipFile zip = new ZipFile(apk);
            try {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.getName().endsWith(".dex")) {
                        sb.append(e.getName()).append('=').append(e.getSize()).append("  ");
                    }
                }
            } finally {
                zip.close();
            }
        } catch (Throwable t) {
            return "读 APK 失败: " + t;
        }
        return sb.toString();
    }
}
