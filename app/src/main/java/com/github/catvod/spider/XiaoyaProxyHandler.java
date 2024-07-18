package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import okhttp3.Response;
import static com.github.catvod.spider.NanoHTTPD.Response.Status;
import static com.github.catvod.spider.NanoHTTPD.newFixedLengthResponse;
import java.io.IOException;
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
import java.net.URL;
import okhttp3.OkHttpClient;
import org.json.JSONObject;
import java.util.HashMap;
import okhttp3.Call;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable; 
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class XiaoyaProxyHandler {

    private static class BidirectInputStream extends InputStream {
    private BlockingQueue<byte[]> buffer;
    volatile private boolean endOfStream;
    private byte[] remainingData;

    public BidirectInputStream(int bufferSize) {
        buffer = new ArrayBlockingQueue<>(bufferSize);
        endOfStream = false;
    }

    @Override
    public synchronized int read(byte[] buffer, int off, int len) throws IOException {
        if (endOfStream && this.buffer.isEmpty() && remainingData == null) {
            return -1; 
        }

        int totalBytesRead = 0; 
        int bytesRead; 
        if (remainingData != null) {
            bytesRead = Math.min(len, remainingData.length); 
            System.arraycopy(remainingData, 0, buffer, off, bytesRead); 
            totalBytesRead += bytesRead; 
            if (bytesRead < remainingData.length) {
                byte[] newRemainingData = new byte[remainingData.length - bytesRead];
                System.arraycopy(remainingData, bytesRead, newRemainingData, 0, remainingData.length - bytesRead);
                remainingData = newRemainingData;
            } else {
                remainingData = null;
            }
            off += bytesRead; 
        }

        while (totalBytesRead < len) {
            byte[] data = this.buffer.poll(); 
            if (data == null) {
                return totalBytesRead;
            }
            int remainingBytes = len - totalBytesRead; 
            bytesRead = Math.min(remainingBytes, data.length);
            System.arraycopy(data, 0, buffer, off, bytesRead);
            totalBytesRead += bytesRead;
            if (bytesRead < data.length) {
                remainingData = new byte[data.length - bytesRead];
                System.arraycopy(data, bytesRead, remainingData, 0, data.length - bytesRead);
                break;
            }
            off += bytesRead; 
        }

        return totalBytesRead; 
    }
    
    @Override
    public int read() throws IOException {
        throw new IOException("read method not implemented");
        return -1;
    }

    @Override
    public void close() throws IOException {
        endOfStream = true;
    }

    public void write(byte[] data, int off, int len) throws IOException {
        try {
            byte[] newData = new byte[len];
            System.arraycopy(data, off, newData, 0, len);
            buffer.put(newData);
        } catch (InterruptedException e) {
            throw new IOException("Write interrupted", e);
        }
    }
}
    
    private static class QurakLinkCacheInfo {
        long cacheTime;
        String cacheLink;
        String cookie;
    }

    private static class QurakLinkCacheManager {
        static HashMap<String, QurakLinkCacheInfo> map = new HashMap<>();
        public static QurakLinkCacheInfo getLinkCache(String url) {
            QurakLinkCacheInfo cacheInfo = map.get(url);
            if (cacheInfo != null) {
                long currentTime = System.currentTimeMillis();
                long cacheTime = cacheInfo.cacheTime;
                if (currentTime - cacheTime <= 10 * 60 * 1000) {
                    return cacheInfo;
                } else {
                    map.remove(url);
                    return null;
                }
            } else {
                return null;
            }
        }

        public static void putLinkCache(String url, QurakLinkCacheInfo value) {
            long currentTime = System.currentTimeMillis();
            value.cacheTime = currentTime;
            map.put(url, value);
            map.entrySet().removeIf(entry -> currentTime - entry.getValue().cacheTime > 10 * 60 * 1000);
        }
    }

    private static class HttpDownloader extends InputStream {
        public String contentType = "";
        public long contentLength = -1;
        long contentEnd;
        public Headers header;
        public int statusCode = 200;
        String directUrl = null;
        volatile static int curConnId = 0;
        volatile boolean closed = false;
        int connId;
        InputStream is = null;
        Queue<Callable<InputStream>> callableQueue = new LinkedList<>();
        Queue<Future<InputStream>> futureQueue = new LinkedList<>();
        static HashMap<String, HttpDownloader> downloaderMap = new HashMap<>();
        ExecutorService executorService = Executors.newFixedThreadPool(128);
        boolean supportRange = true;
        int blockSize = 10 * 1024 * 1024; //默认10MB
        int threadNum = 2; //默认2线程
        String cookie = null;
        String referer = null;
        int blockCounter = 0;
        OkHttpClient downloadClient = OkHttp.client().newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).writeTimeout(3, TimeUnit.SECONDS).build();
        
        private HttpDownloader(Map<String, String> params) {
            
            Thread currentThread = Thread.currentThread();
            currentThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    Logger.log("未捕获的异常1：" + e.getMessage(), true);
                }
            });

            try{
                connId = curConnId++;
                String url = params.get("url");
                //播放初始阶段，播放器会多次请求不同的range，快速关闭同一个链接的已有的下载器
                downloaderMap.entrySet().removeIf(entry -> entry.getValue().closed);
                HttpDownloader cacheDownloader = downloaderMap.get(url);
                if (cacheDownloader != null) {
                    cacheDownloader.close();
                }
                downloaderMap.put(url, this);

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
                String range = "";
                if (params.get("range") != null) {
                    range = params.get("range");
                }
                Logger.log(connId + "[HttpDownloader]：播放器携带的下载链接：" + url + "播放器指定的range：" + range);
                this.getHeader(url, headers);
                this.createDownloadTask(directUrl, headers);
            } catch (Exception e) {
                Logger.log(connId + "[HttpDownloader]：发生错误：" + e.getMessage());
            }
        }

        private void createDownloadTask(String url, Map<String, String> headers) {
            Logger.log(connId + "[createDownloadTask]：下载链接：" + url);
            Request.Builder requestBuilder = new Request.Builder().url(url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
            Request request = requestBuilder.build();
            //不支持断点续传，单线程下载
            if(!this.supportRange) {
                Logger.log(connId + "[createDownloadTask]：单线程模式下载，配置线程数：" + threadNum);
                Callable<InputStream> callable = () -> {
                    return downloadTask(url, headers, "");
                };
                callableQueue.add(callable);
                return;
            }
            
            //多线程下载
            long start = 0; 
            long end = this.contentEnd ;
            String range = request.headers().get("Range");
            range = range == null ? "0-" : range;
            range = range + "-" + this.contentEnd;
            range = range.replace("--", "-");
            String pattern = "bytes=(\\d+)-(\\d+)";
            Pattern r = Pattern.compile(pattern);
            Matcher m = r.matcher(range);
            if (m.find()) {
                String startString = m.group(1); 
                String endString = m.group(2);
                start = Long.parseLong(startString); 
                end = Long.parseLong(endString);
            }
            Logger.log(connId + "[createDownloadTask]：多线程模式下载，配置线程数：" + threadNum + "播放器指定的范围：" + range);

            while (start <= end) {
                long curEnd = start + blockSize - 1;
                curEnd = curEnd > end ? end : curEnd;
                String ra = "bytes=" + start + "-" + curEnd;
                Callable<InputStream> callable = () -> {
                    return downloadTask(url, headers, ra);
                };
                callableQueue.add(callable);
                start = curEnd + 1;
            }
        }

        private InputStream downloadTask(String url, Map<String, String> headers, String range) {
            Thread currentThread = Thread.currentThread();
            currentThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    Logger.log("未捕获的异常2：" + e.getMessage(), true);
                }
            });
            return _downloadTask(url,headers,range);
        }

        private InputStream _downloadTask(String url, Map<String, String> headers, String range) {
            try {
                if(closed){
                    return null;
                }
                Logger.log(connId + "[_downloadTask]：下载分片：" + range);
                Request.Builder requestBuilder = new Request.Builder().url(url);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
                if (!range.isEmpty()) {
                    requestBuilder.removeHeader("Range").addHeader("Range", range);
                }
                if (cookie != null) {
                    requestBuilder.removeHeader("Cookie").addHeader("Cookie", cookie);
                }
                if (referer != null) {
                    requestBuilder.removeHeader("Referer").addHeader("Referer", referer);
                }
                Request request = requestBuilder.build();
                int retryCount = 0;
                int maxRetry = 5;
                Response response = null;
                Call call = null;
                // 单线程模式，让播放器拉取数据
                if (range.isEmpty()) {
                    call = downloadClient.newCall(request);
                    response = call.execute();
                    return response.body().byteStream();
                }

                //多线程模式，启新线程拉取数据
                BidirectInputStream inputStream = new BidirectInputStream(blockSize);
                Thread thread = new Thread(() -> {
                    pullDataFromNet(request, inputStream, range);
                });
                thread.start();
                return inputStream;
            } catch (Exception e) {
                Logger.log(connId + "[_downloadTask]：连接异常终止，下载分片：" + range);
            }
            return null;
        }

        private void pullDataFromNet(Request request, BidirectInputStream inputStream, String range)
        {
            int retryCount = 0;
            int maxRetry = 5;
            int bytesRead = 0;
            byte[] downloadbBuffer = new byte[1024];
            Response response = null;
            Call call = null;
            boolean clean = true;
            
            while (retryCount < maxRetry) {
                try {
                    call = downloadClient.newCall(request);
                    response = call.execute();
                    while (!closed && (bytesRead = response.body().byteStream().read(downloadbBuffer)) != -1) {
                        inputStream.write(downloadbBuffer, 0, bytesRead);
                        clean = false;
                    }
                    Logger.log(connId + "[pullDataFromNet]：分片完成：" + range);
                    break;
                } catch (Exception e) {
                    retryCount++;
                    if (retryCount == maxRetry || closed || !clean) {
                        Logger.log(connId + "[pullDataFromNet]：连接异常终止，下载分片：" + range);
                        break;
                    }
                } finally {
                    if(response != null){
                        call.cancel();
                        response.close();
                    }
                }
            }
            try {
                inputStream.close();
            } catch (Exception e) {}
        }
        
        private void getHeader(String url, Map<String, String> headers) {
            getQuarkLink(url, headers);
            int count = 0;
            while (statusCode == 302 && count < 3){
                _getHeader(directUrl, headers);
                count++;
            }
            Headers originalHeaders = this.header;
            Headers.Builder headersBuilder = new Headers.Builder();
            for (int i = 0; i < originalHeaders.size(); i++) {
                String name = originalHeaders.name(i);
                String value = originalHeaders.value(i);
                if(!name.equals("Content-Length") && !name.equals("Content-Type")){
                    headersBuilder.add(name, value);
                }
            }
            this.header = headersBuilder.build();
        }

        private void getQuarkLink(String url, Map<String, String> headers) {
            try {
                //先假装自己重定向到自己
                statusCode = 302;
                directUrl = url;
                if (!(url.contains("/d/") && url.contains("夸克"))) {
                    return;
                }
                Logger.log(connId + "[getQuarkLink]播放器连接请求：" + url);
                
                QurakLinkCacheInfo info = QurakLinkCacheManager.getLinkCache(url);
                if(info != null){
                    cookie = info.cookie;
                    directUrl = info.cacheLink;
                    referer = "https://pan.quark.cn";
                    Logger.log(connId + "[getQuarkLink]获取到夸克下载直链缓存：" + directUrl);
                    return;
                }
                
                URL urlObj = new URL(url);
                String host = urlObj.getProtocol() + "://" + urlObj.getHost();
                int port = urlObj.getPort();
                if (port != -1) {
                    host = host + ":" + port;
                }
                String path = "";
                int index = url.indexOf("/d/");
                if (index != -1) {
                    path = "/" + url.substring(index + 3);
                } 
                String alistApi = host + "/api/fs/other";
                Map<String, String> params = new HashMap<>();
                params.put("path", path);
                params.put("method", "video_download");
                String rsp = OkHttp.post(alistApi, params);
                JSONObject object = new JSONObject(rsp);
                String data = object.getString("data");
                object = new JSONObject(data);
                cookie = object.getString("cookie");
                String location = object.getString("download_link");
                location = unescapeUnicode(location);
                if(location != null && cookie != null && !location.isEmpty() && !cookie.isEmpty()){
                    QurakLinkCacheInfo var = new QurakLinkCacheInfo();
                    var.cacheLink = location;
                    var.cookie = cookie;
                    QurakLinkCacheManager.putLinkCache(url, var);
                }
                referer = "https://pan.quark.cn";
                Logger.log(connId + "[getQuarkLink]获取到夸克下载直链：" + location);
                directUrl = location == null ? url : location;
            } catch (Exception e) {
                Logger.log(connId + "[getQuarkLink]获取夸克发生错误：" + e.getMessage());
            }
        }

        private String unescapeUnicode(String unicodeString) {
            Pattern pattern = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
            Matcher matcher = pattern.matcher(unicodeString);
            
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                char ch = (char) Integer.parseInt(matcher.group(1), 16);
                matcher.appendReplacement(sb, String.valueOf(ch));
            }
            matcher.appendTail(sb);
            
            return sb.toString();
        }
        
        private void _getHeader(String url, Map<String, String> headers) {
            statusCode = 200;
            this.supportRange = true;
            String range = "";
            Response response = null;
            Call call = null;
            String hContentLength = "";
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }
                
                if (cookie != null) {
                    requestBuilder.removeHeader("Cookie").addHeader("Cookie", cookie);
                }
                if (referer != null) {
                    requestBuilder.removeHeader("Referer").addHeader("Referer", referer);
                }
                Request request = requestBuilder.build();
                call = OkHttp.client().newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request);
                response = call.execute();
                this.header = response.headers();
                statusCode = response.code();
                this.contentType = this.header.get("Content-Type");
                hContentLength = this.header.get("Content-Length");
                String location = this.header.get("Location");
                if(location != null && statusCode == 302){
                    directUrl = location;
                    URL urlObj = new URL(url);
                    String host = urlObj.getProtocol() + "://" + urlObj.getHost();
                    int port = urlObj.getPort();
                    if (port != -1) {
                        host = host + ":" + port;
                    }
                    if(!directUrl.startsWith("http")){
                        directUrl = host + directUrl;
                    }
                } else {
                    directUrl = url;
                }
                this.contentLength = hContentLength != null ? Long.parseLong(hContentLength) : -1;
                this.contentEnd = this.contentLength - 1;
                String hContentEnd = this.header.get("Content-Range");
                if (hContentEnd != null) {
                    hContentEnd = hContentEnd.split("/")[1];
                    this.contentEnd = Long.parseLong(hContentEnd) - 1;
                }
                if (this.header.get("Accept-Ranges") == null || !this.header.get("Accept-Ranges").toLowerCase().equals("bytes")) {
                    this.supportRange = false;
                }
            } catch (Exception e) {
                Logger.log(connId + "[_getHeader]：发生错误：" + e.getMessage());
                this.supportRange = false;
                return;
            } finally {
                if(response!=null){
                    call.cancel();
                    response.close();
                }
            }
        }

        private void runTask(int num) {
            while(num-- > 0 && callableQueue.size() > 0) {
                Future<InputStream> future = this.executorService.submit(callableQueue.remove());
                this.futureQueue.add(future);
            }
        }

        @Override
        public synchronized int read(byte[] buffer, int off, int len) throws IOException {
            try {
                if (closed) {
                    return -1;
                }
                
                if (this.is == null ) {
                    Future<InputStream> future = this.executorService.submit(callableQueue.remove());
                    this.is = future.get();
                    Logger.log(connId + "[read]：读取数据块：" + blockCounter);
                    blockCounter++;
                }
                int ol = this.is.read(buffer, off, len);
                if ( ol == -1 ) {
                    if (blockCounter == 1) {
                        runTask(threadNum);
                    }
                    this.is = this.futureQueue.remove().get();
                    runTask(1);
                    Logger.log(connId + "[read]：读取数据块：" + blockCounter);
                    blockCounter++;
                    return this.is.read(buffer, off, len);
                } 
                return ol;
            } catch (Exception e) {
                Logger.log(connId + "[read]：发生错误：" + e.getMessage());
                return -1;
            }
        }
        
        @Override
        public int read() throws IOException {
            throw new IOException("read method not implemented");
            return -1;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            Logger.log("播放器主动关闭数据流");
            closed = true;
            if(this.executorService != null) {
                this.executorService.shutdownNow();
            }
            futureQueue.clear();
            callableQueue.clear();
        }
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        switch (params.get("do")) {
            case "dbg":
                Logger.dbg = true;
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "genck":
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "gen":
                return genProxy(params);
            default:
                return null;
        }
    }

    private synchronized static Object[] genProxy(Map<String, String> params) throws Exception {
        HttpDownloader httpDownloader = new HttpDownloader(params);
        NanoHTTPD.Response.IStatus status = NanoHTTPD.Response.Status.lookup(httpDownloader.statusCode);
        NanoHTTPD.Response resp = newFixedLengthResponse(status, httpDownloader.contentType, httpDownloader, httpDownloader.contentLength);
        for (String key : httpDownloader.header.names()) resp.addHeader(key, httpDownloader.header.get(key));
        return new Object[]{resp};
    }
}
