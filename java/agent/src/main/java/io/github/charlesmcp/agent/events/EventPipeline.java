package io.github.charlesmcp.agent.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hand-off between Charles's proxy threads and our worker.
 *
 * <p>{@link #offer} never blocks: if the bounded queue is full the event is dropped and counted. This
 * is the backpressure policy — Charles's proxying always wins over observability.
 */
public final class EventPipeline {
    private static final Logger LOG = Logger.getLogger("charles-mcp");

    private final BlockingQueue<RawEvent> queue;
    private final EventStore store;
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong workerErrors = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    public EventPipeline(int capacity, EventStore store) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.store = store;
        this.worker = new Thread(this::run, "charles-mcp-events");
        this.worker.setDaemon(true);
        this.worker.setPriority(Thread.NORM_PRIORITY - 1);
    }

    public void start() {
        worker.start();
    }

    /** Called on Charles's proxy thread. O(1), non-blocking, never throws. */
    public boolean offer(RawEvent event) {
        offered.incrementAndGet();
        if (queue.offer(event)) {
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    private void run() {
        List<RawEvent> batch = new ArrayList<>(256);
        while (running) {
            try {
                RawEvent first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, 255);
                for (RawEvent e : batch) {
                    store.append(e);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                workerErrors.incrementAndGet();
                LOG.log(Level.WARNING, "charles-mcp event worker error (continuing): " + t.getClass().getName());
            } finally {
                batch.clear();
            }
        }
    }

    /** Synchronously drains everything currently queued (tests and shutdown). */
    public void flushForTest() {
        List<RawEvent> batch = new ArrayList<>();
        queue.drainTo(batch);
        for (RawEvent e : batch) {
            store.append(e);
        }
    }

    public void stop() {
        running = false;
        worker.interrupt();
    }

    public long offered() {
        return offered.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long workerErrors() {
        return workerErrors.get();
    }

    public int queued() {
        return queue.size();
    }

    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }
}
