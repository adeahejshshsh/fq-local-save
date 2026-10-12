package com.fq.localsave;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 界面注入层。
 *
 * 目标：在阅读页宿主的「下载」按钮旁边，多出一个我们自己的按钮，点一下就把整本
 * （含插图）落到 /sdcard/Download/番茄本地/。
 *
 * 为什么不做「精确插进宿主按钮旁边」：
 *   番茄 7.3.x 的阅读页顶栏是 Compose 画的（DownloadButtonKt），
 *   原生 View 树里根本没有那个按钮的实体；Compose 的语义树在进程内
 *   没有无障碍连接时拿不到子节点（AccessibilityNodeInfo 未 sealed，getChild 会抛）。
 *   所以策略是分层的：
 *     ① 顶栏若是原生 View（老版本）→ 找到锚点 TextView，直接插进它的父容器，天然并排
 *     ② 否则 → 在 DecorView 上放一个可拖动的悬浮按钮，默认摆在顶栏右侧「下载」左侧，
 *        用户拖一次就永久记住位置（SharedPreferences）
 *   两条路都失败也不影响功能：按钮永远在，位置可调。
 */
public final class Ui {

    private static final String TAG_VIEW = "fq_local_save_btn";
    private static final Handler H = new Handler(Looper.getMainLooper());

    /** 宿主下载按钮可能的文案（阅读页顶栏 + 目录页） */
    private static final String[] ANCHORS = {
            "下载全书", "下载全本", "批量下载", "缓存全书", "全本下载",
            "查看下载", "已下载", "下载"
    };

    /** 校准出来的「宿主下载按钮」点击坐标（DecorView 本地坐标） */
    private static volatile int tapX = -1;
    private static volatile int tapY = -1;

    /** 正在跑的抓取监视器 */
    private static volatile Thread watcher;

    private static WeakReference<Activity> host = new WeakReference<Activity>(null);

    private Ui() {
    }

    // ══════════════════════════════════════════════════════════
    //  挂载 / 卸载
    // ══════════════════════════════════════════════════════════

