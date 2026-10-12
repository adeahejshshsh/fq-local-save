package com.fq.localsave;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志：XposedBridge（logcat）+ 内存环形缓冲 + 落盘到 Download/番茄本地/_运行日志.txt。
 *
 * 落盘走节流：正常情况 2 秒合并一次；关键节点调 flush(true) 强制写。
 * 落盘自身出错必须静默 —— 否则 Logs -> Files -> Logs 会递归。
 */
public final class Logs {

    public static final String TAG = "FQLocal";

    private static final LinkedList<String> RING = new LinkedList<String>();
    private static final int RING_MAX = 400;
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static final Object LOCK = new Object();

    private static volatile long lastFlush = 0L;
    private static volatile boolean flushing = false;

    private Logs() {
    }

    public static void i(String s) {
        add("I", s);
    }

    public static void w(String s) {
        add("W", s);
    }

    public static void e(String s) {
        add("E", s);
    }

    public static void e(String s, Throwable t) {
        add("E", s + " :: " + t + " @ " + where(t));
    }

    private static String where(Throwable t) {
        if (t == null) return "?";
        StackTraceElement[] st = t.getStackTrace();
        if (st == null || st.length == 0) return "?";
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("com.fq.localsave")) continue;
            return cn + "." + e.getMethodName() + ":" + e.getLineNumber();
        }
        return st[0].toString();
    }

    private static void add(String lv, String msg) {
        String line = TS.format(new Date()) + " [" + lv + "] " + msg;
        try {
            XposedBridge.log(TAG + " " + line);
        } catch (Throwable ignore) {
        }
        try {
            Log.i(TAG, line);
        } catch (Throwable ignore) {
        }
        synchronized (LOCK) {
            RING.add(line);
            while (RING.size() > RING_MAX) RING.removeFirst();
        }
        if ("E".equals(lv)) {
            scheduleFlush(true);
        } else {
            long now = System.currentTimeMillis();
            if (now - lastFlush > 2000L) scheduleFlush(false);
        }
    }

    /**
     * 落盘是 MediaStore 查询 + 读 + 写，可能很慢，
     * 而 add() 有可能跑在宿主的 UI 线程上（hook Activity.onResume 之类），
     * 所以一律丢到线程池里做，绝不在调用线程上同步写盘。
     */
    private static void scheduleFlush(final boolean force) {
        try {
            Store.pool().execute(new Runnable() {
                @Override
                public void run() {
                    flush(force);
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** 把环形缓冲里还没写出的部分追加到日志文件 */
    public static void flush(boolean force) {
        if (flushing) return;
        synchronized (LOCK) {
            if (flushing) return;
            flushing = true;
        }
        try {
            if (Store.app() == null) return;
            long now = System.currentTimeMillis();
            if (!force && now - lastFlush < 2000L) return;
            lastFlush = now;
            String pending;
            synchronized (LOCK) {
                if (RING.isEmpty()) return;
                StringBuilder sb = new StringBuilder();
                for (String s : RING) sb.append(s).append('\n');
                RING.clear();
                pending = sb.toString();
            }
            Files.append(Files.ROOT, Files.LOG_NAME, pending.getBytes(Files.UTF8), "text/plain");
        } catch (Throwable ignore) {
            // 绝对不能再往里记日志，会递归
        } finally {
            flushing = false;
        }
    }

    /** 启动时清空一次旧日志 */
    public static void reset() {
        try {
            Files.write(Files.ROOT, Files.LOG_NAME,
                    ("=== 番茄本地下载 启动 " + TS.format(new Date()) + " ===\n").getBytes(Files.UTF8),
                    "text/plain");
        } catch (Throwable ignore) {
        }
    }

    public static String tail(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            int from = Math.max(0, RING.size() - n);
            for (int i = from; i < RING.size(); i++) sb.append(RING.get(i)).append('\n');
        }
        return sb.toString();
    }
}
