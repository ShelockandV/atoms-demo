package dev.atoms.agent;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

/**
 * 将当前任务的模型调用预留与 token 计量绑定到模型请求范围。
 *
 * <p>示例：<code>try (var scope = ModelBudget.track(model, store, runId, owner, token, 20)) { ... }</code>。</p>
 */
final class ModelBudget {
    private ModelBudget() {
    }

    static AutoCloseable track(ModelClient model, Store store, String run, String owner, String token, int limit) {
        return trackWithOutputLimit(model, store, run, owner, token, limit, 0);
    }

    static AutoCloseable trackWithOutputLimit(ModelClient model, Store store, String run, String owner,
                                              String token, int limit, int outputLimit) {
        return bind(model, store, run, owner, token, limit, null, 0, outputLimit);
    }

    static AutoCloseable track(ModelClient model, Store store, String run, String owner, String token, int limit,
                               String node, int nodeLimit, int outputDivisor) {
        return bind(model, store, run, owner, token, limit, node, nodeLimit,
                nodeLimit == 0 ? 0 : Math.max(1, nodeLimit / outputDivisor));
    }

    private static AutoCloseable bind(ModelClient model, Store store, String run, String owner, String token,
                                      int limit, String node, int nodeLimit, int outputLimit) {
        var skill = store.runSkill(run);
        long global = model.tokenBudget();
        long skillLimit = skill == null ? 0 : skill.budgetMax();
        long effective = global == 0 ? skillLimit : skillLimit == 0 ? global : Math.min(global, skillLimit);
        return model.trackWithModelReservation((actualModel, estimate) -> store.reserveModelCall(run, owner, token, limit, effective,
                        estimate, node, nodeLimit, actualModel),
                (actualModel, estimate, input, output) -> store.settleModelCall(run, owner, token, estimate, input, output, node, actualModel),
                () -> store.runMode(run), outputLimit,
                run.replace("-", ""));
    }
}
