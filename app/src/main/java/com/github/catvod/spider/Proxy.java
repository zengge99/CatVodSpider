package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Response;
import static fi.iki.elonen.NanoHTTPD.Response.Status;
import static fi.iki.elonen.NanoHTTPD.Response;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;

public class Proxy extends Spider {

    private static class ChunkedInputStream extends PipedInputStream {

        int chunk = 0;

        String[] chunks;

        private ChunkedInputStream(String[] chunks) {
            this.chunks = chunks;
        }

        @Override
        public synchronized int read(byte[] buffer, int off, int len) throws IOException {
            for (int i = 0; i < this.chunks[this.chunk].length(); ++i) {
                buffer[i] = (byte) this.chunks[this.chunk].charAt(i);
            }
            return this.chunks[this.chunk++].length();
        }
    }

    private static int port = -1;

    public static Object[] proxy(Map<String, String> params) throws Exception {
        switch (params.get("do")) {
            case "gen":
                return genProxy("https://xiaoya.1996999.xyz/my_fan.json");
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
