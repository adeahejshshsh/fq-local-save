package com.fq.localsave;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局运行状态：上下文、抓取缓冲、密钥、阅读页存活信号、配置。
 *
 * 这里是整个模块的「内存」，不碰任何加解密算法 —— 明文一律来自宿主自己的解密例程。
 */
public final class Store {

    public static final String PKG = "com.dragon.read";
    public static final String VERSION = "1.0.2";
    public static final String PREFS = "fq_local_save";

    /** 单本缓冲上限 */
    public static final int BUF_MAX_PER_BOOK = 6000;
    /** 密文→Info 索引上限 */
    public static final int MAP_MAX = 4000;
    /** 等待密钥的排队上限 */
    public static final int PENDING_MAX = 800;

    // ── 上下文 ───────────────────────────────────────────────
    private static volatile Context APP;
    private static volatile SharedPreferences SP;
    private static volatile boolean inited = false;

    public static ExecutorService POOL;

    /**
     * 取线程池。hook 回调跑在宿主自己的线程上，任何时刻都可能被调用，
     * 所以这里做懒创建 —— 绝不能因为 POOL 还没建好就在宿主进程里抛 NPE。
     */
    public static ExecutorService pool() {
        ExecutorService p = POOL;
        if (p != null) return p;
        synchronized (Store.class) {
            if (POOL == null) {
                // 守护线程：我们的后台活儿绝不能拖住宿主进程的生命周期
                POOL = Executors.newFixedThreadPool(8, new java.util.concurrent.ThreadFactory() {
                    private final java.util.concurrent.atomic.AtomicInteger n =
                            new java.util.concurrent.atomic.AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "fq-local-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            return POOL;
        }
    }

    public static Context app() {
        Context c = APP;
        if (c != null) return c;
        try {
            c = AndroidAppHelper.currentApplication();
        } catch (Throwable ignore) {
        }
        if (c != null) {
            APP = c;
            try {
                SP = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            } catch (Throwable ignore) {
            }
        }
        return c;
    }

    public static void setApp(Context c) {
        if (c == null) return;
        APP = c;
        try {
            SP = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignore) {
        }
    }

    public static SharedPreferences sp() {
        if (SP == null) app();
        return SP;
    }

    public static boolean isInited() {
        return inited;
    }

    public static void markInited() {
        inited = true;
    }

    // ── 数据结构 ─────────────────────────────────────────────

    /** 宿主 ChapterInfo 抽出来的信息 */
    public static final class Info {
        public String bookId;
        public String bookName;
        public String chapterId;
        public String chapterName;
        public String content;      // 密文
        public String contentMd5;
        public int keyVersion = -1;
        /** 宿主自带的章节序号（多种候选字段名都试）；-1 = 没拿到 */
        public int chapterIndex = -1;
        public String hash;         // md5(密文)，用于与 decrypt 的入参对齐

        public String key() {
            return (bookId == null ? "" : bookId) + "|" + (chapterId == null ? "" : chapterId);
        }
    }

    /** 学到的书级密钥 */
    public static final class Key {
        public final String v2;
        public final int ver;
        public final int mver;

        public Key(String v2, int ver, int mver) {
            this.v2 = v2;
            this.ver = ver;
            this.mver = mver;
        }
    }

    /** 缓冲里的一章（已解压明文 XHTML） */
    public static final class ChapterBuf {
        /** 章节名里解析出的「第N章」的 N；-1 = 没解析出来 */
        public final int ord;
        /** 宿主 ChapterInfo 自带的章节序号（最权威）；-1 = 没拿到 */
        public final int index;
        /** 抓取序号：模块捕获这一章的先后。宿主下载/阅读都是按目录顺序走的，所以这是保底顺序 */
        public final int seq;
        public final String chapterId;
        public final String title;
        public final byte[] xml;

        public ChapterBuf(int ord, int index, int seq, String chapterId, String title, byte[] xml) {
            this.ord = ord;
            this.index = index;
            this.seq = seq;
            this.chapterId = chapterId;
            this.title = title;
            this.xml = xml;
        }
    }

    /** 全局抓取序号 */
    private static final AtomicInteger CAP_SEQ = new AtomicInteger(0);

    // ── 抓取状态 ─────────────────────────────────────────────

    /** bookId -> (chapterId -> 章节) */
    public static final Map<String, Map<String, ChapterBuf>> BUF =
            new ConcurrentHashMap<String, Map<String, ChapterBuf>>();
    /** md5(密文) -> Info */
    public static final Map<String, Info> BY_CONTENT = new ConcurrentHashMap<String, Info>();
    /** 学到的候选密钥 */
    public static final List<Key> KEYS = new CopyOnWriteArrayList<Key>();
    /** 还没等到密钥的章节 */
    public static final LinkedList<Info> PENDING = new LinkedList<Info>();

    public static volatile String lastBookId;
    public static volatile String lastBookName;

    public static final AtomicInteger SEQ = new AtomicInteger(0);

    // ── 阅读页存活信号 ───────────────────────────────────────
    /**
     * 宿主的 DownloadUiState / DownloadViewMgr 一被创建，就说明阅读页在装配下载按钮。
     * 拿弱引用盯着它：对象被回收 == 阅读页真的走了，我们的按钮也就该收起。
     * 这样就不必去猜宿主的页面切换，也不依赖任何混淆类名之外的 UI 结构。
     */
    private static volatile WeakReference<Object> readerStateRef;
    private static volatile long readerSeenAt = 0L;

    /** DownloadUiState 类是否成功命中（命中才敢用「阅读页」信号来控制按钮显隐） */
    public static volatile boolean readerHookOk = false;

    public static void markReaderAlive(Object state) {
        if (state != null) readerStateRef = new WeakReference<Object>(state);
        readerSeenAt = System.currentTimeMillis();
    }

    /** 阅读页是否还在（弱引用活着，或最近 20 秒内出现过） */
    public static boolean isReaderAlive() {
        WeakReference<Object> r = readerStateRef;
        if (r != null && r.get() != null) return true;
        return System.currentTimeMillis() - readerSeenAt < 20000L;
    }

    /** 前台 Activity */
    private static volatile WeakReference<Activity> CURRENT;

    public static void setCurrent(Activity a) {
        if (a != null) CURRENT = new WeakReference<Activity>(a);
    }

    public static Activity current() {
        WeakReference<Activity> r = CURRENT;
        return r == null ? null : r.get();
    }

    // ── 缓冲操作 ─────────────────────────────────────────────

    public static Map<String, ChapterBuf> bufOf(String bookId) {
        if (bookId == null) return null;
        return BUF.get(bookId);
    }

    public static int bufCount(String bookId) {
        Map<String, ChapterBuf> m = bufOf(bookId);
        return m == null ? 0 : m.size();
    }

    /** 收进缓冲，返回该书累计章节数；-1 表示放弃 */
    public static int bufPut(Info in, byte[] xml) {
        if (in == null || in.bookId == null || in.chapterId == null || xml == null) return -1;
        Map<String, ChapterBuf> m = BUF.get(in.bookId);
        if (m == null) {
            m = new ConcurrentHashMap<String, ChapterBuf>();
            BUF.put(in.bookId, m);
        }
        if (m.size() >= BUF_MAX_PER_BOOK && !m.containsKey(in.chapterId)) return -1;
        int seq = CAP_SEQ.incrementAndGet();
        m.put(in.chapterId, new ChapterBuf(Xhtml.ordinal(in.chapterName), in.chapterIndex,
                seq, in.chapterId, in.chapterName, xml));
        lastBookId = in.bookId;
        if (Files.notEmpty(in.bookName)) lastBookName = in.bookName;
        return m.size();
    }

    // ══════════════════════════════════════════════════════════
    //  章节排序
    //
    //  为什么不能只用「第N章」：番茄大量书的章节名里根本没有「第N章」
    //  （「楔子」「觉醒」「001」都有），抠不到就是 -1，全被扔到末尾，
    //  再按标题字符串排 —— 标题也是空的，于是退化成 ConcurrentHashMap 的
    //  哈希顺序，导出来就是完全乱序。这就是 v1.0.0 的锅。
    //
    //  现在改成「按覆盖率挑一个排序键，整本只用这一个」，四档从权威到保底：
    //    ① 宿主自带章节序号（ChapterInfo 的 index 类字段）
    //    ② 章节名里的「第N章」N（覆盖率要够高才敢用）
    //    ③ chapterId 数值（且与抓取顺序高度单调，才说明它真的随章节递增）
    //    ④ 抓取顺序（宿主下载/阅读都是按目录顺序走的，保底但很稳）
    // ══════════════════════════════════════════════════════════

    public static final class Order {
        public final List<ChapterBuf> list;
        public final String strategy;
        public final String detail;
        public final boolean confident;

        Order(List<ChapterBuf> list, String strategy, String detail, boolean confident) {
            this.list = list;
            this.strategy = strategy;
            this.detail = detail;
            this.confident = confident;
        }
    }

    public static Order order(String bookId) {
        Map<String, ChapterBuf> m = bufOf(bookId);
        List<ChapterBuf> all = new ArrayList<ChapterBuf>();
        if (m != null) all.addAll(m.values());
        if (all.isEmpty()) return new Order(all, "空", "没有捕获到章节", false);

        final int total = all.size();
        int withIndex = 0, withOrd = 0, numeric = 0;
        for (ChapterBuf c : all) {
            if (c.index >= 0) withIndex++;
            if (c.ord > 0) withOrd++;
            if (isNumeric(c.chapterId)) numeric++;
        }

        // ① 宿主自带序号 —— 最权威
        if (withIndex >= Math.max(1, (int) (total * 0.9))) {
            java.util.Collections.sort(all, new java.util.Comparator<ChapterBuf>() {
                @Override
                public int compare(ChapterBuf a, ChapterBuf b) {
                    if (a.index != b.index) return a.index < b.index ? -1 : 1;
                    return a.seq < b.seq ? -1 : (a.seq > b.seq ? 1 : 0);
                }
            });
            return new Order(all, "宿主章节序号",
                    "覆盖率 " + withIndex + "/" + total, withIndex == total);
        }

        // ② 章节名里的「第N章」
        if (withOrd >= Math.max(1, (int) (total * 0.9))) {
            java.util.Collections.sort(all, new java.util.Comparator<ChapterBuf>() {
                @Override
                public int compare(ChapterBuf a, ChapterBuf b) {
                    int x = a.ord > 0 ? a.ord : Integer.MAX_VALUE;
                    int y = b.ord > 0 ? b.ord : Integer.MAX_VALUE;
                    if (x != y) return x < y ? -1 : 1;
                    return a.seq < b.seq ? -1 : (a.seq > b.seq ? 1 : 0);
                }
            });
            return new Order(all, "章节名「第N章」",
                    "覆盖率 " + withOrd + "/" + total, withOrd == total);
        }

        // ③ chapterId 数值 —— 只有当它跟抓取顺序高度单调时才敢信
        //    （番茄有「章节调整」接口，item_id 未必随章节递增）
        if (numeric == total) {
            List<ChapterBuf> bySeq = new ArrayList<ChapterBuf>(all);
            java.util.Collections.sort(bySeq, new java.util.Comparator<ChapterBuf>() {
                @Override
                public int compare(ChapterBuf a, ChapterBuf b) {
                    return a.seq < b.seq ? -1 : (a.seq > b.seq ? 1 : 0);
                }
            });
            int up = 0, pairs = 0;
            for (int i = 1; i < bySeq.size(); i++) {
                long p = parseLong(bySeq.get(i - 1).chapterId);
                long q = parseLong(bySeq.get(i).chapterId);
                if (p < 0 || q < 0) continue;
                pairs++;
                if (q > p) up++;
            }
            double rate = pairs == 0 ? 0 : (double) up / pairs;
            if (rate >= 0.95) {
                java.util.Collections.sort(all, new java.util.Comparator<ChapterBuf>() {
                    @Override
                    public int compare(ChapterBuf a, ChapterBuf b) {
                        long p = parseLong(a.chapterId);
                        long q = parseLong(b.chapterId);
                        if (p != q) return p < q ? -1 : 1;
                        return a.seq < b.seq ? -1 : (a.seq > b.seq ? 1 : 0);
                    }
                });
                return new Order(all, "chapterId 数值",
                        "与抓取顺序单调率 " + Math.round(rate * 100) + "%", true);
            }
            Logs.w("chapterId 与抓取顺序单调率只有 " + Math.round(rate * 100)
                    + "%，不敢用它排序，退回抓取顺序");
        }

        // ④ 保底：抓取顺序
        java.util.Collections.sort(all, new java.util.Comparator<ChapterBuf>() {
            @Override
            public int compare(ChapterBuf a, ChapterBuf b) {
                return a.seq < b.seq ? -1 : (a.seq > b.seq ? 1 : 0);
            }
        });
        return new Order(all, "抓取顺序",
                "宿主没给序号、章节名也没「第N章」；按捕获先后排（宿主下载/阅读本身按目录走）",
                false);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty() || s.length() > 19) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return -1L;
        }
    }

    // ── 密钥 ─────────────────────────────────────────────────

    public static boolean learnKey(String v2, int ver, int mver) {
        if (v2 == null || v2.isEmpty()) return false;
        for (Key k : KEYS) {
            if (k.v2.equals(v2) && k.mver == mver) return false;
        }
        KEYS.add(new Key(v2, ver, mver));
        Logs.i("学到书级密钥: v2.len=" + v2.length() + " ver=" + ver + " mVer=" + mver);
        return true;
    }

    // ── 配置 ─────────────────────────────────────────────────

    public static boolean cfgImages() {
        SharedPreferences p = sp();
        return p == null || p.getBoolean("images", true);
    }

    public static boolean cfgHtml() {
        SharedPreferences p = sp();
        return p == null || p.getBoolean("html", true);
    }

    public static boolean cfgXhtml() {
        SharedPreferences p = sp();
        return p != null && p.getBoolean("xhtml", false);
    }

    public static boolean cfgVisible() {
        SharedPreferences p = sp();
        return p == null || p.getBoolean("visible", true);
    }

    public static void setBool(String k, boolean v) {
        SharedPreferences p = sp();
        if (p != null) p.edit().putBoolean(k, v).apply();
    }

    public static int cfgInt(String k, int def) {
        SharedPreferences p = sp();
        return p == null ? def : p.getInt(k, def);
    }

    public static void setInt(String k, int v) {
        SharedPreferences p = sp();
        if (p != null) p.edit().putInt(k, v).apply();
    }
}
