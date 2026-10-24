package com.xk72.proxy;

/** Test double: raw values match the ones observed in Charles 4.6.2. */
public enum ProxyEventStatus {
    INIT(0), WAITING(40), COMPLETE(80), FAILED(90), BLOCKED(100);

    private final int raw;

    ProxyEventStatus(int raw) {
        this.raw = raw;
    }

    public int getRawValue() {
        return raw;
    }
}
