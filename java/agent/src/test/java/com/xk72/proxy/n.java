package com.xk72.proxy;

import com.xk72.proxy.websocket.WebSocketMessage;

import java.util.ArrayList;
import java.util.List;

/** Test double for Charles's listener multiplexer (copy-on-write list, dispatch in order). */
public class n implements ProxyListener {
    private volatile List<ProxyListener> listeners = new ArrayList<>();

    public synchronized void a(ProxyListener l) {
        List<ProxyListener> copy = new ArrayList<>(listeners);
        copy.add(l);
        listeners = copy;
    }

    public synchronized void b(ProxyListener l) {
        List<ProxyListener> copy = new ArrayList<>(listeners);
        copy.remove(l);
        listeners = copy;
    }

    public boolean c(ProxyListener l) {
        return listeners.contains(l);
    }

    public int size() {
        return listeners.size();
    }

    @Override public void connectionEstablished(Connection c) { for (ProxyListener l : listeners) l.connectionEstablished(c); }
    @Override public void connectionTerminated(Connection c) { for (ProxyListener l : listeners) l.connectionTerminated(c); }
    @Override public void transactionConnect(ProxyEvent e) { for (ProxyListener l : listeners) l.transactionConnect(e); }
    @Override public void receivedRequestAddress(ProxyEvent e) { for (ProxyListener l : listeners) l.receivedRequestAddress(e); }
    @Override public void receivedRequestURL(ProxyEvent e) { for (ProxyListener l : listeners) l.receivedRequestURL(e); }
    @Override public void receivedRequestHeader(ProxyEvent e) { for (ProxyListener l : listeners) l.receivedRequestHeader(e); }
    @Override public void resolvingRemote(ProxyEvent e) { for (ProxyListener l : listeners) l.resolvingRemote(e); }
    @Override public void connectingToRemote(ProxyEvent e) { for (ProxyListener l : listeners) l.connectingToRemote(e); }
    @Override public void connectedToRemote(ProxyEvent e) { for (ProxyListener l : listeners) l.connectedToRemote(e); }
    @Override public void receivedRequest(ProxyEvent e) { for (ProxyListener l : listeners) l.receivedRequest(e); }
    @Override public void receivingRequestBody(ProxyEvent e, byte[] b, int c) { for (ProxyListener l : listeners) l.receivingRequestBody(e, b, c); }
    @Override public void receivedResponseHeader(ProxyEvent e) { for (ProxyListener l : listeners) l.receivedResponseHeader(e); }
    @Override public void receivingResponseBody(ProxyEvent e, byte[] b, int c) { for (ProxyListener l : listeners) l.receivingResponseBody(e, b, c); }
    @Override public void receivedResponse(ProxyEvent e) { for (ProxyListener l : listeners) l.receivedResponse(e); }
    @Override public void webSocketEstablished(ProxyEvent e) { for (ProxyListener l : listeners) l.webSocketEstablished(e); }
    @Override public void receivingRequestWebSocketMessage(ProxyEvent e, WebSocketMessage m) { for (ProxyListener l : listeners) l.receivingRequestWebSocketMessage(e, m); }
    @Override public void receivingResponseWebSocketMessage(ProxyEvent e, WebSocketMessage m) { for (ProxyListener l : listeners) l.receivingResponseWebSocketMessage(e, m); }
    @Override public void requestWebSocketMessageParsingAborted(ProxyEvent e) { for (ProxyListener l : listeners) l.requestWebSocketMessageParsingAborted(e); }
    @Override public void responseWebSocketMessageParsingAborted(ProxyEvent e) { for (ProxyListener l : listeners) l.responseWebSocketMessageParsingAborted(e); }
    @Override public void transactionComplete(ProxyEvent e) { for (ProxyListener l : listeners) l.transactionComplete(e); }
    @Override public void transactionException(ProxyEvent e) { for (ProxyListener l : listeners) l.transactionException(e); }
    @Override public void transactionBlocked(ProxyEvent e) { for (ProxyListener l : listeners) l.transactionBlocked(e); }
    @Override public void cancelTransaction(ProxyEvent e) { for (ProxyListener l : listeners) l.cancelTransaction(e); }
}
