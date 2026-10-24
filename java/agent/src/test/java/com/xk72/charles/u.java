package com.xk72.charles;

import com.xk72.charles.model.Session;
import com.xk72.proxy.ProxyListener;
import com.xk72.proxy.n;

/** Test double for the ProxyManager: same member shapes as Charles 4.6.2 (a/b listener pair, p, q). */
public class u {
    private final n mux = new n();
    private volatile Session session;

    public void a(ProxyListener l) {
        mux.a(l);
    }

    public void b(ProxyListener l) {
        mux.b(l);
    }

    public n p() {
        return mux;
    }

    public Session q() {
        return session;
    }

    public void a(Session s) {
        this.session = s;
    }
}
