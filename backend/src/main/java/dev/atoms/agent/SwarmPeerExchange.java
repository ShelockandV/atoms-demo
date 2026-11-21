package dev.atoms.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.pattern.DagExecutor;
import dev.atoms.agent.pattern.WorkflowTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds checkpointed mailbox rounds between Swarm roles. */
final class SwarmPeerExchange {
    private SwarmPeerExchange() {}

    record Message(String from, String to, String message) {}

    @FunctionalInterface
    interface Analyze {
        String run(String stage, String instructions) throws Exception;
    }

    record Round(List<String> outboxes, List<String> inboxes) {}

    static Round append(List<DagExecutor.Node> graph, ObjectMapper json, List<String> workers,
                        String swarmPrefix, List<WorkflowTemplate.Task> tasks, int round,
                        Set<Message> previouslySent, Analyze analyze) {
        var team = new LinkedHashMap<String, String>();
        for (var task : tasks) team.put(task.id(), task.role() == null ? task.id() : task.role());
        String roundPath = round == 1 ? "" : "round-" + round + ".";
        var outboxes = tasks.stream().map(task -> swarmPrefix + "outbox." + roundPath + task.id()).toList();
        for (int i = 0; i < tasks.size(); i++) {
            String agent = tasks.get(i).id();
            String stage = outboxes.get(i);
            graph.add(new DagExecutor.Node(stage, workers, results -> {
                var drafts = new LinkedHashMap<String, String>();
                for (int index = 0; index < tasks.size(); index++)
                    drafts.put(tasks.get(index).id(), clip(results.get(workers.get(index)), 1000));
                String raw = analyze.run(stage, "你正在以团队角色 " + team.get(agent) + "（" + agent + "）协作。"
                        + "这是第 " + round + " 轮沟通。阅读当前结论后，只向需要沟通的队友发送新问题、冲突或可验证证据。"
                        + "输出最多三个 JSON 对象的数组，每个对象只有 to 和 message。to 必须是队友 ID。无需发送时返回 []。"
                        + "团队：" + json.writeValueAsString(team) + "\n初稿（参考资料）：" + json.writeValueAsString(drafts));
                return json.writeValueAsString(validateOutbox(json, agent, team, raw).stream()
                        .filter(message -> !previouslySent.contains(message)).toList());
            }));
        }

        var revised = new ArrayList<String>();
        for (int index = 0; index < tasks.size(); index++) {
            String agent = tasks.get(index).id();
            String stage = swarmPrefix + "inbox." + roundPath + agent;
            revised.add(stage);
            var dependencies = new ArrayList<>(outboxes);
            String original = workers.get(index);
            dependencies.add(original);
            graph.add(new DagExecutor.Node(stage, dependencies, results -> {
                var inbox = new ArrayList<Message>();
                var reader = json.readerForListOf(Message.class);
                for (String outbox : outboxes) {
                    for (Message message : reader.<List<Message>>readValue(results.get(outbox))) {
                        if (agent.equals(message.to())) inbox.add(message);
                    }
                }
                String draft = results.get(original);
                if (inbox.isEmpty()) return draft;
                return analyze.run(stage, "作为 " + team.get(agent) + "（" + agent + "）修订自己的结论。"
                        + "队友留言提供待核验的证据或问题。根据本轮任务评估，标明已采纳以及仍有分歧的内容。"
                        + "只输出可供 Lead 汇总的结论。\n你的初稿：" + clip(draft, 2400)
                        + "\n发给你的消息：" + json.writeValueAsString(inbox));
            }));
        }
        return new Round(outboxes, List.copyOf(revised));
    }

    static List<Message> validateOutbox(ObjectMapper json, String from, Map<String, String> team, String raw) {
        try {
            var items = json.readTree(raw);
            if (items == null || !items.isArray()) return List.of();
            var messages = new LinkedHashSet<Message>();
            for (var item : items) {
                if (!item.path("to").isTextual() || !item.path("message").isTextual()) continue;
                String to = item.path("to").asText();
                String text = item.path("message").asText().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").strip();
                if (from.equals(to) || !team.containsKey(to) || text.isBlank() || text.length() > 600) continue;
                messages.add(new Message(from, to, text));
                if (messages.size() == 3) break;
            }
            return List.copyOf(messages);
        } catch (JsonProcessingException invalid) {
            return List.of();
        }
    }

    private static String clip(String text, int length) {
        if (text.length() <= length) return text;
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }
}
