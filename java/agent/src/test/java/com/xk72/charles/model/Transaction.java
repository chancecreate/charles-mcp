package com.xk72.charles.model;

import com.xk72.proxy.Fields;
import com.xk72.proxy.ProxyEventStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.Date;
import java.util.zip.GZIPInputStream;

/** Test double exposing the getters the agent reads. Fields are public for test setup. */
public class Transaction extends ModelNode {
    public String method = "GET";
    public String protocol = "http";
    public String host = "example.test";
    public int port = -1;
    public String path = "/";
    public String query;
    public Fields requestHeader;
    public Fields responseHeader;
    public int responseStatus;
    public long requestSize;
    public long responseSize;
    public Date startTime = new Date(1_000);
    public Date endTime;
    public Exception exception;
    public ProxyEventStatus status = ProxyEventStatus.INIT;
    public byte[] requestBody;
    public byte[] responseBody;
    public String responseContentEncoding;
    public boolean throwOnGetHost;

    public String getMethod() { return method; }
    public String getProtocol() { return protocol; }
    public String getHost() {
        if (throwOnGetHost) {
            throw new IllegalStateException("boom");
        }
        return host;
    }
    public int getPort() { return port; }
    public String getPath() { return path; }
    public String getQuery() { return query; }
    public Fields getRequestHeader() { return requestHeader; }
    public Fields getResponseHeader() { return responseHeader; }
    public int getResponseStatus() { return responseStatus; }
    public long getRequestSize() { return requestSize; }
    public long getResponseSize() { return responseSize; }
    public Date getStartTime() { return startTime; }
    public Date getEndTime() { return endTime; }
    public Exception getException() { return exception; }
    public ProxyEventStatus getStatus() { return status; }
    public InetAddress getRemoteAddress() { return InetAddress.getLoopbackAddress(); }
    public String getRequestContentEncoding() { return null; }
    public String getResponseContentEncoding() { return responseContentEncoding; }

    public synchronized InputStream getRequestBodyStream() {
        return requestBody == null ? null : new ByteArrayInputStream(requestBody);
    }

    public synchronized InputStream getResponseBodyStream() {
        return responseBody == null ? null : new ByteArrayInputStream(responseBody);
    }

    public synchronized InputStream getDecodedRequestBodyStream() {
        return getRequestBodyStream();
    }

    public synchronized InputStream getDecodedResponseBodyStream() {
        InputStream raw = getResponseBodyStream();
        if (raw == null || !"gzip".equals(responseContentEncoding)) {
            return raw;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPInputStream gz = new GZIPInputStream(raw);
            gz.transferTo(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
