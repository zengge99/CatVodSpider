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
import java.net.URLDecoder;
import com.github.catvod.utils.Notify;
import java.io.PrintStream;

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
            if(!this.supportRange) {
                Future<ByteArrayInputStream> future = this.executorService.submit(() -> {
                    ByteArrayInputStream si = downloadTask(url, headers, "");
                    return si;
                });
                this.futureQueue.add(future);
                return;
            }
            
            //多线程下载
            long start = 0; 
            long end = this.contentLength - 1;
            String pattern = "bytes=(\\d+)-(\\d+)";
            Pattern r = Pattern.compile(pattern);
            Matcher m = r.matcher(range);
            if (m.find()) {
                String startString = m.group(1); 
                String endString = m.group(2);
                start = Long.parseLong(startString); 
                end = Long.parseLong(endString);
            }

            long blockSize = 1024 * 1024;
            while (start <= end) {
                long curEnd = start + blockSize - 1;
                curEnd = curEnd > end ? end : curEnd;
                String ra = "bytes=" + start + "-" + curEnd;
                Future<ByteArrayInputStream> future = this.executorService.submit(() -> {
                    return downloadTask(url, headers, ra);
                });
                this.futureQueue.add(future);
                start = curEnd + 1;
            }
        }

        private ByteArrayInputStream downloadTaskOld(String url, Map<String, String> headers, String range) {
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
                if(range != ""){
                    requestBuilder.removeHeader("Range").addHeader("Range", range);
                }
                //requestBuilder.removeHeader("Accept-Encoding").addHeader("Accept-Encoding", "");
                Request request = requestBuilder.build();
                Response response = OkHttp.newCall(request);

                //单线程模式，重新获取更准确的响应头。通常发生于服务器不支持HEAD方法，通过HEAD获取的头无效才会用单线程。
                if(range == ""){
                    this.header = response.headers();
                    this.contentType = this.header.get("Content-Type");
                    String hContentLength = this.header.get("Content-Length");
                    this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : 0;
                }
                    
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
                ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
                e.printStackTrace(new PrintStream(errorStream));
                return new ByteArrayInputStream(errorStream.toByteArray());
            }
        }

        private ByteArrayInputStream downloadTask(String url, Map<String, String> headers, String range) {
            int retryCount = 0;
            while (retryCount < 5) {
                try {
                    Request.Builder requestBuilder = new Request.Builder().url(url);
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        requestBuilder.addHeader(entry.getKey(), entry.getValue());
                    }
                    if (!range.isEmpty()) {
                        requestBuilder.removeHeader("Range").addHeader("Range", range);
                    }
                    Request request = requestBuilder.build();
                    Response response = OkHttp.newCall(request);
        
                    // 单线程模式，重新获取更准确的响应头。通常发生于服务器不支持HEAD方法，通过HEAD获取的头无效才会用单线程。
                    if (range.isEmpty()) {
                        this.header = response.headers();
                        this.contentType = this.header.get("Content-Type");
                        String hContentLength = this.header.get("Content-Length");
                        this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : 0;
                    }
                        
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
        
                    while ((bytesRead = response.body().byteStream().read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    this.waiting++;
                    return new ByteArrayInputStream(baos.toByteArray());
                } catch (Exception e) {
                    retryCount++;
                    if (retryCount == 5) {
                        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
                        e.printStackTrace(new PrintStream(errorStream));
                        this.waiting++;
                        this.close();
                        return new ByteArrayInputStream(errorStream.toByteArray());
                    }
                }
            }
        }

        private void getHeader(String url, Map<String, String> headers) {
            String range = "";
            String hContentLength = "";
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url).head();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
                //requestBuilder.removeHeader("Accept-Encoding").addHeader("Accept-Encoding", "");
                Request request = requestBuilder.build();
                
                this.header = OkHttp.newCall(request).headers();
                this.contentType = this.header.get("Content-Type");
                hContentLength = this.header.get("Content-Length");
                this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : 0;
                if (this.header.get("Accept-Ranges") != "bytes") {
                    this.supportRange = false;
                }
            } catch (Exception e) {
                this.supportRange = false;
                return;
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
                Notify.show("代理加载成功");
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                List<String> keys = Arrays.asList("referer", "icy-metadata", "range", "connection", "accept-encoding", "user-agent");
                for (String key : params.keySet()) if (keys.contains(key)) headers.put(key, params.get(key));
                String decodedUrl = URLDecoder.decode(params.get("url"), "UTF-8");
                return genProxy(decodedUrl, headers);
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
