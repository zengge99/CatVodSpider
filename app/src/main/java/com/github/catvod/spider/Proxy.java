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

public class Proxy extends Spider {

    private static class HttpDownloader extends PipedInputStream {

        public String contentType = "";
        public long contentLength = 0;
        public Headers header;
        Response response;
        boolean success;
        Future<ByteArrayInputStream> future;
        ExecutorService executorService;

        private HttpDownloader(String url) {
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

            this.executorService = Executors.newFixedThreadPool(1);
            this.future = this.executorService.submit(() -> {
                try {
                    Request request = new Request.Builder().url(url).addHeader("Accept-Encoding", "").build();
                    Response response = OkHttp.newCall(request);
                    
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;

                    while ((bytesRead = response.body().read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    return new ByteArrayInputStream(baos.toByteArray());
                } catch (Exception e) {
                    return null;
                }
            });
        }

        @Override
        public synchronized int read(byte[] buffer, int off, int len) throws IOException {
            ByteArrayInputStream is;
            try {
                is = this.future.get();
            } catch (Exception e) {}
            this.executorService.shutdown();
            return is.read(buffer, off, len);
        }

        @Override
        public void close() throws IOException {
            super.close();
        }
    }

    private static int port = -1;

    public static Object[] proxy(Map<String, String> params) throws Exception {
        switch (params.get("do")) {
            case "gen":
                return genProxy1("https://pan.1996999.xyz/tmp.txt");
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

    public static Object[] genProxy1(String url) throws Exception {
        HttpDownloader httpDownloader = new HttpDownloader(url);
        NanoHTTPD.Response resp = newFixedLengthResponse(Status.PARTIAL_CONTENT, httpDownloader.contentType, httpDownloader, httpDownloader.contentLength);
        for (String key : httpDownloader.header.names()) resp.addHeader(key, httpDownloader.header.get(key));
        return new Object[]{resp};
    }


    public static Object[] genProxy(String url) throws Exception {
        Response response = OkHttp.newCall(url);
        String contentType = response.headers().get("Content-Type");
        String hContentLength = response.headers().get("Content-Length");
        String contentDisposition = response.headers().get("Content-Disposition");
        long contentLength = hContentLength != null ? Long.parseLong(hContentLength) : 0;
        NanoHTTPD.Response resp = newFixedLengthResponse(Status.PARTIAL_CONTENT, contentType, response.body().byteStream(), contentLength);
        for (String key : response.headers().names()) resp.addHeader(key, response.headers().get(key));
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
