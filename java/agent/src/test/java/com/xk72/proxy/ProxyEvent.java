package com.xk72.proxy;

import java.util.HashMap;
import java.util.Map;

/** Test double. */
public class ProxyEvent {
    private final Map<Object, Object> attributes = new HashMap<>();
    private ProxyEventStatus status = ProxyEventStatus.INIT;

    public Object getAttribute(Object key) {
        return attributes.get(key);
    }

    public void setAttribute(Object key, Object value) {
        attributes.put(key, value);
    }

    public ProxyEventStatus getStatus() {
        return status;
    }

    public void setStatus(ProxyEventStatus s) {
        this.status = s;
    }
}
