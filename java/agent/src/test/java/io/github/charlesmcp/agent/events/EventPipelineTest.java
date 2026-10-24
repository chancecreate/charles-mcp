package io.github.charlesmcp.agent.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventPipelineTest {

    @Test
    void overflowDropsAndCountsWithoutBlocking() {
        EventStore store = new EventStore(10_000, 3_600_000, 10_000, 1L << 30, System::currentTimeMillis);
        EventPipeline p = new EventPipeline(64, store); // worker deliberately not started
        long t0 = System.nanoTime();
        int accepted = 0;
        for (int i = 0; i < 1000; i++) {
            if (p.offer(new RawEvent(EventType.REQUEST_CREATED, 1, "s", "t" + i, null, null))) {
                accepted++;
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(64, accepted);
        assertEquals(936, p.dropped());
        assertEquals(1000, p.offered());
        assertEquals(64, p.queued());
        assertTrue(elapsedMs < 1000, "offer must never block (took " + elapsedMs + " ms)");
    }

    @Test
    void workerDrainsIntoStore() throws Exception {
        EventStore store = new EventStore(10_000, 3_600_000, 10_000, 1L << 30, System::currentTimeMillis);
        EventPipeline p = new EventPipeline(1024, store);
        p.start();
        for (int i = 0; i < 500; i++) {
            p.offer(new RawEvent(EventType.REQUEST_CREATED, System.currentTimeMillis(), "s", "t" + i, null, null));
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (store.stats().eventsAppended < 500 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        p.stop();
        assertEquals(500, store.stats().eventsAppended);
        assertEquals(0, p.dropped());
    }
}
