package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

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
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class Proxy extends Spider {
    private static class HttpDownloader extends PipedInputStream {
        public String contentType = "";
        public long contentLength = 0;
        public Headers header;
        Response response;
        int waiting = 0;
        ByteArrayInputStream is = null;
        Queue<Future<ByteArrayInputStream>> futureQueue;
        ExecutorService executorService;
        boolean supportRange = true;

        private HttpDownloader(String url, Map<String, String> headers) {
            this.getHeader(url, headers);
            this.createDownloadTask(url, headers);
        }

        private void createDownloadTask(String url, Map<String, String> headers) {
            Request.Builder requestBuilder = new Request.Builder().url(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
            Request request = requestBuilder.build();
            String range = request.headers().get("Range");
            this.futureQueue = new LinkedList<>();
            this.executorService = Executors.newFixedThreadPool(5);
            //不支持断点续传，单线程下载
            if(!this.supportRange || range == "") {
                Future<ByteArrayInputStream> future = this.executorService.submit(() -> {
                    return downloadTask(url, headers, "");
                });
                this.futureQueue.add(future);
                return;
            }

            //多线程下载
            int startInt = 0; 
            int endInt = this.contentLength - 1;
            String pattern = "bytes=(\\d+)-(\\d+)";
            Pattern r = Pattern.compile(pattern);
            Matcher m = r.matcher(range);
            if (m.find()) {
                String start = m.group(1); 
                String end = m.group(2);
                startInt = Integer.parseInt(start); 
                endInt = Integer.parseInt(end);
            }
            
            for (int i = 0; i < 10; i++) {
                final int index = i; 
                Future<ByteArrayInputStream> future = this.executorService.submit(() -> {
                    return downloadTask(url, headers, "");
                });
                this.futureQueue.add(future);
            }
        }

        private ByteArrayInputStream downloadTask(String url, Map<String, String> headers, String range) {
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
                if(range != ""){
                    requestBuilder.addHeader("Range", range);
                }
                Request request = requestBuilder.build();
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
        }

        private void getHeader(String url, Map<String, String> headers) {
            String range = "";
            String hContentLength = "";
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
                Request request = requestBuilder.build();
                range = request.headers().get("Range");
                int index = range.indexOf("=");
                if (index != -1 && index < range.length() - 1) {
                    range = range.substring(index + 1);
                } else {
                    range = "";
                }
                requestBuilder.addHeader("Range", "bytes=0-1");
                request = requestBuilder.build();
                this.header = OkHttp.newCall(request).headers();
                this.contentType = this.header.get("Content-Type");
                hContentLength = this.header.get("Content-Length");
                this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : 0;
                if (this.contentLength != 2) {
                    this.supportRange = false;
                }
                hContentLength = this.header.get("Content-Range");
                String pattern = "\\d+";
                Pattern r = Pattern.compile(pattern);
                Matcher m = r.matcher(hContentLength);
                if (m.find()) {
                    hContentLength = m.group();
                } else {
                    hContentLength = "";
                }
                this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : 0;
            } catch (Exception e) {
                this.supportRange = false;
                return;
            }
            if (this.supportRange) {
                this.header = this.header.newBuilder().add("Content-Range", "bytes " + range + "/" + hContentLength).build();
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
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                List<String> keys = Arrays.asList("referer", "icy-metadata", "range", "connection", "accept-encoding", "user-agent");
                for (String key : params.keySet()) if (keys.contains(key)) headers.put(key, params.get(key));
                return genProxy(params.get("url"), headers);
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

    public static Object[] genProxy(String url, Map<String, String> headers) throws Exception {
        HttpDownloader httpDownloader = new HttpDownloader(url, headers);
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
