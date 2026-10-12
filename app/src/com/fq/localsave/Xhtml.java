package com.fq.localsave;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 番茄章节正文解析。
 *
 * 解密后的 XHTML 长这样（节选）：
 *   <blk>正文段落</blk>
 *   <div class="image"><img src="...novel-pic-r/<key>"><blk>图注</blk></div>
 * 所以正文抽取 = 顺序扫 <blk> 与 image div，别的标签全部跳过。
 */
public final class Xhtml {

    private static final Pattern P_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern P_NUM = Pattern.compile("第\\s*([0-9]+)\\s*[章节回卷]");
    private static final Pattern P_NUM_CN =
            Pattern.compile("第\\s*([〇零一二三四五六七八九两十百千万]+)\\s*[章节回卷]");
    private static final String CN_DIGITS = "一二三四五六七八九";

    private Xhtml() {
    }

    /** 正文块 */
    public static final class Blk {
        public static final int TEXT = 0, IMG = 1;
        public int kind;
        public String text;     // TEXT 正文 / IMG 图注
        public String imgKey;   // IMG 本地图片键（src 的 Java hashCode）
        public byte[] img;      // IMG 图片字节
    }

    // ══════════════════════════════════════════════════════════
    //  正文抽取
    // ══════════════════════════════════════════════════════════

    /**
     * @param xml    解密后的 XHTML
     * @param bookId 书名 ID（找插图用；可为 null，表示不取图）
     * @return 正文块列表
     */
    public static List<Blk> extractBlocks(String xml, String bookId) {
        List<Blk> out = new ArrayList<Blk>();
        if (xml == null || xml.isEmpty()) return out;
        int i = 0;
        int n = xml.length();
        while (i < n) {
            int nb = xml.indexOf("<blk", i);
            int nd = xml.indexOf("<div", i);
            if (nb < 0 && nd < 0) break;
            if (nd >= 0 && (nb < 0 || nd < nb)) {
                int te = xml.indexOf('>', nd);
                if (te < 0) break;
                String tag = xml.substring(nd, te + 1);
                if (tag.indexOf("image") >= 0) {
                    int close = xml.indexOf("</div>", te);
                    int end = close < 0 ? n : close;
                    Blk b = parseImageDiv(xml.substring(te + 1, end), bookId);
                    if (b != null) out.add(b);
                    i = close < 0 ? n : close + 6;
                } else {
                    i = te + 1;    // 普通 div：只跳过标签，内部的 <blk> 照收
                }
                continue;
            }
            int gt = xml.indexOf('>', nb);
            if (gt < 0) break;
            int e = xml.indexOf("</blk>", gt);
            if (e < 0) break;
            String t = cleanText(xml.substring(gt + 1, e));
            if (!t.isEmpty()) {
                Blk b = new Blk();
                b.kind = Blk.TEXT;
                b.text = t;
                out.add(b);
            }
            i = e + 6;
        }
        if (out.isEmpty()) {
            // 兜底：整篇当一段
            String t = cleanText(xml);
            if (!t.isEmpty()) {
                Blk b = new Blk();
                b.kind = Blk.TEXT;
                b.text = t;
                out.add(b);
            }
        }
        return out;
    }

    private static Blk parseImageDiv(String seg, String bookId) {
        String src = null;
        String cap = "";
        int is = seg.indexOf("<img");
        if (is >= 0) {
            int ite = seg.indexOf('>', is);
            String itag = ite < 0 ? seg.substring(is) : seg.substring(is, ite + 1);
            src = attr(itag, "src");
        }
        int bs = seg.indexOf("<blk");
        if (bs >= 0) {
            int gt = seg.indexOf('>', bs);
            if (gt >= 0) {
                int be = seg.indexOf("</blk>", gt);
                if (be > gt) cap = cleanText(seg.substring(gt + 1, be));
            }
        }
        String key = imgKeyOf(src);
        Blk b = new Blk();
        b.kind = Blk.IMG;
        b.text = cap;
        b.imgKey = key;
        b.img = key == null ? null : findImage(key, bookId);
        if (key == null && b.img == null && cap.isEmpty()) return null;
        return b;
    }

