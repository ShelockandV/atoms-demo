package dev.atoms.web;

import dev.atoms.agent.persistence.Store;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
final class GatewayTaskEvents {
    record Subscription(String workflowId, String owner, long cursor, Set<String> types) {}

    @FunctionalInterface
    interface EventSink {
        void send(Map<String, Object> event) throws Exception;
    }

    @FunctionalInterface
    interface Heartbeat {
        void send() throws Exception;
    }

    private static final Set<String> TERMINAL = Set.of(
            "TASK_STATUS_COMPLETED", "TASK_STATUS_FAILED", "TASK_STATUS_CANCELLED", "TASK_STATUS_TIMEOUT");
    private final Store store;

    GatewayTaskEvents(Store store) { this.store = store; }

    Subscription subscribe(String id, String cursor, String types, String owner) {
        if (id == null || id.isBlank()) throw Store.error(400, "workflow_id 为必填项");
        store.simpleTask(id, owner);
        long start;
        try {
            start = cursor == null || cursor.isBlank() ? 0 : Long.parseLong(cursor);
            if (start < 0) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            throw Store.error(400, "无效的事件游标");
        }
        var filter = new HashSet<String>();
        if (types != null) Arrays.stream(types.split(",")).map(String::strip)
                .filter(type -> !type.isEmpty()).forEach(filter::add);
        return new Subscription(id, owner, start, Set.copyOf(filter));
    }

    void follow(Subscription subscription, EventSink sink, Heartbeat heartbeat) throws Exception {
        long last = subscription.cursor();
        long nextPing = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Thread.currentThread().isInterrupted()) {
            var status = store.simpleTask(subscription.workflowId(), subscription.owner()).get("status");
            var events = store.taskEventsAfter(subscription.workflowId(), last);
            for (var event : events) {
                last = ((Number) event.get("seq")).longValue();
                if (subscription.types().isEmpty() || subscription.types().contains(event.get("type"))) sink.send(event);
            }
            if (events.size() == 200) continue;
            if (TERMINAL.contains(status)) return;
            if (System.nanoTime() >= nextPing) {
                heartbeat.send();
                nextPing = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            }
            Thread.sleep(500);
        }
    }
}
