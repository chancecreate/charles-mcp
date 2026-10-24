package com.xk72.charles;

/** Test double. */
public class CharlesContext {
    protected static CharlesContext instance;
    private final u proxyManager = new u();
    private final String version;

    public CharlesContext(String version) {
        this.version = version;
    }

    public static CharlesContext getInstance() {
        return instance;
    }

    public static void setInstanceForTest(CharlesContext c) {
        instance = c;
    }

    public u getProxyManager() {
        return proxyManager;
    }

    public String getVersionName() {
        return version;
    }
}