    // ══════════════════════════════════════════════════════════
    //  插图：宿主私有目录里是明文
    //
    //  番茄把章节插图落在 <filesDir>/<账号子目录>/prefix_public_<bookId>/image/<key>
    //  模块跑在宿主进程内 → 直接 File 读，不需要 root，也不需要任何权限。
    // ══════════════════════════════════════════════════════════

    public static byte[] findImage(String key, String bookId) {
        if (key == null || bookId == null) return null;
        try {
            android.content.Context ctx = Store.app();
            if (ctx == null) return null;
            java.io.File files = ctx.getFilesDir();
            java.io.File[] subs = files == null ? null : files.listFiles();
            if (subs == null) return null;
            for (java.io.File s : subs) {
                if (!s.isDirectory()) continue;
                java.io.File img = new java.io.File(
                        new java.io.File(s, "prefix_public_" + bookId), "image/" + key);
                if (img.isFile()) {
                    byte[] b = Files.readFile(img);
                    if (b != null && b.length > 0) return b;
                }
            }
        } catch (Throwable t) {
            Logs.e("findImage", t);
        }
        return null;
    }

    /** src → 本地文件名键：取 "novel-pic-r/" 之后（或最后一段）再做 Java String.hashCode */
    public static String imgKeyOf(String src) {
        if (src == null) return null;
        String s = src;
        int k = s.indexOf("novel-pic-r/");
        if (k >= 0) {
            s = s.substring(k + "novel-pic-r/".length());
        } else {
            int slash = s.lastIndexOf('/');
            s = slash >= 0 ? s.substring(slash + 1) : s;
        }
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.isEmpty()) return null;
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return String.valueOf(h);
    }

    // ══════════════════════════════════════════════════════════
    //  文本工具
    // ══════════════════════════════════════════════════════════

    public static String cleanText(String s) {
        if (s == null) return "";
        String t = s.indexOf('<') < 0 ? s : P_TAG.matcher(s).replaceAll("");
        return unescape(t).trim();
    }

    public static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                b.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                b.append(c);
                i++;
                continue;
            }
            String ent = s.substring(i + 1, semi);
            String rep = null;
            if (ent.startsWith("#x") || ent.startsWith("#X")) {
                try {
                    rep = String.valueOf((char) Integer.parseInt(ent.substring(2), 16));
                } catch (Throwable ignore) {
                }
            } else if (ent.startsWith("#")) {
                try {
                    rep = String.valueOf((char) Integer.parseInt(ent.substring(1)));
                } catch (Throwable ignore) {
                }
            } else if ("amp".equals(ent)) rep = "&";
            else if ("lt".equals(ent)) rep = "<";
            else if ("gt".equals(ent)) rep = ">";
            else if ("quot".equals(ent)) rep = "\"";
            else if ("apos".equals(ent)) rep = "'";
            else if ("nbsp".equals(ent)) rep = " ";
            if (rep == null) {
                b.append(c);
                i++;
            } else {
                b.append(rep);
                i = semi + 1;
            }
        }
        return b.toString();
    }

    public static String attr(String tag, String name) {
        if (tag == null || name == null) return null;
        String pat = name + "\\s*=\\s*\"([^\"]*)\"";
        Matcher m = Pattern.compile(pat).matcher(tag);
        if (m.find()) return m.group(1);
        m = Pattern.compile(name + "\\s*=\\s*'([^']*)'").matcher(tag);
        if (m.find()) return m.group(1);
        return null;
    }

    // ══════════════════════════════════════════════════════════
    //  章节序号
    // ══════════════════════════════════════════════════════════

    /** 章节文件名前缀：优先「第N章」的 N，否则用 chapterId，再否则自增 */
    public static String prefix(String chapterId, String title) {
        int ord = ordinal(title);
        if (ord > 0) return String.format(Locale.US, "%04d", ord);
        if (Files.notEmpty(chapterId)) {
            String cid = chapterId.replaceAll("[^0-9]", "");
            if (!cid.isEmpty()) {
                while (cid.length() < 10) cid = "0" + cid;
                return "zz" + cid;
            }
        }
        return "zz" + String.format(Locale.US, "%06d", Store.SEQ.incrementAndGet());
    }

    public static int ordinal(String title) {
        if (title == null) return -1;
        Matcher m = P_NUM.matcher(title);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Throwable ignore) {
            }
        }
        Matcher c = P_NUM_CN.matcher(title);
        if (c.find()) {
            int v = cn2int(c.group(1));
            if (v > 0) return v;
        }
        return -1;
    }

    public static int cn2int(String s) {
        int total = 0, section = 0, number = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '零' || ch == '〇') {
                any = true;
                continue;
            }
            int d = CN_DIGITS.indexOf(ch);
            if (d >= 0) {
                number = d + 1;
                any = true;
                continue;
            }
            if (ch == '两') {
                number = 2;
                any = true;
                continue;
            }
            int u;
            if (ch == '十') u = 10;
            else if (ch == '百') u = 100;
            else if (ch == '千') u = 1000;
            else if (ch == '万') u = 10000;
            else return -1;
            any = true;
            if (u == 10000) {
                section = (section + (number == 0 ? 1 : number)) * u;
                total += section;
                section = 0;
                number = 0;
            } else {
                if (number == 0) number = 1;
                section += number * u;
                number = 0;
            }
        }
        if (!any) return -1;
        return total + section + number;
    }

    // ══════════════════════════════════════════════════════════
    //  章标题去重
    //
    //  番茄的章节 XHTML 里，**第一个 <blk> 往往就是章标题本身**：
    //      <blk>第三章 开端</blk><blk>正文第一段……</blk>
    //  而我们又会在正文前面写一行标题，不去重就是「两个标题」。
    //
    //  更麻烦的是两侧写法常不一样：
    //      宿主 ChapterInfo.name = "第三章 开端"
    //      正文首块           = "第3章　开端"   （中文数字 + 全角空格）
    //  所以比较不能直接 equals，得先把「第N章」前缀归一化成阿拉伯数字再比。
    // ══════════════════════════════════════════════════════════

    private static final Pattern P_HEAD = Pattern.compile(
            "^第\\s*([0-9]+|[〇零一二三四五六七八九两十百千万]+)\\s*([章节回卷])");

    /** 去掉所有空白（含全角空格 \u3000 与不换行空格） */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000' || c == '\u00A0') continue;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * 标题归一化键：
     *   "第三章 开端" → "3|开端"
     *   "第3章"       → "3|"
     *   "楔子"        → "楔子"
     */
    public static String titleKey(String s) {
        String t = norm(s);
        if (t.isEmpty()) return "";
        Matcher m = P_HEAD.matcher(t);
        if (m.find()) {
            String num = m.group(1);
            int n;
            if (num.matches("[0-9]+")) {
                try {
                    n = Integer.parseInt(num);
                } catch (Throwable e) {
                    n = -1;
                }
            } else {
                n = cn2int(num);
            }
            if (n > 0) {
                return n + "|" + t.substring(m.end());
            }
        }
        return t;
    }

    /** 正文首块是不是就是标题（重复了） */
    public static boolean dupTitle(String title, String first) {
        String a = titleKey(title);
        String b = titleKey(first);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;

        int pa = a.indexOf('|'), pb = b.indexOf('|');
        if (pa >= 0 && pb >= 0) {
            String na = a.substring(0, pa), nb = b.substring(0, pb);
            if (na.equals(nb)) {
                String ra = a.substring(pa + 1), rb = b.substring(pb + 1);
                // 一边只有「第N章」，另一边是「第N章 + 标题」→ 同一个标题
                if (ra.isEmpty() || rb.isEmpty()) return true;
                if (ra.equals(rb)) return true;
                // 标题少写了后半段（「开端」 vs 「开端(修)」）也算
                if (ra.length() >= 2 && rb.startsWith(ra) && rb.length() - ra.length() <= 12) return true;
                if (rb.length() >= 2 && ra.startsWith(rb) && ra.length() - rb.length() <= 12) return true;
            }
        }
        // 标题没有「第N章」前缀，而首块有，且首块去掉前缀后正好等于标题
        if (pb >= 0) {
            String rb = b.substring(pb + 1);
            if (!rb.isEmpty() && rb.equals(a)) return true;
        }
        if (pa >= 0) {
            String ra = a.substring(pa + 1);
            if (!ra.isEmpty() && ra.equals(b)) return true;
        }
        // 纯前后缀包含（标题只是首块的一段）
        if (b.startsWith(a) && b.length() - a.length() <= 10) return true;
        if (a.startsWith(b) && a.length() - b.length() <= 10) return true;
        return false;
    }

    /**
     * 判定为同一个标题时，取信息更全的那个当标题。
     * 典型场景：宿主 ChapterInfo.name = "第3章"（没副标题），
     *          正文首块 = "第3章 开端"。
     * 这时直接丢掉首块会把「开端」也丢掉，所以要把首块升级成标题再吃掉它。
     */
    public static String richerTitle(String title, String first) {
        String a = title == null ? "" : title.trim();
        String b = first == null ? "" : first.trim();
        if (b.isEmpty()) return a;
        String ka = titleKey(a), kb = titleKey(b);
        int pa = ka.indexOf('|'), pb = kb.indexOf('|');
        if (pa >= 0 && pb >= 0 && ka.substring(0, pa).equals(kb.substring(0, pb))) {
            if (kb.substring(pb + 1).length() > ka.substring(pa + 1).length()) return b;
            return a;
        }
        if (pb >= 0 && pa < 0) return b;      // 有「第N章」前缀的更全
        if (a.isEmpty()) return b;
        return a;
    }

    /** 首块看起来像章标题吗（只认「第N章/节/回/卷」这种硬特征，不瞎吃正文） */
    public static boolean looksLikeChapterTitle(String s) {
        String t = norm(s);
        if (t.isEmpty() || t.length() > 40) return false;
        return P_HEAD.matcher(t).find();
    }

    // ══════════════════════════════════════════════════════════
    //  统计（校对用）
    // ══════════════════════════════════════════════════════════

    /** 原始 XHTML 去标签后的非空白字符数（整篇，含 <h1> 等） */
    public static int rawTextLen(String xml) {
        if (xml == null) return 0;
        String t = P_TAG.matcher(xml).replaceAll("");
        t = unescape(t);
        int n = 0;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isWhitespace(t.charAt(i))) n++;
        }
        return n;
    }

    /**
     * 只数 <blk> 里的非空白字符 —— 这才是「导出侧真正会落进正文的那部分」。
     *
     * 为什么不能直接用 rawTextLen：章节 XHTML 里还有 <h1> 章名、<div> 结构等，
     * 它们不进正文。拿整篇去比，每章都会显示「比原文少 N 字」，
     * 校对报告就变成狼来了，真丢字反而看不出来。
     */
    public static int rawBlkLen(String xml) {
        if (xml == null) return 0;
        int n = 0, i = 0;
        while (true) {
            int b = xml.indexOf("<blk", i);
            if (b < 0) break;
            int gt = xml.indexOf('>', b);
            if (gt < 0) break;
            int e = xml.indexOf("</blk>", gt);
            if (e < 0) break;
            String t = cleanText(xml.substring(gt + 1, e));
            for (int k = 0; k < t.length(); k++) {
                if (!Character.isWhitespace(t.charAt(k))) n++;
            }
            i = e + 6;
        }
        return n;
    }

    /** 从 <h1> 里抠章名（宿主没给 chapterName 时的兜底） */
    public static String titleOf(String xml) {
        if (xml == null) return "";
        int b = xml.indexOf("<h1");
        if (b < 0) return "";
        int gt = xml.indexOf('>', b);
        if (gt < 0) return "";
        int e = xml.indexOf("</h1>", gt);
        if (e < 0) return "";
        return cleanText(xml.substring(gt + 1, e));
    }

    /** 字符串里的非空白字符数 */
    public static int nonWsLen(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) n++;
        }
        return n;
    }

    /** 导出侧真正落进正文的非空白字符数（正文块 + 图注） */
    public static int outChars(List<Blk> blks) {
        int n = 0;
        if (blks == null) return 0;
        for (Blk b : blks) {
            String t = b.text;
            if (t == null) continue;
            for (int i = 0; i < t.length(); i++) {
                if (!Character.isWhitespace(t.charAt(i))) n++;
            }
        }
        return n;
    }

    public static int countOf(String s, String needle) {
        if (s == null || needle == null || needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while (true) {
            int k = s.indexOf(needle, i);
            if (k < 0) break;
            n++;
            i = k + needle.length();
        }
        return n;
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** 便于调试：把块列表压成一行摘要 */
    public static String brief(Map<String, Object> unused) {
        return "";
    }
}
