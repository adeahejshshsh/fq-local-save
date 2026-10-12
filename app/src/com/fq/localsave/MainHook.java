package com.fq.localsave;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Environment;


import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 番茄本地下载（com.dragon.read）LSPosed 模块 —— 入口。
 *
 * 一句话原理：模块不实现任何解密算法，它跑在番茄进程里，
 * 复用宿主自己的 native 解密例程拿到明文 XHTML，然后把整本（含插图）
 * 落盘到 /sdcard/Download/番茄本地/<书名>/<书名>.txt。
 *
 * 落点与流程见 Hooks / Exporter / Ui 三个类的头注释。
 */
public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        try {
            XposedBridge.log(Logs.TAG + " load: " + lp.packageName + "/" + lp.processName);
        } catch (Throwable ignore) {
        }
        if (!Store.PKG.equals(lp.packageName)) return;

        final ClassLoader cl = lp.classLoader;
        Store.pool();          // 提前把守护线程池建好

        try {
            Store.setApp(android.app.AndroidAppHelper.currentApplication());
        } catch (Throwable ignore) {
        }

        // Application.attach 一跑就有上下文，此时才能建 Dex 扫描 / 落盘
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Store.setApp((Context) p.thisObject);
                                boot(cl);
                            } catch (Throwable t) {
                                Logs.e("Application.attach", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Logs.e("hook Application.attach 失败", t);
        }

        hookActivity(cl);
        boot(cl);
    }

    private static volatile boolean booted = false;

    private static void boot(final ClassLoader cl) {
        if (booted) return;
        if (Store.app() == null) return;
        synchronized (MainHook.class) {
            if (booted) return;
            booted = true;
        }
        Store.pool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Logs.reset();
                    Logs.i("=== 番茄本地下载 v" + Store.VERSION + " 启动 ===");
                    Logs.i("宿主 pkg=" + Store.app().getPackageName()
                            + " api=" + Build.VERSION.SDK_INT
                            + " 外部存储管理=" + Environment.isExternalStorageManager());
                    Logs.i("宿主 dex: " + Dex.dexSummary());
                } catch (Throwable t) {
                    Logs.e("启动日志", t);
                }
                try {
                    Hooks.install(cl);
                    Logs.i("hook 安装完成");
                } catch (Throwable t) {
                    Logs.e("Hooks.install", t);
                }
                Logs.flush(true);
            }
        });
    }

    private static void hookActivity(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object o = p.thisObject;
                        if (!(o instanceof Activity)) return;
                        Activity a = (Activity) o;
                        Store.setCurrent(a);
                        Ui.attach(a);
                    } catch (Throwable t) {
                        Logs.e("Activity.onResume", t);
                    }
                }
            });
            Logs.i("hook Activity.onResume OK");
        } catch (Throwable t) {
            Logs.e("hook Activity.onResume 失败", t);
        }
    }
}
