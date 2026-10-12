package com.fq.localsave;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 抓取层：在宿主自己的解密链路上取明文。
 *
 * 核心事实（已由公开项目实测确认，见 fq-hook / hongguo 反编译）：
 *   番茄把章节解密收敛到一个 native 咽喉点：
 *       com.dragon.read.crypt.CryptManager
 *           public static native byte[] decrypt(String 密文, String 书级key, int mVer)
 *   实现在 libdragon_crypt.so，返回值恒为 gzip，解压即 XHTML。
 *   所以模块不实现任何加解密算法，只做三件事：拿密文、拿密钥、调宿主的 decrypt。
 *
 * 四个落点：
 *   ① CryptManager.decrypt(after)              —— 被动截获明文（最稳，必然成功）
 *   ② ChapterInfo.<任意返回自身的方法>(after)   —— 唯一的信息汇聚点，一次拿全
 *   ③ DecryptKey.<init>(after)                 —— 学到书级密钥
 *   ④ reader.utils.n.a(after)                  —— 覆盖「已下载从 DB 读」的章节
 *
 * 版本适配：类名硬编码候选表 + DEX 字符串表扫描（Dex 类）双保险，
 * 任何一个候选命中即可；全不命中就只记日志，绝不崩宿主。
 */
public final class Hooks {

    private static volatile Class<?> CLS_CRYPT;
    private static volatile ClassLoader CL;

    /** 自己发起的 decrypt 调用要跳过，否则会重复抓一遍 */
    private static final ThreadLocal<Boolean> SELF = new ThreadLocal<Boolean>();

    /** 宿主下载按钮的 Kotlin 回调（Function2<reader, Continuation, Object>） */
    private static volatile Object dlCallback;
    /** 回调第一个参数的类型（reader） */
    private static volatile Class<?> readerCls;
    /** 抓到的 reader 实例 */
    private static volatile Object readerObj;
    /** 回调 setter 的参数类型（Function2） */
    private static volatile Class<?> functionCls;

    private static final Set<String> LOGGED = java.util.Collections.synchronizedSet(new HashSet<String>());

    private Hooks() {
    }

    private static void once(String key, String msg) {
        if (LOGGED.add(key)) Logs.i(msg);
    }

