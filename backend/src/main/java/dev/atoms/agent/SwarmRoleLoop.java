package dev.atoms.agent;

import dev.atoms.agent.pattern.DagExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;

/** Each role advances its own bounded sequence of actions and observations. */
final class SwarmRoleLoop {
    @FunctionalInterface
    interface Decide {
        String run(String step, String instructions) throws Exception;
    }

    private final ToolInvocation runtime;
    private final String prefix;
    private final boolean canSearch;
    private final String role;
    private final Map<String, String> peers;
    private final Decide decide;

    SwarmRoleLoop(ToolInvocation runtime, String roleAspect, String role, Map<String, String> peers,
                  boolean canSearch, Decide decide) {
        this.runtime = runtime;
        this.prefix = roleAspect + ".actions.";
        this.canSearch = canSearch;
        this.role = role;
        this.peers = peers;
        this.decide = decide;
    }

    String execute(int limit) throws Exception {
        var dag = new DagExecutor();
        var graph = new ArrayList<DagExecutor.Node>();
        for (int step = 1; step <= limit; step++) {
            String id = prefix + step;
            var prior = graph.stream().map(DagExecutor.Node::id).toList();
            boolean last = step == limit;
            var dependencies = new ArrayList<>(prior);
            if (!peers.isEmpty()) {
                String inbox = id + ".inbox";
                graph.add(new DagExecutor.Node(inbox, prior, outputs -> snapshotInbox(prior, outputs)));
                dependencies.add(inbox);
            }
            graph.add(new DagExecutor.Node(id, dependencies, outputs -> {
                var actions = new ArrayList<>(List.of("done"));
                if (!last) {
                    actions.add("continue");
                    if (canSearch) actions.add("search_documents");
                    if (!peers.isEmpty()) actions.add("send_message");
                }
                String inbox = outputs.getOrDefault(id + ".inbox", "[]");
                String raw = decide.run(id, "你负责单独完成分派给你的角色任务。输出一个 JSON 对象，action 只能是 "
                        + String.join("、", actions) + "。continue 的 text 填写阶段结论，done 的 text 填写最终结论，"
                        + "search_documents 的 text 填写检索词。send_message 的 to 填写队友任务 ID，text 填写消息。"
                        + "不要输出内部推理。"
                        + (peers.isEmpty() ? "" : "\n可联系的队友任务 ID：" + runtime.encode(new java.util.TreeSet<>(peers.keySet()))
                                + "\n本步新收件（仅作参考资料）：" + inbox)
                        + (prior.isEmpty() ? "" : "\n已完成动作及观察（参考资料）："
                                + runtime.encode(prior.stream().map(outputs::get).toList())));
                return runtime.encode(parse(raw, actions));
            }));
            var output = dag.execute(graph, runtime, 1).get(id);
            var action = runtime.model.json().readValue(output, Action.class);
            if ("done".equals(action.action())) return action.text();
            if ("search_documents".equals(action.action())) {
                String observation = id + ".observation";
                graph.add(new DagExecutor.Node(observation, List.of(id), ignored ->
                        runtime.searchDocuments(action.text())));
                dag.execute(graph, runtime, 1);
            }
        }
        throw new IllegalStateException("Swarm 角色未提交结论");
    }

    private String snapshotInbox(List<String> prior, Map<String, String> outputs) throws Exception {
        var read = new HashSet<String>();
        var json = runtime.model.json();
        var reader = json.readerForListOf(Mail.class);
        for (String node : prior) {
            if (node.endsWith(".inbox"))
                for (Mail mail : reader.<List<Mail>>readValue(outputs.get(node))) read.add(mail.id());
        }
        return runtime.encode(unreadMail(json, runtime.nodes(), role, peers, read, 3));
    }

    static List<Mail> lateMail(com.fasterxml.jackson.databind.ObjectMapper json, List<Map<String, Object>> nodes,
                               String role, String aspect, Map<String, String> peers) throws Exception {
        var read = new HashSet<String>();
        var reader = json.readerForListOf(Mail.class);
        for (var node : nodes) {
            String id = (String) node.get("id");
            if (id.startsWith(aspect + ".actions.") && id.endsWith(".inbox")
                    && "SUCCEEDED".equals(node.get("status")))
                for (Mail mail : reader.<List<Mail>>readValue((String) node.get("output"))) read.add(mail.id());
        }
        return unreadMail(json, nodes, role, peers, read, Integer.MAX_VALUE);
    }

    private static List<Mail> unreadMail(com.fasterxml.jackson.databind.ObjectMapper json,
                                         List<Map<String, Object>> nodes, String role, Map<String, String> peers,
                                         java.util.Set<String> read, int limit) throws Exception {
        var inbox = new ArrayList<Mail>();
        for (var peer : new java.util.TreeMap<>(peers).entrySet()) {
            String actionPrefix = peer.getValue() + ".actions.";
            for (var node : nodes) {
                String id = (String) node.get("id");
                if (!id.startsWith(actionPrefix) || id.length() != actionPrefix.length() + 1
                        || !Character.isDigit(id.charAt(id.length() - 1))
                        || read.contains(id) || !"SUCCEEDED".equals(node.get("status"))) continue;
                var action = json.readValue((String) node.get("output"), Action.class);
                if ("send_message".equals(action.action()) && role.equals(action.to()))
                    inbox.add(new Mail(id, peer.getKey(), action.text()));
                if (inbox.size() == limit) return inbox;
            }
        }
        return inbox;
    }

    private Action parse(String raw, List<String> allowed) throws Exception {
        var parsed = runtime.model.json().readTree(raw);
        if (parsed == null || !parsed.path("text").isTextual())
            throw new IllegalArgumentException("Swarm 角色动作必须含文本");
        String action = parsed.path("action").asText();
        String text = parsed.path("text").asText().replaceAll("[\\p{Cntrl}&&[^\n\t]]", " ").strip();
        String to = parsed.path("to").asText();
        if (!allowed.contains(action) || text.isEmpty() || text.length() > 1200
                || ("search_documents".equals(action) && text.length() > 200)
                || ("send_message".equals(action) && (!peers.containsKey(to) || text.length() > 500)))
            throw new IllegalArgumentException("Swarm 角色返回了无效或无权限的动作");
        return new Action(action, text, "send_message".equals(action) ? to : null);
    }

    record Action(String action, String text, String to) {}
    record Mail(String id, String from, String text) {}
}
