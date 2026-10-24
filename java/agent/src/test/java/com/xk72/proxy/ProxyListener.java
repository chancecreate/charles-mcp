package com.xk72.proxy;

import com.xk72.proxy.websocket.WebSocketMessage;

/**
 * Test double with the same callback names/signatures as Charles 4.6.2's public ProxyListener
 * interface (as observed with javap; see docs/reverse-engineering-findings.md). No Charles code.
 */
public interface ProxyListener {
    void connectionEstablished(Connection c);
    void connectionTerminated(Connection c);
    void transactionConnect(ProxyEvent e);
    void receivedRequestAddress(ProxyEvent e);
    void receivedRequestURL(ProxyEvent e);
    void receivedRequestHeader(ProxyEvent e);
    void resolvingRemote(ProxyEvent e);
    void connectingToRemote(ProxyEvent e);
    void connectedToRemote(ProxyEvent e);
    void receivedRequest(ProxyEvent e);
    void receivingRequestBody(ProxyEvent e, byte[] b, int n);
    void receivedResponseHeader(ProxyEvent e);
    void receivingResponseBody(ProxyEvent e, byte[] b, int n);
    void receivedResponse(ProxyEvent e);
    void webSocketEstablished(ProxyEvent e);
    void receivingRequestWebSocketMessage(ProxyEvent e, WebSocketMessage m);
    void receivingResponseWebSocketMessage(ProxyEvent e, WebSocketMessage m);
    void requestWebSocketMessageParsingAborted(ProxyEvent e);
    void responseWebSocketMessageParsingAborted(ProxyEvent e);
    void transactionComplete(ProxyEvent e);
    void transactionException(ProxyEvent e);
    void transactionBlocked(ProxyEvent e);
    void cancelTransaction(ProxyEvent e);
}
