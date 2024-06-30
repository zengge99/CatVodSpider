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
//import java.net.URLDecoder;
import com.github.catvod.utils.Notify;
import java.io.PrintStream;
import java.io.InputStream;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.net.URL;

public class Proxy extends Spider {
    private static class HttpDownloader extends PipedInputStream {
        public String contentType = "";
        public long contentLength = -1;
        public Headers header;
        public int statusCode = 200;
        String newUrl = null;
        int waiting = 0;
        InputStream is = null;
        Queue<Future<InputStream>> futureQueue;
        ExecutorService executorService;
        boolean supportRange = true;
        int blockSize = 10 * 1024 * 1024; //默认1MB
        int threadNum = 2; //默认2线程
        String cookie = "";
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        
        private HttpDownloader(Map<String, String> params) {
            try{
                if(params.get("thread") != null){
                    threadNum = Integer.parseInt(params.get("thread"));
                }
                if(params.get("size") != null){
                    blockSize = Integer.parseInt(params.get("size"));
                }
                if(params.get("cookie") != null){
                    //如果发送是EncodeURIComponet过的，get会自动转码，不需要手工转，坑啊
                    cookie = params.get("cookie");
                }
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                List<String> keys = Arrays.asList("referer", "icy-metadata", "range", "connection", "accept-encoding", "user-agent", "cookie");
                for (String key : params.keySet()) if (keys.contains(key)) headers.put(key, params.get(key));
                String url = params.get("url");
                this.getHeader(url, headers);
                this.createDownloadTask(url, headers);
            } catch (Exception e) {
                //不需要做什么
            }
        }

        void incrementWaiting() {
            lock.writeLock().lock();
            try {
                waiting++;
            } finally {
                lock.writeLock().unlock();
            }
        }

        void decrementWaiting() {
            lock.writeLock().lock();
            try {
                waiting--;
            } finally {
                lock.writeLock().unlock();
            }
        }

        int readWaiting() {
            lock.readLock().lock();
            try {
                return waiting;
            } finally {
                lock.readLock().unlock();
            }
        }

