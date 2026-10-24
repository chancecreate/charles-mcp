package io.github.charlesmcp.agent.instrumentation;

import io.github.charlesmcp.agent.AgentInfo;
import io.github.charlesmcp.agent.bridge.BridgeBackend;
import io.github.charlesmcp.agent.bridge.BridgeServer;
import io.github.charlesmcp.agent.bridge.Discovery;
import io.github.charlesmcp.agent.config.AgentConfig;
import io.github.charlesmcp.agent.config.AgentLogging;
import io.github.charlesmcp.agent.events.EventPipeline;
import io.github.charlesmcp.agent.events.EventStore;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.github.charlesmcp.agent.json.Json.obj;

/**
 * Waits for Charles to finish initialising, verifies the API shape, then wires
 * listener → pipeline → store → bridge. Runs on its own daemon thread; any failure leaves Charles
 * untouched and simply logs why the bridge is inactive.
 */
public final class CharlesHook implements BridgeBackend {
    private static final Logger LOG = Logger.getLogger("charles-mcp");
    static final long POLL_INTERVAL_MS = 250;
    static final long GIVE_UP_AFTER_MS = 15 * 60_000;

    private final AgentConfig config;
    private final Supplier<Class<?>> contextClassLocator;
    private final long startedMs = System.currentTimeMillis();

    private volatile String state = "waiting_for_charles";
    private volatile String stateDetail;
    private volatile CharlesApi api;
    private volatile String charlesVersion;
    private volatile Object proxyManager;
    private volatile EventStore store;
    private volatile EventPipeline pipeline;
    private volatile LifecycleListener listener;
    private volatile BodyReader bodies;
    private volatile BridgeServer bridge;
    private volatile Object proxyListener;
    private volatile Method unregister;

    public CharlesHook(AgentConfig config, Supplier<Class<?>> contextClassLocator) {
        this.config = config;
        this.contextClassLocator = contextClassLocator;
    }

    public Thread startAsync() {
        Thread t = new Thread(this::runSafely, "charles-mcp-bootstrap");
        t.setDaemon(true);
        t.start();
        return t;
    }

