package dev.atoms.agent.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按模型累计可重试故障。例如连续失败三次后暂时跳过该模型。
 */
final class ModelCircuitBreaker {
    /**
     * 保存单个模型的故障计数和探测状态。
     */
    private static final class State {
        long epoch;
        long openedAt;
        int failures;
        boolean open;
        boolean probing;
    }

    /**
     * 标识一次放行的请求。旧请求的结果不能更改新一轮熔断。
     */
    record Permit(State state, long epoch, boolean probe) {
    }

    /**
     * 表示模型仍处于冷却期，或者已有探测请求在执行。
     */
    static final class Open extends IllegalStateException {
        Open(String model) {
            super("模型暂时熔断：" + model);
        }
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final int threshold;
    private final long cooldownNanos;
    private final LongSupplier clock;

    ModelCircuitBreaker(int threshold, long cooldownNanos, LongSupplier clock) {
        this.threshold = threshold;
        this.cooldownNanos = cooldownNanos;
        this.clock = clock;
    }

    Permit acquire(String model) {
        if (threshold == 0) return null;
        var state = states.computeIfAbsent(model, ignored -> new State());
        synchronized (state) {
            if (state.open) {
                if (state.probing || clock.getAsLong() - state.openedAt < cooldownNanos) throw new Open(model);
                state.probing = true;
            }
            return new Permit(state, state.epoch, state.open);
        }
    }

    void complete(Permit permit, Boolean success) {
        if (permit == null) return;
        var state = permit.state();
        synchronized (state) {
            if (state.epoch != permit.epoch()) return;
            state.probing = false;
            if (success == null) return;
            if (success) {
                if (state.open) state.epoch++;
                state.open = false;
                state.failures = 0;
            } else if (permit.probe() || ++state.failures >= threshold) {
                state.open = true;
                state.openedAt = clock.getAsLong();
                state.epoch++;
            }
        }
    }
}
