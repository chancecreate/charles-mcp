package io.github.charlesmcp.agent.bridge;

import io.github.charlesmcp.agent.events.EventStore;
import io.github.charlesmcp.agent.instrumentation.BodyReader;

import java.util.Map;

/** What the bridge server needs from the rest of the agent. Lets the server be tested without Charles. */
public interface BridgeBackend {
    EventStore store();

    /** Status fields merged into the {@code status} response (Charles version, counters, ...). */
    Map<String, Object> status();

    /** Bounded body read from a live transaction; null if no body. Called on a bridge thread only. */
    BodyReader.Body readBody(Object liveTransaction, boolean response, int maxBytes) throws Exception;

    /** Whether the live transaction is still in a session tree; null if unknown. */
    Boolean attachedToSession(Object liveTransaction);
}