    public static void attach(final Activity act) {
        if (act == null) return;
        Store.setCurrent(act);
        host = new WeakReference<Activity>(act);
        try {
            final View decor = act.getWindow().getDecorView();
            decor.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        install(act, decor);
                    } catch (Throwable t) {
                        Logs.e("install 按钮失败", t);
                    }
                }
            });
            startTicker(act);
        } catch (Throwable t) {
            Logs.e("attach", t);
        }
    }

    private static void install(Activity act, View decor) {
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        if (root.findViewWithTag(TAG_VIEW) != null) return;

        // ① 先试原生锚点：顶栏是原生 View 的版本可以直接并排插进去
        View anchor = findByTexts(decor, ANCHORS);
        View btn = buildButton(act);
        if (anchor != null && anchor.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) anchor.getParent();
            try {
                ViewGroup.LayoutParams lp = anchor.getLayoutParams();
                ViewGroup.LayoutParams copy;
                if (lp == null) {
                    copy = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                } else {
                    // 必须造出与父容器同类型的 LayoutParams，否则 RelativeLayout 之类会 ClassCastException
                    try {
                        copy = lp.getClass().getConstructor(int.class, int.class)
                                .newInstance(lp.width, lp.height);
                    } catch (Throwable t) {
                        copy = new ViewGroup.LayoutParams(lp.width, lp.height);
                    }
                }
                int idx = p.indexOfChild(anchor);
                p.addView(btn, Math.max(0, idx), copy);
                btn.setTag(TAG_VIEW);
                btn.setVisibility(visibleNow(act) ? View.VISIBLE : View.GONE);
                Logs.i("已把按钮并排插进原生锚点父容器: " + p.getClass().getName()
                        + " anchor=" + shortText(anchor));
                return;
            } catch (Throwable t) {
                Logs.e("并排插入失败，退回悬浮方案", t);
            }
        }

        // ② 悬浮方案：挂到 DecorView 上，位置可拖动并持久化
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int[] wh = screenSize(act);
        int defLeft = Math.max(dp(act, 8), wh[0] - dp(act, 132));
        int defTop = statusBarHeight(act) + dp(act, 4);
        lp.leftMargin = Store.cfgInt("btn_x", defLeft);
        lp.topMargin = Store.cfgInt("btn_y", defTop);
        btn.setTag(TAG_VIEW);
        root.addView(btn, lp);
        // 新建出来的按钮默认是 VISIBLE，先按当前判定摆正，免得在非阅读页闪一下
        btn.setVisibility(visibleNow(act) ? View.VISIBLE : View.GONE);
        Logs.i("按钮已挂到 DecorView（悬浮可拖动）: x=" + lp.leftMargin + " y=" + lp.topMargin);
        startTicker(act);
    }

    /** 每秒核对一次：阅读页在不在、用户有没有要求隐藏 */
    private static volatile boolean tickerRunning = false;

    private static void startTicker(final Activity act) {
        if (tickerRunning) return;
        tickerRunning = true;
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity a = Store.current();
                    if (a != null && !a.isFinishing()) {
                        View decor = a.getWindow().getDecorView();
                        View btn = decor.findViewWithTag(TAG_VIEW);
                        if (btn != null) {
                            int want = visibleNow(a) ? View.VISIBLE : View.GONE;
                            if (btn.getVisibility() != want) btn.setVisibility(want);
                        }
                    }
                } catch (Throwable ignore) {
                }
                H.postDelayed(this, 1200L);
            }
        }, 800L);
    }

    /**
     * 显示判定：
     *  - 用户手动隐藏 → 不显示
     *  - 阅读页信号可用（DownloadUiState 类已命中）→ 只在阅读页显示
     *  - 阅读页信号不可用（宿主改版把类挪走了）→ 只要宿主在前台就显示，
     *    宁可能见性差一点，也不能让功能整个消失
     */
    private static boolean visibleNow(Activity act) {
        if (!Store.cfgVisible()) return false;
        if (!Store.readerHookOk) return isForeground(act);
        return Store.isReaderAlive();
    }

    private static boolean isForeground(Activity act) {
        try {
            return act.hasWindowFocus();
        } catch (Throwable t) {
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  按钮本体
    // ══════════════════════════════════════════════════════════

    private static volatile boolean calibrating = false;

    private static View buildButton(final Activity act) {
        final TextView tv = new TextView(act);
        tv.setText("⬇存本地");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setGravity(Gravity.CENTER);
        int px = dp(act, 8), py = dp(act, 5);
        tv.setPadding(px + dp(act, 2), py, px + dp(act, 2), py);
        tv.setBackground(roundBg(0xE6D32F2F));
        tv.setAlpha(0.92f);

        tv.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startLeft, startTop;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        if (lp instanceof FrameLayout.LayoutParams) {
                            startLeft = ((FrameLayout.LayoutParams) lp).leftMargin;
                            startTop = ((FrameLayout.LayoutParams) lp).topMargin;
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!moved && (Math.abs(dx) > dp(act, 4) || Math.abs(dy) > dp(act, 4))) {
                            moved = true;
                        }
                        if (moved && lp instanceof FrameLayout.LayoutParams) {
                            FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) lp;
                            f.leftMargin = (int) (startLeft + dx);
                            f.topMargin = (int) (startTop + dy);
                            v.setLayoutParams(f);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            if (lp instanceof FrameLayout.LayoutParams) {
                                FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) lp;
                                Store.setInt("btn_x", f.leftMargin);
                                Store.setInt("btn_y", f.topMargin);
                                Logs.i("按钮位置已保存: x=" + f.leftMargin + " y=" + f.topMargin);
                            }
                        } else {
                            onClickButton(act, v);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        return tv;
    }

    private static void onClickButton(Activity act, View v) {
        if (calibrating) {
            calibrating = false;
            // 按钮可能被插进了原生锚点的父容器（LinearLayout 之类），
            // 那里的 LayoutParams 不是 FrameLayout 的，强转会崩 —— 所以先判类型
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) lp;
                tapX = f.leftMargin + v.getWidth() / 2;
                tapY = f.topMargin + v.getHeight() / 2;
            } else {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                int[] dloc = new int[2];
                act.getWindow().getDecorView().getLocationOnScreen(dloc);
                tapX = loc[0] - dloc[0] + v.getWidth() / 2;
                tapY = loc[1] - dloc[1] + v.getHeight() / 2;
            }
            Store.setInt("tap_x", tapX);
            Store.setInt("tap_y", tapY);
            ((TextView) v).setText("⬇存本地");
            v.setBackground(roundBg(0xE6D32F2F));
            toast(act, "已记住宿主『下载』按钮位置 (" + tapX + "," + tapY + ")");
            Logs.i("校准完成 tap=(" + tapX + "," + tapY + ")");
            return;
        }
        showPanel(act);
    }

    private static GradientDrawable roundBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(999f);
        g.setStroke(2, 0x66FFFFFF);
        return g;
    }

    // ══════════════════════════════════════════════════════════
    //  面板
    // ══════════════════════════════════════════════════════════

    private static void showPanel(final Activity act) {
        final String bookId = Store.lastBookId;
        final String bookName = Store.lastBookName == null ? "_未捕获" : Store.lastBookName;
        final int count = Store.bufCount(bookId);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(act, 18);
        box.setPadding(p, p, p, dp(act, 8));

        TextView title = new TextView(act);
        title.setText("番茄本地下载");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTextColor(0xFFE53935);
        box.addView(title);

        TextView info = new TextView(act);
        Store.Order od = Store.order(bookId);
        info.setText("当前书：《" + bookName + "》\n已捕获章节：" + count + " 章"
                + "\n章节顺序：" + od.strategy + (od.confident ? " ✔权威" : " ⚠推定")
                + "\n　" + od.detail
                + "\n\n流程：先让宿主把整本解密一遍（点阅读页右上角『下载』，或点下面的\n"
                + "『帮点宿主下载』），模块同步抓明文；抓完再点『保存到本地』。");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        info.setPadding(0, dp(act, 10), 0, dp(act, 10));
        box.addView(info);

        box.addView(button(act, "① 帮点宿主『下载』并等待抓取", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoTriggerDownload(act);
                toast(act, "已尝试触发宿主下载；没反应就自己点一下右上角『下载』");
            }
        }));

        box.addView(button(act, "② 保存已捕获的 " + count + " 章到本地", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doExport(act, bookId, bookName, null);
            }
        }));

        box.addView(button(act, "③ 抓完自动保存（点一下，之后全自动）", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startWatcher(act, bookId, bookName);
            }
        }));

        box.addView(button(act, "校准宿主『下载』按钮位置（拖红点到它上面，再点红点）", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        enterCalibrate(act);
                    }
                }));

        box.addView(button(act, Store.cfgVisible() ? "隐藏悬浮按钮" : "显示悬浮按钮", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Store.setBool("visible", !Store.cfgVisible());
                        toast(act, Store.cfgVisible() ? "已显示" : "已隐藏（长按设置里可恢复）");
                    }
                }));

        box.addView(button(act, "诊断：打印章节原始字段到日志", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dumpChapters(bookId);
                toast(act, "已写入 _运行日志.txt，把「章节原始字段」那几行发我即可");
            }
        }));

        box.addView(button(act, "查看运行日志", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog(act);
            }
        }));

        new AlertDialog.Builder(act)
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private static void enterCalibrate(Activity act) {
        calibrating = true;
        View decor = act.getWindow().getDecorView();
        View btn = decor.findViewWithTag(TAG_VIEW);
        if (btn instanceof TextView) {
            ((TextView) btn).setText("拖我→");
            btn.setBackground(roundBg(0xE6FF6F00));
        }
        toast(act, "把红点拖到宿主『下载』按钮上，再点它一下完成校准");
    }

    private static TextView button(Context c, String text, boolean primary, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTextColor(primary ? Color.WHITE : 0xFF333333);
        b.setGravity(Gravity.CENTER);
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? 0xFFE53935 : 0xFFEEEEEE);
        g.setCornerRadius(dp(c, 8));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    /**
     * 把每一章的原始字段写进日志：chapterId / chapterName / 抠出的 ord / 宿主 index / 抓取 seq。
     * 排序一旦还是不对，看这几行就能直接判断该拿哪个字段当排序键。
     */
    static void dumpChapters(String bookId) {
        try {
            Store.Order od = Store.order(bookId);
            Logs.i("===== 章节原始字段（顺序策略=" + od.strategy + " / " + od.detail
                    + " / 共 " + od.list.size() + " 章）=====");
            int n = 0;
            for (Store.ChapterBuf c : od.list) {
                Logs.i(String.format(java.util.Locale.US,
                        "[%04d] id=%s | 名=%s | 名中N=%d | 宿主index=%d | seq=%d",
                        ++n, c.chapterId, c.title, c.ord, c.index, c.seq));
                if (n >= 60) {
                    Logs.i("… 只打前 60 章");
                    break;
                }
            }
            Logs.i("===== 章节原始字段 结束 =====");
            Logs.flush(true);
        } catch (Throwable t) {
            Logs.e("dumpChapters", t);
        }
    }

    private static void showLog(final Activity act) {
        ScrollView sv = new ScrollView(act);
        TextView tv = new TextView(act);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setText(Logs.tail(200));
        int p = dp(act, 14);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle("运行日志（尾部 200 行）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════
    //  触发宿主下载
    // ══════════════════════════════════════════════════════════

    private static void autoTriggerDownload(Activity act) {
        // 路 ①：原生锚点直接 performClick
        try {
            View decor = act.getWindow().getDecorView();
            View anchor = findByTexts(decor, ANCHORS);
            if (anchor != null && anchor.isClickable()) {
                anchor.performClick();
                Logs.i("已 performClick 原生锚点: " + shortText(anchor));
                return;
            }
        } catch (Throwable t) {
            Logs.e("performClick 锚点失败", t);
        }
        // 路 ②：调用宿主自己的下载按钮回调（抓到的 Kotlin suspend 回调）
        if (Hooks.invokeDownloadCallback()) {
            Logs.i("已调用宿主下载回调");
            return;
        }
        // 路 ③：按校准坐标模拟一次真实点击
        int x = tapX >= 0 ? tapX : Store.cfgInt("tap_x", -1);
        int y = tapY >= 0 ? tapY : Store.cfgInt("tap_y", -1);
        if (x > 0 && y > 0) {
            if (tapAt(act, x, y)) {
                Logs.i("已按校准坐标模拟点击 (" + x + "," + y + ")");
                return;
            }
        }
        Logs.w("三条触发路径都不可用，请手动点宿主『下载』");
    }

    /** 在 DecorView 本地坐标 (x,y) 处派发一次真实的按下+抬起 */
    private static boolean tapAt(Activity act, float x, float y) {
        try {
            View decor = act.getWindow().getDecorView();
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            decor.dispatchTouchEvent(down);
            down.recycle();
            MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
            decor.dispatchTouchEvent(up);
            up.recycle();
            return true;
        } catch (Throwable t) {
            Logs.e("模拟点击失败", t);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  抓取监视 + 自动保存
    // ══════════════════════════════════════════════════════════

    /**
     * 盯住缓冲：章节数不再增长并稳定 8 秒 → 认为宿主下完了 → 自动导出。
     * 超时 8 分钟放弃（但不会丢数据，用户随时可以手动保存）。
     */
    private static void startWatcher(final Activity act, final String bookId, final String bookName) {
        if (bookId == null) {
            toast(act, "还没捕获到章节 —— 先让番茄打开这本书");
            return;
        }
        if (watcher != null && watcher.isAlive()) {
            toast(act, "已经在盯了");
            return;
        }
        toast(act, "开始盯抓取：宿主下完就自动保存到本地");
        watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                int last = -1, stable = 0;
                long t0 = System.currentTimeMillis();
                try {
                    while (System.currentTimeMillis() - t0 < 8 * 60 * 1000L) {
                        int now = Store.bufCount(bookId);
                        if (now != last) {
                            last = now;
                            stable = 0;
                            Logs.i("抓取中… " + now + " 章");
                        } else if (now > 0) {
                            stable++;
                        }
                        if (now > 0 && stable >= 8) break;
                        Thread.sleep(1000L);
                    }
                } catch (Throwable ignore) {
                }
                final int n = Store.bufCount(bookId);
                final Activity a = Store.current();
                if (n <= 0) {
                    if (a != null) {
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast(a, "没抓到章节：宿主没有解密动作。请点阅读页右上角『下载』");
                            }
                        });
                    }
                    watcher = null;
                    return;
                }
                if (a != null) {
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(a, "抓取稳定（" + n + " 章），开始保存…");
                            doExport(a, bookId, bookName, null);
                        }
                    });
                }
                watcher = null;
            }
        }, "fq-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    // ══════════════════════════════════════════════════════════
    //  导出
    // ══════════════════════════════════════════════════════════

    public static void doExport(final Activity act, final String bookId, final String bookName,
                                final Runnable after) {
        final Store.Order order = Store.order(bookId);
        if (order.list.isEmpty()) {
            toast(act, "还没有捕获到章节：先让宿主把这本书解密一遍");
            return;
        }
        Logs.i("章节顺序策略=" + order.strategy + " (" + order.detail + ") 共 " + order.list.size() + " 章");
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        final TextView st = new TextView(act);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        st.setText("准备中…");
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(act, 18);
        box.setPadding(p, p, p, p);
        box.addView(st);
        box.addView(bar);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("保存《" + bookName + "》到本地")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("后台继续", null)
                .create();
        dlg.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String summary;
                try {
                    summary = Exporter.export(bookId, bookName, order, new Exporter.Prog() {
                        @Override
                        public void set(final int percent, final String text) {
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        bar.setProgress(percent);
                                        st.setText(text);
                                    } catch (Throwable ignore) {
                                    }
                                }
                            });
                        }
                    });
                } catch (Throwable t) {
                    Logs.e("导出异常", t);
                    summary = "导出失败: " + t;
                }
                final String s = summary;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            dlg.dismiss();
                        } catch (Throwable ignore) {
                        }
                        try {
                            new AlertDialog.Builder(act)
                                    .setTitle("保存结果")
                                    .setMessage(s)
                                    .setPositiveButton("好", null)
                                    .show();
                        } catch (Throwable t) {
                            Logs.e("弹结果框失败（Activity 可能已销毁）", t);
                        }
                        if (after != null) after.run();
                    }
                });
            }
        }, "fq-export").start();
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════

    public static View findByTexts(View v, String[] needles) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String s = t.toString().trim();
                for (String n : needles) {
                    if (s.equals(n)) return v;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByTexts(g.getChildAt(i), needles);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String shortText(View v) {
        if (v instanceof TextView) {
            CharSequence c = ((TextView) v).getText();
            return c == null ? "?" : c.toString();
        }
        return v.getClass().getSimpleName();
    }

    public static int dp(Context c, float v) {
        try {
            return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable t) {
            return (int) v;
        }
    }

    private static int[] screenSize(Context c) {
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) {
            return new int[]{1080, 2400};
        }
    }

    private static int statusBarHeight(Context c) {
        try {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignore) {
        }
        return dp(c, 28);
    }

    public static void toast(final Activity a, final String s) {
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(a, s, Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {
                }
            }
        });
    }
}