        private void createDownloadTask(String url, Map<String, String> headers) {
            Request.Builder requestBuilder = new Request.Builder().url(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
            Request request = requestBuilder.build();
            this.futureQueue = new LinkedList<>();
            this.executorService = Executors.newFixedThreadPool(threadNum);
            //不支持断点续传，单线程下载
            if(!this.supportRange) {
                Future<InputStream> future = this.executorService.submit(() -> {
                    return downloadTask(url, headers, "");
                });
                this.futureQueue.add(future);
                return;
            }
            
            //多线程下载
            long start = 0; 
            long end = this.contentLength - 1;
            String range = request.headers().get("Range");
            range = range == null ? "" : range;
            String pattern = "bytes=(\\d+)-(\\d+)";
            Pattern r = Pattern.compile(pattern);
            Matcher m = r.matcher(range);
            if (m.find()) {
                String startString = m.group(1); 
                String endString = m.group(2);
                start = Long.parseLong(startString); 
                end = Long.parseLong(endString);
            }

            while (start <= end) {
                long curEnd = start + blockSize - 1;
                curEnd = curEnd > end ? end : curEnd;
                String ra = "bytes=" + start + "-" + curEnd;
                Future<InputStream> future = this.executorService.submit(() -> {
                    return downloadTask(url, headers, ra);
                });
                this.futureQueue.add(future);
                start = curEnd + 1;
            }
        }

        private InputStream downloadTask(String url, Map<String, String> headers, String range) {
            try{
                url = newUrl != null ? newUrl : url;
                while(readWaiting() > threadNum){
                try{
                    Thread.sleep(100);
                    } catch (Exception e) {}
                }
                InputStream in = _downloadTask(url,headers,range);
                return in;   
            } finally {
                incrementWaiting();
            }
        }

        private InputStream _downloadTask(String url, Map<String, String> headers, String range) {
            Request.Builder requestBuilder = new Request.Builder().url(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
            if (!range.isEmpty()) {
                requestBuilder.removeHeader("Range").addHeader("Range", range);
            }
            if (!cookie.isEmpty()) {
                requestBuilder.removeHeader("Cookie").addHeader("Cookie", cookie);
            }
            Request request = requestBuilder.build();
            int retryCount = 0;
            int maxRetry = 5;
            byte[] downloadbBuffer = new byte[1024 * 1024];
            while (retryCount < maxRetry) {
                try {
                    Response response = OkHttp.newCall(request);
                    // 单线程模式，重新获取更准确的响应头。通常发生于服务器不支持HEAD方法，通过HEAD获取的头无效才会用单线程。
                    if (range.isEmpty()) {
                        statusCode = response.code();
                        this.header = response.headers();
                        this.contentType = this.header.get("Content-Type");
                        String hContentLength = this.header.get("Content-Length");
                        this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : -1;
                        return response.body().byteStream();
                    }
                        
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int bytesRead;
                    while ((bytesRead = response.body().byteStream().read(downloadbBuffer)) != -1) {
                        baos.write(downloadbBuffer, 0, bytesRead);
                    }
                    return new ByteArrayInputStream(baos.toByteArray());
                } catch (Exception e) {
                    retryCount++;
                    if (retryCount == maxRetry) {
                        try{
                            this.close();
                        } catch ( Exception err ) {}
                        return null;
                    }
                }
            }
            //其实不可能走到这里， 避免编译报错。
            return null;
        }
        
        private void getHeader(String url, Map<String, String> headers) {
            if (newUrl !=null){
                return;
            }
            _getHeader(url, headers);
            if (newUrl != null){
                _getHeader(newUrl, headers);
            }
        }

        private void getQuarkLink(String url, Map<String, String> headers) {
            try {
                if (!(url.contains("/d/") && url.contains("夸克"))) {
                    return;
                }
                URL urlObj = new URL(url);
                String host = urlObj.getProtocol() + "://" + urlObj.getHost();
                String path = urlObj.getPath();
                String alistApi = host + "/api/fs/other";
            } catch (Exception e) {}
        }
        
        private void _getHeader(String url, Map<String, String> headers) {
            String range = "";
            String hContentLength = "";
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url).head();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
                //requestBuilder.removeHeader("Accept-Encoding").addHeader("Accept-Encoding", "");
                if (!cookie.isEmpty()) {
                    requestBuilder.removeHeader("Cookie").addHeader("Cookie", cookie);
                }
                Request request = requestBuilder.build();

                Response response = OkHttp.newCall(request);
                this.header = response.headers();
                statusCode = response.code();
                this.contentType = this.header.get("Content-Type");
                hContentLength = this.header.get("Content-Length");
                String location = this.header.get("Location");
                if(location != null && statusCode == 302){
                    newUrl = location;
                }
                this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : -1;
                if (!this.header.get("Accept-Ranges").toLowerCase().equals("bytes")) {
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
                //流如果关闭了会抛异常
                this.available();
                if (this.is == null ) {
                    this.is = this.futureQueue.remove().get();
                    decrementWaiting();
                }
                int ol = this.is.read(buffer, off, len);
                //因为是预先下载到内存块，因此0也是读完了
                if ( ol == -1 || ol == 0 )
                {
                    this.is = this.futureQueue.remove().get();
                    decrementWaiting();
                    return this.is.read(buffer, off, len);
                } 
                return ol;
            } catch (Exception e) {
                this.is = null;
                return -1;
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
            case "genck":
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "gen":
                return genProxy(params);
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

    public static Object[] genProxy(Map<String, String> params) throws Exception {
        HttpDownloader httpDownloader = new HttpDownloader(params);
        NanoHTTPD.Response.IStatus status = NanoHTTPD.Response.Status.lookup(httpDownloader.statusCode);
        NanoHTTPD.Response resp = newFixedLengthResponse(status, httpDownloader.contentType, httpDownloader, httpDownloader.contentLength);
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