    void runSafely() {
        try {
            run();
        } catch (IncompatibleCharlesException e) {
            state = "unsupported";
            stateDetail = e.getMessage();
            LOG.warning("charles-mcp: bridge disabled, Charles API mismatch: " + e.getMessage()
                    + " (set charlesmcp.allowUnsupported=true only for investigation)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            state = "error";
            stateDetail = t.getClass().getName();
            LOG.log(Level.WARNING, "charles-mcp: bridge failed to start; Charles is unaffected", t);
        }
    }

    void run() throws Exception {
        long deadline = System.currentTimeMillis() + GIVE_UP_AFTER_MS;
        Object context = null;
        Object pm = null;
        while (System.currentTimeMillis() < deadline) {
            Class<?> ctxClass = contextClassLocator.get();
            if (ctxClass != null) {
                if (api == null) {
                    api = CharlesApi.resolve(ctxClass);
                }
                context = api.contextInstance();
                if (context != null) {
                    pm = api.proxyManager(context);
                    if (pm != null) {
                        break;
                    }
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        if (pm == null) {
            state = "timeout";
            LOG.warning("charles-mcp: Charles did not finish initialising; bridge not started");
            return;
        }
        charlesVersion = api.versionName(context);
        if (!CharlesApi.isSupportedVersion(charlesVersion)) {
            if (!config.allowUnsupported) {
                throw new IncompatibleCharlesException("Charles version " + charlesVersion + " is not in " + CharlesApi.SUPPORTED_VERSIONS);
            }
            LOG.warning("charles-mcp: running against unsupported Charles " + charlesVersion + " (allowUnsupported=true)");
        }
        attach(pm);
    }

    /** Wires everything once a ProxyManager exists. Package-visible for tests. */
    void attach(Object pm) throws Exception {
        AgentLogging.configure(config.logFile);
        this.proxyManager = pm;
        this.store = new EventStore(config.maxEvents, config.maxEventAgeMillis, config.maxTransactions,
                config.maxRetainedHeaderBytes, System::currentTimeMillis);
        this.pipeline = new EventPipeline(config.queueCapacity, store);
        this.bodies = new BodyReader(api);
        this.listener = new LifecycleListener(api, pipeline, System::currentTimeMillis, config.debugLifecycle);
        this.bridge = new BridgeServer(config, this, AgentInfo.VERSION);

        pipeline.start();
        bridge.start();
        listener.bind(pm, bridge.port());
        proxyListener = listener.asProxyListener();
        unregister = api.register(pm, proxyListener);
        listener.sessionId(); // record the initial session

        Discovery.write(config.discoveryFile, discoveryContent());
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "charles-mcp-shutdown"));
        state = "active";
        LOG.info("charles-mcp: bridge active on 127.0.0.1:" + bridge.port() + " (discovery file: " + config.discoveryFile + ")");
    }

    Map<String, Object> discoveryContent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", 1);
        m.put("host", "127.0.0.1");
        m.put("port", bridge.port());
        m.put("token", bridge.token());
        m.put("pid", ProcessHandle.current().pid());
        m.put("bridge_version", AgentInfo.VERSION);
        m.put("protocol", BridgeServer.PROTOCOL_VERSION);
        m.put("charles_version", charlesVersion);
        m.put("started_ms", startedMs);
        return m;
    }

    void shutdown() {
        try {
            if (unregister != null && proxyManager != null && proxyListener != null) {
                unregister.invoke(proxyManager, proxyListener);
            }
        } catch (Throwable ignored) {
            // JVM is exiting
        }
        if (bridge != null) {
            Discovery.deleteIfOwned(config.discoveryFile, bridge.token());
            bridge.stop();
        }
        if (pipeline != null) {
            pipeline.stop();
        }
    }

    // ------------------------------------------------------------------------------------------
    // BridgeBackend

    @Override
    public EventStore store() {
        return store;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent_state", state);
        m.put("agent_state_detail", stateDetail);
        m.put("charles_version", charlesVersion);
        m.put("supported_charles_versions", CharlesApi.SUPPORTED_VERSIONS);
        m.put("charles_version_tested", charlesVersion == null ? null : CharlesApi.isTestedVersion(charlesVersion));
        m.put("pid", ProcessHandle.current().pid());
        m.put("started_ms", startedMs);
        Boolean recording = null;
        try {
            if (proxyManager != null) {
                recording = api.currentSession(proxyManager) != null;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            recording = null;
        }
        // Inferred: Charles detaches the recording session while recording is stopped.
        m.put("recording", recording);
        LifecycleListener l = listener;
        m.put("session_id", l == null ? null : l.currentSessionIdOrNull());
        EventPipeline p = pipeline;
        if (p != null) {
            m.put("queue", obj("capacity", p.capacity(), "queued", p.queued(), "offered", p.offered(),
                    "dropped", p.dropped(), "worker_errors", p.workerErrors()));
        }
        if (l != null) {
            m.put("listener", obj("transactions_seen", l.transactionsSeen(), "callback_errors", l.callbackErrors(),
                    "duplicate_terminals_suppressed", l.duplicateTerminalsSuppressed(),
                    "ignored_own_traffic", l.ignoredOwnTraffic()));
        }
        return m;
    }

    @Override
    public BodyReader.Body readBody(Object liveTransaction, boolean response, int maxBytes) throws Exception {
        return bodies.read(liveTransaction, response, maxBytes);
    }

    @Override
    public Boolean attachedToSession(Object liveTransaction) {
        try {
            return bodies.attachedToSession(liveTransaction);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // Test accessors
    public String state() {
        return state;
    }

    public BridgeServer bridge() {
        return bridge;
    }

    public EventPipeline pipeline() {
        return pipeline;
    }

    public void setApiForTest(CharlesApi api, String version) {
        this.api = api;
        this.charlesVersion = version;
    }
}