    // ══════════════════════════════════════════════════════════
    //  类解析
    // ══════════════════════════════════════════════════════════

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, CL);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 先试硬编码候选；全失败再用 DEX 扫描按简单类名兜底。
     *
     * @param fixed      已知类名候选（按新→旧排列）
     * @param simpleName DEX 扫描用的简单类名，null 表示不扫
     */
    private static Class<?> resolve(String[] fixed, String simpleName) {
        for (String n : fixed) {
            Class<?> c = load(n);
            if (c != null) {
                Logs.i("命中类 " + n);
                return c;
            }
        }
        if (simpleName == null) return null;
        List<String> cands = Dex.bySimpleName(simpleName);
        for (String n : cands) {
            Class<?> c = load(n);
            if (c != null) {
                Logs.i("DEX 扫描命中类 " + n + "（硬编码候选全部失效）");
                return c;
            }
        }
        Logs.w("找不到类 " + simpleName + "，DEX 候选=" + cands);
        return null;
    }

    // ══════════════════════════════════════════════════════════
    //  安装
    // ══════════════════════════════════════════════════════════

    public static void install(ClassLoader cl) {
        CL = cl;
        hookCrypt();
        hookChapterInfo();
        hookDecryptKey();
        hookReaderUtil();
        hookDownloadButton();
    }

    // ── ① CryptManager.decrypt ───────────────────────────────
    private static void hookCrypt() {
        try {
            Class<?> c = resolve(new String[]{
                    "com.dragon.read.crypt.CryptManager",
                    "com.dragon.read.reader.crypt.CryptManager",
                    "com.dragon.read.kmp.crypt.CryptManager",
                    "com.dragon.read.crypt.a"}, "CryptManager");
            if (c == null) {
                Logs.e("CryptManager 未命中 —— 抓取会降级到 reader.utils 路径");
                return;
            }
            CLS_CRYPT = c;
            XposedBridge.hookAllMethods(c, "decrypt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        onDecrypt(p);
                    } catch (Throwable t) {
                        Logs.e("onDecrypt", t);
                    }
                }
            });
            Logs.i("hook CryptManager.decrypt OK :: " + Dex.dumpStatics(c));
        } catch (Throwable t) {
            Logs.e("hook CryptManager 失败", t);
        }
    }

    private static void onDecrypt(XC_MethodHook.MethodHookParam p) {
        if (Boolean.TRUE.equals(SELF.get())) return;
        Object r = p.getResult();
        if (!(r instanceof byte[])) return;
        byte[] res = (byte[]) r;
        if (res.length == 0) return;
        String a0 = (p.args != null && p.args.length > 0 && p.args[0] instanceof String)
                ? (String) p.args[0] : null;
        if (a0 == null) return;
        final Store.Info info = Store.BY_CONTENT.get(Files.md5hex(a0));
        if (info == null) return;                 // 不知道是哪一章，放过
        final byte[] raw = res;
        Store.pool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] xml = Files.isGzip(raw) ? Files.gunzip(raw) : raw;
                    if (xml == null || xml.length == 0) return;
                    int n = Store.bufPut(info, xml);
                    if (n > 0) {
                        once("cap:" + info.bookId,
                                "抓取路径=decrypt 截获 《" + info.bookName + "》");
                        Logs.i("已缓存《" + info.bookName + "》" + info.chapterName + "（共 " + n + " 章）");
                    }
                } catch (Throwable t) {
                    Logs.e("bufPut(onDecrypt)", t);
                }
            }
        });
    }

    // ── ② ChapterInfo ────────────────────────────────────────
    private static void hookChapterInfo() {
        try {
            Class<?> c = resolve(new String[]{
                    "com.dragon.read.reader.download.ChapterInfo",
                    "com.dragon.read.kmp.reader.download.ChapterInfo"}, "ChapterInfo");
            if (c == null) return;
            int hooked = 0;
            // 不认方法名，只认形状：把这个类上所有非抽象方法都挂一遍，
            // 再从「返回值 / this / 每个参数」里试着抽出 bookId+content。
            // 抽不出来就安静放过 —— 宿主把工厂方法改名/改签名都不会让抓取整个失效。
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                probeInfo(p.getResult());
                                probeInfo(p.thisObject);
                                if (p.args != null) {
                                    for (Object a : p.args) probeInfo(a);
                                }
                            } catch (Throwable t) {
                                Logs.e("onChapterInfo", t);
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable ignore) {
                }
            }
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(k, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            probeInfo(p.thisObject);
                        }
                    });
                    hooked++;
                } catch (Throwable ignore) {
                }
            }
            Logs.i("hook ChapterInfo 完成，挂了 " + hooked + " 个方法/构造器；候选方法="
                    + Dex.describeMethods(c, -1));
        } catch (Throwable t) {
            Logs.e("hook ChapterInfo 失败", t);
        }
    }

    /** 从任意对象里试着抽出章节信息；抽不出来就安静返回 */
    private static void probeInfo(Object o) {
        if (o == null) return;
        Store.Info in = readInfo(o);
        if (in == null || in.content == null || in.content.isEmpty() || in.hash == null) return;
        if (in.bookId == null) return;
        if (Store.BY_CONTENT.size() > Store.MAP_MAX) Store.BY_CONTENT.clear();
        Store.BY_CONTENT.put(in.hash, in);
        once("ci:" + in.bookId, "已识别书籍 《" + in.bookName + "》 bookId=" + in.bookId);
        // 密钥已经学到就立刻自己解一遍（下载瞬间即落盘）
        if (!Store.KEYS.isEmpty()) {
            submitActive(in);
        } else {
            synchronized (Store.PENDING) {
                if (Store.PENDING.size() < Store.PENDING_MAX) Store.PENDING.add(in);
            }
        }
    }

    private static void submitActive(final Store.Info in) {
        Store.pool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] gz = tryDecrypt(in.content, in.keyVersion);
                    if (gz == null) {
                        synchronized (Store.PENDING) {
                            if (Store.PENDING.size() < Store.PENDING_MAX) Store.PENDING.add(in);
                        }
                        return;
                    }
                    byte[] xml = Files.isGzip(gz) ? Files.gunzip(gz) : gz;
                    if (xml == null || xml.length == 0) return;
                    int n = Store.bufPut(in, xml);
                    if (n > 0) {
                        Logs.i("已缓存(自解密)《" + in.bookName + "》" + in.chapterName + "（共 " + n + " 章）");
                    }
                } catch (Throwable t) {
                    Logs.e("submitActive", t);
                }
            }
        });
    }

    // ── ③ DecryptKey ─────────────────────────────────────────
    /**
     * 学书级密钥。
     *
     * 已知字段布局（公开项目实测）：a=v1key(String) b=v2key(String) c=ver(int)
     *                              d=m_ver(int) e=Source f=notNeedDecrypt(boolean)
     *
     * 但字段名同样会随版本变。所以这里做双轨：
     *   ① 按已知名字直接取，命中就登记
     *   ② 兜底：把「所有长度 >= 8 的 String 字段」× 「所有 int 字段」都登记成候选
     *      —— 因为 tryDecrypt 是用 gzip 魔数 1f8b 校验的，错的密钥天然被淘汰，
     *      多登记几个候选的代价只是每章多几次 native 调用，换来的是改名也不失效。
     */
    private static void hookDecryptKey() {
        try {
            Class<?> c = resolve(new String[]{
                    "com.dragon.read.reader.DecryptKey",
                    "com.dragon.read.kmp.reader.DecryptKey"}, "DecryptKey");
            if (c == null) return;
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        learnKeys(p.thisObject);
                    } catch (Throwable t) {
                        Logs.e("DecryptKey.<init>", t);
                    }
                }
            });
            Logs.i("hook DecryptKey.<init> OK；字段=" + fieldsOf(c));
        } catch (Throwable t) {
            Logs.e("hook DecryptKey 失败", t);
        }
    }

    private static void learnKeys(Object o) {
        if (o == null) return;
        boolean any = false;
        // ① 已知字段名
        String v2 = str(o, "b");
        int ver = num(o, "c", -1);
        int mver = num(o, "d", -1);
        boolean nd = bool(o, "f");
        if (!nd && v2 != null && !v2.isEmpty()) {
            any |= Store.learnKey(v2, ver, mver);
        }
        // ② 字段名兜底：String 候选 × int 候选
        List<String> strs = new ArrayList<String>();
        List<Integer> ints = new ArrayList<Integer>();
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.length() >= 8 && strs.size() < 4) strs.add(s);
                } else if (v instanceof Integer && ints.size() < 4) {
                    ints.add((Integer) v);
                }
            }
        } catch (Throwable ignore) {
        }
        if (strs.isEmpty()) return;
        if (ints.isEmpty()) ints.add(-1);
        for (String s : strs) {
            for (Integer m : ints) {
                if (v2 != null && s.equals(v2) && m == mver) continue;   // ① 已经登记过
                any |= Store.learnKey(s, m, m);
            }
        }
        if (any) drainPending();
    }

    private static void drainPending() {
        final List<Store.Info> todo = new ArrayList<Store.Info>();
        synchronized (Store.PENDING) {
            while (!Store.PENDING.isEmpty() && todo.size() < 150) todo.add(Store.PENDING.removeFirst());
        }
        for (final Store.Info fi : todo) submitActive(fi);
    }

    // ── ④ reader.utils.n.a(ChapterInfo, DecryptKey) ──────────
    /**
     * 同样不认方法名：把整类方法都挂上，只看「返回值是不是一段章节 XHTML」。
     * 这条路径专门兜「已下载、从 DB 读」的章节 —— 那些不走 ChapterInfo。
     */
    private static void hookReaderUtil() {
        try {
            Class<?> c = resolve(new String[]{
                    "com.dragon.read.reader.utils.n",
                    "com.dragon.read.kmp.reader.utils.n"}, null);
            if (c == null) {
                Logs.w("reader.utils.n 未命中（可接受：decrypt 路径已足够）");
                return;
            }
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length < 1) return;
                        Store.Info in = readInfo(p.args[0]);
                        if (in != null && in.hash != null) Store.BY_CONTENT.put(in.hash, in);
                    } catch (Throwable ignore) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object r = p.getResult();
                        if (!(r instanceof String)) return;
                        String x = (String) r;
                        if (x.length() < 32) return;
                        if (x.indexOf("<?xml") < 0 && x.indexOf("<blk") < 0) return;
                        if (p.args == null || p.args.length < 1) return;
                        Store.Info in = readInfo(p.args[0]);
                        if (in == null || in.bookId == null) return;
                        int n = Store.bufPut(in, x.getBytes(Files.UTF8));
                        if (n > 0) {
                            once("cap2:" + in.bookId, "抓取路径=reader.utils 明文 《" + in.bookName + "》");
                        }
                    } catch (Throwable t) {
                        Logs.e("reader.utils after", t);
                    }
                }
            };
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                if (m.getParameterTypes().length < 1) continue;
                try {
                    XposedBridge.hookMethod(m, h);
                    n++;
                } catch (Throwable ignore) {
                }
            }
            Logs.i("hook reader.utils 完成，挂了 " + n + " 个方法；方法=" + Dex.describeMethods(c, -1));
        } catch (Throwable t) {
            Logs.e("hook reader.utils 失败", t);
        }
    }

    // ── ⑤ 下载按钮回调（用于「帮点宿主下载」） ───────────────
    /**
     * DownloadUiState 里有个 MutableState 存的就是按钮点击回调 Function2<reader, Continuation, Object>。
     * 这里不改字段名，只按「参数类型是 kotlin.jvm.functions.Function2 的单参方法」定位它的 setter，
     * 顺手从泛型签名里读出 reader 的真实类型，再挂那个类型的构造器拿实例。
     * 三步都成功，我们就能直接调用宿主自己的下载流程 —— 不需要知道按钮在屏幕上的坐标。
     */
    private static void hookDownloadButton() {
        try {
            Class<?> state = resolve(new String[]{
                    "com.dragon.read.kmp.reader.download.DownloadUiState",
                    "com.dragon.read.reader.download.DownloadUiState"}, null);
            if (state == null) {
                Logs.w("DownloadUiState 未命中 —— 悬浮按钮改为「前台常显」，触发下载只能靠校准坐标");
                return;
            }
            Store.readerHookOk = true;
            // 只要这个类被实例化，就说明阅读页在装配下载按钮
            XposedBridge.hookAllConstructors(state, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Store.markReaderAlive(p.thisObject);
                }
            });
            int n = 0;
            for (Method m : state.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 1) continue;
                Type[] gt = m.getGenericParameterTypes();
                if (gt.length != 1) continue;
                String tn = gt[0].toString();
                if (tn.indexOf("Function2") < 0) continue;
                try {
                    functionCls = m.getParameterTypes()[0];
                    if (gt[0] instanceof ParameterizedType) {
                        Type[] args = ((ParameterizedType) gt[0]).getActualTypeArguments();
                        if (args.length >= 1) {
                            String rn = args[0].toString();       // 例如 cm4.i（reader 接口）
                            readerCls = load(rn);
                            if (readerCls != null) {
                                Logs.i("从泛型签名解析出 reader 类型: " + rn
                                        + (readerCls.isInterface() ? "（接口，只能从参数里捞实例）" : ""));
                                if (!readerCls.isInterface()) hookReaderCtor(readerCls);
                            }
                        }
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args != null && p.args.length > 0 && p.args[0] != null) {
                                dlCallback = p.args[0];
                                Store.markReaderAlive(p.thisObject);
                            }
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    Logs.e("hook 下载回调 setter " + m.getName(), t);
                }
            }
            Logs.i("hook 下载按钮回调 完成，setter=" + n + " readerCls=" + readerCls);
            hookDownloadMgrForReader();
        } catch (Throwable t) {
            Logs.e("hookDownloadButton 失败", t);
        }
    }

    /**
     * reader 是个接口（cm4.i 这种），没有构造器可挂，
     * 只能从 DownloadViewMgr 的方法参数里捞实例 —— 而且我们已经知道它的精确类型，
     * 所以可以直接 isInstance 判定，不会误抓。
     */
    private static void hookDownloadMgrForReader() {
        try {
            Class<?> mgr = resolve(new String[]{
                    "com.dragon.read.kmp.reader.download.DownloadViewMgr",
                    "com.dragon.read.reader.download.DownloadViewMgr"}, null);
            if (mgr == null) return;
            XposedBridge.hookAllMethods(mgr, "g", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    grabReader(p.args);
                }
            });
            for (Method m : mgr.getDeclaredMethods()) {
                final String name = m.getName();
                if ("g".equals(name)) continue;
                if (m.getParameterTypes().length == 0) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            grabReader(p.args);
                        }
                    });
                } catch (Throwable ignore) {
                }
            }
            Logs.i("hook DownloadViewMgr 捞 reader 实例 OK（方法数 " + mgr.getDeclaredMethods().length + "）");
        } catch (Throwable t) {
            Logs.e("hookDownloadMgrForReader 失败", t);
        }
    }

    private static void grabReader(Object[] args) {
        Class<?> rc = readerCls;
        if (rc == null || args == null) return;
        for (Object a : args) {
            if (a != null && rc.isInstance(a)) {
                readerObj = a;
                return;
            }
        }
    }

    private static void hookReaderCtor(Class<?> readerCls) {
        try {
            XposedBridge.hookAllConstructors(readerCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject != null) readerObj = p.thisObject;
                }
            });
        } catch (Throwable t) {
            Logs.e("hook reader 构造器", t);
        }
    }

    /** 调用宿主的下载按钮回调。成功返回 true。 */
    public static boolean invokeDownloadCallback() {
        Object cb = dlCallback;
        Object rd = readerObj;
        if (cb == null) {
            Logs.w("还没有抓到下载回调（阅读页还没装配完？）");
            return false;
        }
        if (rd == null) {
            // 回调内部会拿 reader 取书信息，传 null 有把宿主搞崩的风险，宁可放弃这条路
            Logs.w("抓到回调但还没捞到 reader 实例，跳过（避免给宿主传 null）");
            return false;
        }
        try {
            Class<?> contCls = load("kotlin.coroutines.Continuation");
            if (contCls == null) {
                Logs.w("找不到 kotlin.coroutines.Continuation");
                return false;
            }
            final Object emptyCtx = emptyContext();
            Object cont = Proxy.newProxyInstance(CL, new Class<?>[]{contCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String n = method.getName();
                            if ("getContext".equals(n)) return emptyCtx;
                            if ("resumeWith".equals(n)) return null;
                            if ("toString".equals(n)) return "FQContinuation";
                            if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                            if ("equals".equals(n)) return proxy == args[0];
                            return null;
                        }
                    });
            Method inv = cb.getClass().getMethod("invoke", Object.class, Object.class);
            inv.setAccessible(true);
            Object r = inv.invoke(cb, rd, cont);
            Logs.i("已调用宿主下载回调 invoke(reader=" + (rd == null ? "null" : rd.getClass().getName())
                    + ") -> " + r);
            return true;
        } catch (Throwable t) {
            Logs.e("调用下载回调失败", t);
            return false;
        }
    }

    private static Object emptyContext() {
        try {
            Class<?> c = load("kotlin.coroutines.EmptyCoroutineContext");
            if (c == null) return null;
            Field f = c.getField("INSTANCE");
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  自解密
    // ══════════════════════════════════════════════════════════

    /**
     * 用学到的候选密钥逐个试。
     * 返回值自带校验（gzip 魔数 1f8b），所以就算 keyVersion 的语义对不上，
     * 也能靠「谁能解出 gzip」把正确的组合试出来。
     */
    public static byte[] tryDecrypt(String content, int keyVerHint) {
        Class<?> crypt = CLS_CRYPT;
        if (crypt == null || content == null) return null;
        List<Store.Key> cand = new ArrayList<Store.Key>(Store.KEYS);
        for (int i = 0; i < cand.size(); i++) {
            Store.Key k = cand.get(i);
            if (k.mver == keyVerHint || k.ver == keyVerHint) {
                cand.add(0, cand.remove(i));
                break;
            }
        }
        for (Store.Key k : cand) {
            try {
                SELF.set(Boolean.TRUE);
                Object r = XposedHelpers.callStaticMethod(crypt, "decrypt", content, k.v2, k.mver);
                if (r instanceof byte[]) {
                    byte[] b = (byte[]) r;
                    if (Files.isGzip(b)) return b;
                }
            } catch (Throwable ignore) {
                // 密钥不对时 native 抛异常属正常
            } finally {
                SELF.remove();
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════
    //  反射小工具
    // ══════════════════════════════════════════════════════════

    public static Store.Info readInfo(Object o) {
        if (o == null) return null;
        try {
            Store.Info in = new Store.Info();
            in.bookId = str(o, "bookId");
            in.bookName = str(o, "bookName");
            in.chapterId = str(o, "chapterId");
            in.chapterName = firstStr(o, "name", "chapterName", "chapterTitle", "title");
            in.content = str(o, "content");
            in.contentMd5 = str(o, "contentMd5");
            in.keyVersion = num(o, "keyVersion", -1);
            // 章节序号：番茄的目录模型 com.dragon.read.origin.rpc.model.ChapterList
            // 里是 public int index，这里把能想到的名字都试一遍
            in.chapterIndex = firstNum(o, "chapterIndex", "index", "chapterOrder", "order",
                    "chapterNo", "chapterSeq", "seq", "itemOrder", "chapter_index", "chapter_order");

            if (in.bookId == null || in.bookName == null || in.content == null) {
                // 字段名对不上 → 按「值长什么样」猜一次，猜法只在每个类上跑一遍
                guessFields(o, in);
            }
            if (in.content != null) in.hash = Files.md5hex(in.content);
            if (in.bookId == null || in.content == null) {
                once("readInfo:" + o.getClass().getName(),
                        "章节信息字段对不上（该类字段=" + fieldsOf(o.getClass()) + "）");
                return null;
            }
            return in;
        } catch (Throwable t) {
            Logs.e("readInfo", t);
            return null;
        }
    }

    private static final Set<String> GUESSED = java.util.Collections.synchronizedSet(new HashSet<String>());

    /**
     * 字段名兜底：不看名字看内容。
     *   密文 = 最长的那个 String 字段（章节密文一定是最长的）
     *   bookId / chapterId = 名字里带 id 的短字符串
     * 只在已知字段名全部落空时才跑，且每个类只跑一次。
     */
    private static void guessFields(Object o, Store.Info in) {
        Class<?> c = o.getClass();
        if (!GUESSED.add(c.getName())) return;
        try {
            Field[] fs = c.getDeclaredFields();
            String longest = null, idLike = null, cidLike = null;
            for (Field f : fs) {
                if (f.isSynthetic() || f.getType() != String.class) continue;
                f.setAccessible(true);
                String v = (String) f.get(o);
                if (v == null || v.isEmpty()) continue;
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (longest == null || v.length() > longest.length()) longest = v;
                if (n.contains("book") && n.contains("id")) idLike = v;
                else if (n.contains("chapter") && n.contains("id")) cidLike = v;
                else if (idLike == null && n.endsWith("id")) idLike = v;
            }
            if (in.content == null && longest != null && longest.length() >= 64) in.content = longest;
            if (in.bookId == null) in.bookId = idLike;
            if (in.chapterId == null) in.chapterId = cidLike;
            if (in.bookName == null) in.bookName = firstStr(o, "bookTitle", "novelName", "book_name");
            Logs.w("章节信息按值猜字段: bookId=" + in.bookId + " chapterId=" + in.chapterId
                    + " content.len=" + (in.content == null ? -1 : in.content.length())
                    + "（该类字段=" + fieldsOf(c) + "）");
        } catch (Throwable t) {
            Logs.e("guessFields", t);
        }
    }

    private static String firstStr(Object o, String... names) {
        for (String n : names) {
            String v = str(o, n);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** 第一个 >= 0 的 int 字段；都没有就 -1 */
    private static int firstNum(Object o, String... names) {
        for (String n : names) {
            int v = num(o, n, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE && v >= 0) return v;
        }
        return -1;
    }

    public static String str(Object o, String field) {
        try {
            Field f = findField(o.getClass(), field);
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(o);
            return v instanceof String ? (String) v : (v == null ? null : String.valueOf(v));
        } catch (Throwable t) {
            return null;
        }
    }

    public static int num(Object o, String field, int def) {
        try {
            Field f = findField(o.getClass(), field);
            if (f == null) return def;
            f.setAccessible(true);
            Object v = f.get(o);
            return v instanceof Number ? ((Number) v).intValue() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean bool(Object o, String field) {
        try {
            Field f = findField(o.getClass(), field);
            if (f == null) return false;
            f.setAccessible(true);
            Object v = f.get(o);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                return k.getDeclaredField(name);
            } catch (Throwable ignore) {
                k = k.getSuperclass();
            }
        }
        return null;
    }

    private static String fieldsOf(Class<?> c) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(" + t + ")";
        }
    }
}
