package com.github.catvod.spider;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.crawler.SpiderDebug;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.InputStream;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;
import com.github.catvod.utils.ProxyVideo;

public class Init {

    private final ExecutorService executor;
    private final Handler handler;
    private Application app;

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }

    public static Init get() {
        return Loader.INSTANCE;
    }

    public Init() {
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(5);
    }

    public static Application context() {
        return get().app;
    }

    public static void init(Context context) {
        get().app = ((Application) context);

        /*
        Thread serverThread = new Thread(() -> {
            try {
                Logger.log("小雅代理启动", true);
                ProxyVideo.go();
            } catch (Exception e) {
                Logger.log("小雅代理启动失败：" + e.getMessage(), true);
                return;
            }
            Logger.log("小雅代理启动成功", true);
        });
        serverThread.start();
        */
        
        /*
        Runnable myTask = new Runnable() {
            @Override
            public void run() {
                XiaoyaProxyServer xiaoya = null;
                try {
                    Logger.log("小雅代理创建", true);
                    xiaoya = new XiaoyaProxyServer(9979);
                    Logger.log("小雅代理启动", true);
                    xiaoya.start();
                    Logger.log("小雅代理启动成功", true);
                } catch (Exception e) {
                    Logger.log("小雅代理启动失败：" + e.getMessage(), true);
                    xiaoya.stop();
                    xiaoya = null;
                    return;
                }
                Logger.log("小雅代理启动成功", true);
            }
        };
        execute(myTask);
        */

        /*
        Thread serverThread = new Thread(() -> {
            XiaoyaProxyServer xiaoya = null;
            try {
                Logger.log("小雅代理创建", true);
                xiaoya = new XiaoyaProxyServer(9979);
                Logger.log("小雅代理启动", true);
                xiaoya.start();
                Logger.log("小雅代理启动成功", true);
            } catch (Exception e) {
                Logger.log("小雅代理启动失败：" + e.getMessage(), true);
                xiaoya.stop();
                xiaoya = null;
                return;
            }
            Logger.log("小雅代理启动成功", true);
        });
        serverThread.start();
        */
        
    }

    public static void execute(Runnable runnable) {
        get().executor.execute(runnable);
    }

    public static void run(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void run(Runnable runnable, int delay) {
        get().handler.postDelayed(runnable, delay);
    }

    public static void checkPermission() {
        try {
            Activity activity = Init.getActivity();
            if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return;
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9999);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Activity getActivity() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
        activitiesField.setAccessible(true);
        Map<?, ?> activities = (Map<?, ?>) activitiesField.get(activityThread);
        for (Object activityRecord : activities.values()) {
            Class<?> activityRecordClass = activityRecord.getClass();
            Field pausedField = activityRecordClass.getDeclaredField("paused");
            pausedField.setAccessible(true);
            if (!pausedField.getBoolean(activityRecord)) {
                Field activityField = activityRecordClass.getDeclaredField("activity");
                activityField.setAccessible(true);
                Activity activity = (Activity) activityField.get(activityRecord);
                SpiderDebug.log(activity.getComponentName().getClassName());
                return activity;
            }
        }
        return null;
    }
}
