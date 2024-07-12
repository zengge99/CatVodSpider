package com.github.catvod.spider;

import java.io.InputStream;
import java.util.Map;
//import fi.iki.elonen.NanoHTTPD;

public class XiaoyaProxyServer extends NanoHTTPD {

    private static class Loader {
        static volatile XiaoyaProxyServer INSTANCE = new XiaoyaProxyServer(9988);
    }
    
    public XiaoyaProxyServer(int port) {
        super(port);
    }

    public static XiaoyaProxyServer get() {
        return Loader.INSTANCE;
    }

    public static Response success() {
        return success("OK");
    }

    public static Response success(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text);
    }

    public static Response error(String text) {
        return error(Response.Status.INTERNAL_ERROR, text);
    }

    public static Response error(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, text);
    }

    public static Response redirect(String url, Map<String, String> headers) {
        Response response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "");
        for (Map.Entry<String, String> entry : headers.entrySet()) response.addHeader(entry.getKey(), entry.getValue());
        response.addHeader("Location", url);
        return response;
    }

    @Override
    public Response serve(IHTTPSession session) {
        return proxy(session);
    }

    private Response proxy(IHTTPSession session) {
        try {
            Map<String, String> params = session.getParms();
            params.putAll(session.getHeaders());
            Object[] rs = XiaoyaProxyHandler.proxy(params);
            return rs[0] instanceof Response ? (Response) rs[0] : newChunkedResponse(Response.Status.lookup((Integer) rs[0]), (String) rs[1], (InputStream) rs[2]);
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    
    @Override
    public void start() throws IOException {
        if(!super.isAlive()) {
            super.start();
        }
    }
    
    @Override
    public void stop() {
        super.stop();
    }
}
