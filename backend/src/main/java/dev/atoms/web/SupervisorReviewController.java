package dev.atoms.web;

import dev.atoms.agent.GenerationService;
import dev.atoms.agent.persistence.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/supervisor-reviews")
class SupervisorReviewController {
    private final Store store;
    private final GenerationService generation;
    private final String token;

    SupervisorReviewController(Store store, GenerationService generation,
                               @Value("${atoms.approval.supervisor-review-token:}") String token) {
        this.store = store;
        this.generation = generation;
        this.token = token;
    }

    @GetMapping
    Object pending(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
        authorize(authorization);
        return Map.of("reviews", store.pendingSupervisorReviews());
    }

    record Decision(String approvalId, Boolean approved) {}

    @PostMapping("/{run}")
    Object decide(@PathVariable String run, @RequestBody Decision decision,
                  @RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
        authorize(authorization);
        if (decision.approvalId() == null || decision.approved() == null) throw Store.error(400, "缺少审批版本或决定");
        generation.decideSupervisorReview(run, decision.approvalId(), decision.approved());
        return Map.of("success", true);
    }

    private void authorize(String authorization) {
        if (token.isBlank()) throw Store.error(404, "未启用 Supervisor 审核");
        if (!MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) throw Store.error(401, "需要 Supervisor 审批凭据");
    }
}
