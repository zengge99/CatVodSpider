package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Response;
import static fi.iki.elonen.NanoHTTPD.Response.Status;
//import static fi.iki.elonen.NanoHTTPD.Response;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;
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

public class Proxy extends Spider {

    private static class HttpDownloader extends PipedInputStream {

        public String contentType = "";
        public long contentLength = 0;
        public Headers header;
        Response response;
        boolean success;
        int waiting = 0;
        ByteArrayInputStream is = null;
        Queue<Future<ByteArrayInputStream>> futureQueue;
        ExecutorService executorService;

        private HttpDownloader(String url) {
            this.futureQueue = new LinkedList<>();
            this.success = true;
            try {
                Request request = new Request.Builder().head().url(url).addHeader("Accept-Encoding", "").build();
                this.header = OkHttp.newCall(request).headers();
                this.contentType = this.header.get("Content-Type");
                String hContentLength = this.header.get("Content-Length");
                this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : 0;
            } catch (Exception e) {
                this.success = false;
                return;
            }

            this.executorService = Executors.newFixedThreadPool(5);
            for (int i = 0; i < 10; i++) {
                final int index = i; 
                Future<ByteArrayInputStream> future = this.executorService.submit(() -> {
                    try {
                        Request request = new Request.Builder().url(url).addHeader("Accept-Encoding", "").addHeader("Range","bytes=" + (index*1024*1024) + "-" + ((index+1)*1024*1024 - 1)).build();
                        Response response = OkHttp.newCall(request);
                    
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int bytesRead;

                        while ((bytesRead = response.body().byteStream().read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        this.waiting++;
                        while(this.waiting>5){
                            Thread.sleep(100);
                        }
                        return new ByteArrayInputStream(baos.toByteArray());
                    } catch (Exception e) {
                        return null;
                    }
                });
                this.futureQueue.add(future);
            }
        }

        @Override
        public synchronized int read(byte[] buffer, int off, int len) throws IOException {
            try {
                if (this.is == null ) {
                    if(this.futureQueue.isEmpty()){
                        return -1;
                    }
                    this.is = this.futureQueue.remove().get();
                    this.waiting--;
                }
                int ol = this.is.read(buffer, off, len);
                if ( ol == -1 )
                {
                    this.is = null;
                    return 0;
                }
                return ol;
            } catch (Exception e) {
                this.is = null;
                return 0;
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            this.executorService.shutdown();
        }
    }

    private static int port = -1;

    public static Object[] proxy(Map<String, String> params) throws Exception {
        switch (params.get("do")) {
            case "gen":
                return genProxy(params.get("url"));
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

    public static Object[] genProxy(String url) throws Exception {
        HttpDownloader httpDownloader = new HttpDownloader(url);
        NanoHTTPD.Response resp = newFixedLengthResponse(Status.PARTIAL_CONTENT, httpDownloader.contentType, httpDownloader, httpDownloader.contentLength);
        for (String key : httpDownloader.header.names()) resp.addHeader(key, httpDownloader.header.get(key));
        return new Object[]{resp};
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
