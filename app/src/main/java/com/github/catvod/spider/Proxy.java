package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import android.content.Context;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import okhttp3.Response;
import static com.github.catvod.spider.NanoHTTPD.Response.Status;
import static com.github.catvod.spider.NanoHTTPD.newFixedLengthResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import okhttp3.Request;
import okhttp3.Headers;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Queue;
import java.util.LinkedList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import com.github.catvod.utils.Notify;
import java.io.PrintStream;
import java.io.InputStream;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.net.URL;
import okhttp3.OkHttpClient;
import org.json.JSONObject;
import java.util.HashMap;
import okhttp3.Call;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

public class Proxy extends Spider {
    private static int port = -1;

    public static Object[] proxy(Map<String, String> params) throws Exception {
        switch (params.get("do")) {
            case "ck":
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "ali":
                return Ali.proxy(params);
            case "bili":
                return Bili.proxy(params);
            case "webdav":
                return WebDAV.vod(params);
            default:
                return null;
        }
    }
    
    static void adjustPort() {
        if (Proxy.port > 0) return;
        int port = 9978;
        while (port < 10000) {
            String resp = OkHttp.string("http://127.0.0.1:" + port + "/proxy?do=ck", null);
            if (resp.equals("ok")) {
                SpiderDebug.log("Found local server port " + port);
                Proxy.port = port;
                break;
            }
            port++;
        }
    }

    public static int getPort() {
        adjustPort();
        return port;
    }

    public static String getUrl() {
        adjustPort();
        return "http://127.0.0.1:" + port + "/proxy";
    }
}
