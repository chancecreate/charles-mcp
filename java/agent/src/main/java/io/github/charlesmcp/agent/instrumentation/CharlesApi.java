package io.github.charlesmcp.agent.instrumentation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflective handle on the small set of Charles internals the agent uses.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Only <em>non-obfuscated</em> names are looked up by name ({@code CharlesContext.getInstance},
 *   {@code ProxyListener} callbacks, {@code Transaction} getters, {@code Fields} accessors).</li>
 *   <li>Obfuscated members of the ProxyManager ({@code com.xk72.charles.u}) are located by
 *   <em>signature</em>, and the add-listener method is confirmed by its observable effect, never by
 *   its one-letter name.</li>
 *   <li>Everything is resolved once and verified up front; any mismatch throws
 *   {@link IncompatibleCharlesException} so the agent stays inert (fail closed).</li>
 * </ul>
 * There is no compile-time dependency on Charles, so this code builds and is unit-tested in CI against
 * small clean-room stubs.
 */
public final class CharlesApi {
    /** Versions the bridge has been verified against end to end with a live Charles. */
    public static final List<String> TESTED_VERSIONS = Collections.unmodifiableList(Arrays.asList("4.6.2", "4.6.8"));
    /** Human-readable description of the accepted version lines (see {@link #isSupportedVersion}). */
    public static final String SUPPORTED_VERSIONS = "4.6.x";
    /** Charles 5 moved CharlesContext into com.charlesproxy; Charles 4 has it in com.xk72.charles. */
    public static final List<String> CONTEXT_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "com.charlesproxy.CharlesContext", "com.xk72.charles.CharlesContext"));
    public static final String PROXY_LISTENER = "com.xk72.proxy.ProxyListener";
    public static final String PROXY_EVENT = "com.xk72.proxy.ProxyEvent";
    public static final String TRANSACTION = "com.xk72.charles.model.Transaction";
    public static final String SESSION = "com.xk72.charles.model.Session";
    public static final String FIELDS = "com.xk72.proxy.Fields";
    /** ProxyEvent attribute under which Charles's session recorder stores the Transaction it created. */
    public static final String RECORDER_TRANSACTION_ATTRIBUTE = "com.xk72.charles.model.Transaction";

    /** Callbacks ProxyListener must have (all present in 4.6.x and 5.x). Used to verify the interface shape. */
    static final Set<String> EXPECTED_LISTENER_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "connectionEstablished", "connectionTerminated", "transactionConnect", "receivedRequestAddress",
            "receivedRequestURL", "receivedRequestHeader", "resolvingRemote", "connectingToRemote",
            "connectedToRemote", "receivedRequest", "receivingRequestBody", "receivedResponseHeader",
            "receivingResponseBody", "receivedResponse", "webSocketEstablished",
            "receivingRequestWebSocketMessage", "receivingResponseWebSocketMessage",
            "requestWebSocketMessageParsingAborted", "responseWebSocketMessageParsingAborted",
            "transactionComplete", "transactionException", "transactionBlocked", "cancelTransaction")));

    static final List<String> REQUIRED_TRANSACTION_GETTERS = Arrays.asList(
            "getMethod", "getProtocol", "getHost", "getPort", "getPath", "getQuery", "getRequestHeader",
            "getResponseHeader", "getResponseStatus", "getRequestSize", "getResponseSize", "getStartTime",
            "getEndTime", "getException", "getStatus", "getParent", "getDecodedRequestBodyStream",
            "getDecodedResponseBodyStream", "getRequestBodyStream", "getResponseBodyStream");

    static final List<String> OPTIONAL_TRANSACTION_GETTERS = Arrays.asList(
            "getProtocolVersion", "getRequestHeaderSize", "getResponseHeaderSize", "getRequestBeginTime",
            "getRequestCompleteTime", "getResponseBeginTime", "getDnsDuration", "getConnectDuration",
            "getSslDuration", "getRemoteAddress", "getClientAddress", "getAlpnProtocol", "getSslProtocol",
            "getCipherSuite", "getWebSocket", "getTunnel", "getRequestContentEncoding",
            "getResponseContentEncoding");

    public final ClassLoader loader;
    public final Class<?> contextClass;
    public final Class<?> proxyListenerInterface;
    public final Class<?> proxyEventClass;
    public final Class<?> transactionClass;
    public final Class<?> sessionClass;
    public final Class<?> fieldsClass;

    private final Method getInstance;
    private final Method getProxyManager;
    private final Method getVersionName;
    private final Method currentSessionMethod;
    private final List<Method> listenerRegistrationCandidates;
    private final Method multiplexerGetter;
    private final Method multiplexerContains;

    final Method eventGetAttribute;
    final Method eventSetAttribute;
    final Method statusRawValue;
    final Method fieldCount;
    final Method fieldName;
    final Method fieldValue;
    final Map<String, Method> tx;

    private CharlesApi(Class<?> contextClass) throws IncompatibleCharlesException {
        this.contextClass = contextClass;
        this.loader = contextClass.getClassLoader();
        this.proxyListenerInterface = load(PROXY_LISTENER);
        this.proxyEventClass = load(PROXY_EVENT);
        this.transactionClass = load(TRANSACTION);
        this.sessionClass = load(SESSION);
        this.fieldsClass = load(FIELDS);

        if (!proxyListenerInterface.isInterface()) {
            throw new IncompatibleCharlesException(PROXY_LISTENER + " is not an interface");
        }
        Set<String> actual = new HashSet<>();
        for (Method m : proxyListenerInterface.getMethods()) {
            actual.add(m.getName());
            if (m.getReturnType() != void.class) {
                throw new IncompatibleCharlesException("ProxyListener." + m.getName() + " is not void");
            }
        }
        // Newer versions may add callbacks (5.x adds transactionContinues); those are ignored by our listener.
        // Missing callbacks mean the lifecycle changed, so we refuse.
        if (!actual.containsAll(EXPECTED_LISTENER_METHODS)) {
            Set<String> missing = new HashSet<>(EXPECTED_LISTENER_METHODS);
            missing.removeAll(actual);
            throw new IncompatibleCharlesException("ProxyListener shape mismatch; missing=" + missing);
        }

        this.getInstance = method(contextClass, "getInstance");
        if (!Modifier.isStatic(getInstance.getModifiers())) {
            throw new IncompatibleCharlesException("CharlesContext.getInstance is not static");
        }
        this.getProxyManager = method(contextClass, "getProxyManager");
        this.getVersionName = method(contextClass, "getVersionName");

        Class<?> pm = getProxyManager.getReturnType();
        Method session = null;
        List<Method> regs = new ArrayList<>();
        Method muxGetter = null;
        for (Method m : pm.getMethods()) {
            if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 0 && m.getReturnType() == sessionClass) {
                if (session != null) {
                    throw new IncompatibleCharlesException("ambiguous current-session accessor on " + pm.getName());
                }
                session = m;
            } else if (p.length == 1 && p[0] == proxyListenerInterface && m.getReturnType() == void.class) {
                regs.add(m);
            } else if (p.length == 0 && proxyListenerInterface.isAssignableFrom(m.getReturnType())) {
                if (muxGetter != null) {
                    throw new IncompatibleCharlesException("ambiguous listener multiplexer accessor on " + pm.getName());
                }
                muxGetter = m;
            }
        }
        if (session == null) {
            throw new IncompatibleCharlesException("no current-session accessor on " + pm.getName());
        }
        if (regs.size() != 2) {
            throw new IncompatibleCharlesException("expected add/remove ProxyListener pair on " + pm.getName() + ", found " + regs.size());
        }
        if (muxGetter == null) {
            throw new IncompatibleCharlesException("no listener multiplexer accessor on " + pm.getName());
        }
        regs.sort((a, b) -> a.getName().compareTo(b.getName()));
        this.currentSessionMethod = session;
        this.listenerRegistrationCandidates = Collections.unmodifiableList(regs);
        this.multiplexerGetter = muxGetter;
        Method contains = null;
        for (Method m : muxGetter.getReturnType().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == proxyListenerInterface && m.getReturnType() == boolean.class) {
                contains = m;
            }
        }
        if (contains == null) {
            throw new IncompatibleCharlesException("listener multiplexer has no contains(ProxyListener)");
        }
        this.multiplexerContains = contains;

        this.eventGetAttribute = method(proxyEventClass, "getAttribute", Object.class);
        this.eventSetAttribute = method(proxyEventClass, "setAttribute", Object.class, Object.class);
        Method getStatus = method(proxyEventClass, "getStatus");
        this.statusRawValue = method(getStatus.getReturnType(), "getRawValue");

        this.fieldCount = method(fieldsClass, "getFieldCount");
        this.fieldName = method(fieldsClass, "getFieldName", int.class);
        this.fieldValue = method(fieldsClass, "getFieldValue", int.class);

        Map<String, Method> t = new HashMap<>();
        for (String g : REQUIRED_TRANSACTION_GETTERS) {
            t.put(g, method(transactionClass, g));
        }
        for (String g : OPTIONAL_TRANSACTION_GETTERS) {
            try {
                t.put(g, transactionClass.getMethod(g));
            } catch (NoSuchMethodException ignored) {
                // optional
            }
        }
        this.tx = Collections.unmodifiableMap(t);
    }

    /**
     * Version gate, applied in addition to the API shape check. Accepts the 4.6.x line; the shape
     * verification in the constructor is what decides compatibility within it. Charles 5 stores its
     * recorded transactions in a different (obfuscated) model, so it must not pass this gate until it is
     * supported explicitly: the old classes still exist there and would otherwise pass the shape check
     * while capturing nothing.
     */
    public static boolean isSupportedVersion(String version) {
        if (version == null) {
            return false;
        }
        return version.equals("4.6") || version.startsWith("4.6.");
    }

    public static boolean isTestedVersion(String version) {
        return TESTED_VERSIONS.contains(version);
    }

    /** Resolves and verifies the API shape against an already-loaded {@code CharlesContext} class. */
    public static CharlesApi resolve(Class<?> contextClass) throws IncompatibleCharlesException {
        return new CharlesApi(contextClass);
    }

    private Class<?> load(String name) throws IncompatibleCharlesException {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IncompatibleCharlesException("missing class " + name);
        }
    }

    private static Method method(Class<?> c, String name, Class<?>... params) throws IncompatibleCharlesException {
        try {
            return c.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new IncompatibleCharlesException("missing method " + c.getName() + "." + name + Arrays.toString(params));
        }
    }

    // ------------------------------------------------------------------------------------------

    public Object contextInstance() throws ReflectiveOperationException {
        return getInstance.invoke(null);
    }

    public Object proxyManager(Object context) throws ReflectiveOperationException {
        return getProxyManager.invoke(context);
    }

    public String versionName(Object context) throws ReflectiveOperationException {
        Object v = getVersionName.invoke(context);
        return v == null ? null : v.toString();
    }

    public Object currentSession(Object proxyManager) throws ReflectiveOperationException {
        return currentSessionMethod.invoke(proxyManager);
    }

    public boolean isRegistered(Object proxyManager, Object listener) throws ReflectiveOperationException {
        Object mux = multiplexerGetter.invoke(proxyManager);
        return mux != null && Boolean.TRUE.equals(multiplexerContains.invoke(mux, listener));
    }

    /**
     * Registers {@code listener}. The ProxyManager exposes two obfuscated {@code void (ProxyListener)}
     * methods (add and remove). We call one and check the multiplexer: invoking "remove" for an absent
     * listener is a no-op, so trying both in turn is safe and unambiguous.
     *
     * @return the method that removes the listener again
     */
    public Method register(Object proxyManager, Object listener) throws ReflectiveOperationException, IncompatibleCharlesException {
        for (int i = 0; i < listenerRegistrationCandidates.size(); i++) {
            Method candidate = listenerRegistrationCandidates.get(i);
            candidate.invoke(proxyManager, listener);
            if (isRegistered(proxyManager, listener)) {
                return listenerRegistrationCandidates.get(1 - i);
            }
        }
        throw new IncompatibleCharlesException("could not register ProxyListener (neither candidate added it)");
    }

    public Object eventAttribute(Object proxyEvent, Object key) throws ReflectiveOperationException {
        return eventGetAttribute.invoke(proxyEvent, key);
    }

    public void setEventAttribute(Object proxyEvent, Object key, Object value) throws ReflectiveOperationException {
        eventSetAttribute.invoke(proxyEvent, key, value);
    }

    /** Invokes a verified zero-arg Transaction getter; returns null for absent optional getters. */
    public Object tx(Object transaction, String getter) throws ReflectiveOperationException {
        Method m = tx.get(getter);
        return m == null ? null : m.invoke(transaction);
    }

    public boolean hasTx(String getter) {
        return tx.containsKey(getter);
    }

    public int statusRaw(Object status) throws ReflectiveOperationException {
        if (status == null) {
            return -1;
        }
        Object v = statusRawValue.invoke(status);
        return v instanceof Integer ? (Integer) v : -1;
    }

    public int fieldCount(Object fields) throws ReflectiveOperationException {
        return (Integer) fieldCount.invoke(fields);
    }

    public String fieldName(Object fields, int i) throws ReflectiveOperationException {
        Object v = fieldName.invoke(fields, i);
        return v == null ? null : v.toString();
    }

    public String fieldValue(Object fields, int i) throws ReflectiveOperationException {
        Object v = fieldValue.invoke(fields, i);
        return v == null ? null : v.toString();
    }
}
