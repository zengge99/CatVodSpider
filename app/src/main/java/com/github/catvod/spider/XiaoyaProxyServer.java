package com.github.catvod.spider;

import android.util.Base64;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

public class XiaoyaProxyServer extends NanoHTTPD {

    private List<Process> process;

    public XiaoyaProxyServer(int port) {
        super(port);
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
        response.addHeader(HttpHeaders.LOCATION, url);
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
            Object[] rs = Proxy.proxy(params);
            return rs[0] instanceof Response ? (Response) rs[0] : newChunkedResponse(Response.Status.lookup((Integer) rs[0]), (String) rs[1], (InputStream) rs[2]);
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    @Override
    public void stop() {
        super.stop();
        Go.stop();
    }
}
