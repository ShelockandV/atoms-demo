package dev.atoms.agent.pattern;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按依赖和并发上限调度节点，并复用已经完成的节点结果。
 *
 * <p>示例：需求与架构节点可并行，汇总节点等待两者完成。</p>
 *
 * Executes an acyclic graph with bounded concurrency and persisted node results.
 */
public final class DagExecutor {
    /**
     * 表示接受依赖输出并产生节点结果的一段工作。
     *
     * <p>示例：<code>Work work = outputs -&gt; outputs.get("requirements");</code>。</p>
     */
    @FunctionalInterface
    public interface Work {
        String run(Map<String, String> dependencies) throws Exception;
    }

    /**
     * 声明 DAG 节点的唯一标识、依赖及节点工作。
     *
     * <p>示例：<code>new Node("summary", List.of("requirements"), work)</code>。</p>
     */
    public record Node(String id, List<String> dependencies, Work work) {
        public Node {
            dependencies = List.copyOf(dependencies);
        }
    }

    /**
     * 把一个并行节点的标识及输出送回调度循环。
     *
     * <p>示例：<code>new Completed("summary", "分析完成")</code>。</p>
     */
    private record Completed(String id, String output) {
    }

    /**
     * 由调用方提供节点持久化、有效性检查和模型计量范围。
     *
     * <p>示例：恢复时，调度器通过 nodes() 获取已完成的节点。</p>
     */
    public interface Runtime {
        List<Map<String, Object>> nodes();

        void save(String id, List<String> dependencies, String status, String output, long elapsedMs);

        void checkActive() throws InterruptedException;

        AutoCloseable track();

        boolean stale(Exception error);
    }

    public Map<String, String> execute(List<Node> nodes, Runtime context, int parallelism) throws Exception {
        var graph = validate(nodes, parallelism);
        var results = new LinkedHashMap<String, String>();
        for (var row : context.nodes())
            if (graph.containsKey(row.get("id")) && "SUCCEEDED".equals(row.get("status")))
                results.put((String) row.get("id"), (String) row.get("output"));
        var scheduled = new HashSet<>(results.keySet());
        var cancelled = new AtomicBoolean();
        var pool = Executors.newFixedThreadPool(parallelism, Thread.ofVirtual().name("atoms-dag-", 0).factory());
        var completions = new ExecutorCompletionService<Completed>(pool);
        var futures = new ArrayList<Future<Completed>>();
        int inFlight = 0;
        try {
            while (results.size() < graph.size()) {
                context.checkActive();
                for (var node : graph.values()) {
                    if (inFlight >= parallelism) break;
                    if (scheduled.contains(node.id()) || !results.keySet().containsAll(node.dependencies())) continue;
                    var dependencies = new LinkedHashMap<String, String>();
                    node.dependencies().forEach(id -> dependencies.put(id, results.get(id)));
                    context.save(node.id(), node.dependencies(), "RUNNING", "", 0);
                    scheduled.add(node.id());
                    inFlight++;
                    futures.add(completions.submit(() -> {
                        long started = System.nanoTime();
                        try (var tracking = context.track()) {
                            context.checkActive();
                            String output = node.work().run(Map.copyOf(dependencies));
                            if (output.length() > 4000) output = output.substring(0, 4000) + "\n[内容已截断]";
                            if (cancelled.get()) throw new InterruptedException("DAG 已取消");
                            context.checkActive();
                            context.save(node.id(), node.dependencies(), "SUCCEEDED", output,
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                            return new Completed(node.id(), output);
                        } catch (Exception e) {
                            if (!cancelled.get() && !(e instanceof InterruptedException) && !context.stale(e))
                                context.save(node.id(), node.dependencies(), "FAILED",
                                        "节点未完成，请查看任务错误；已有项目版本未被覆盖。", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                            throw e;
                        }
                    }));
                }
                if (inFlight == 0) throw new IllegalStateException("DAG 没有可执行节点");
                Completed completed;
                try {
                    completed = completions.take().get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception cause) throw cause;
                    if (e.getCause() instanceof Error cause) throw cause;
                    throw new IllegalStateException("DAG 节点执行失败", e.getCause());
                }
                inFlight--;
                results.put(completed.id(), completed.output());
            }
            return Map.copyOf(results);
        } finally {
            cancelled.set(true);
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
        }
    }

    static Map<String, Node> validate(List<Node> nodes, int parallelism) {
        if (parallelism < 1 || parallelism > 4 || nodes.isEmpty() || nodes.size() > 20)
            throw new IllegalArgumentException("DAG 只支持1～20个节点、1～4个并发任务");
        var graph = new LinkedHashMap<String, Node>();
        for (var node : nodes) {
            if (node.id() == null || !node.id().matches("[a-zA-Z0-9_.-]{1,80}") || node.work() == null
                    || graph.putIfAbsent(node.id(), node) != null)
                throw new IllegalArgumentException("DAG 节点名称无效或重复");
            if (new HashSet<>(node.dependencies()).size() != node.dependencies().size())
                throw new IllegalArgumentException("DAG 节点含重复依赖");
        }
        var visited = new HashSet<String>();
        while (visited.size() < graph.size()) {
            int before = visited.size();
            for (var node : graph.values()) {
                if (!graph.keySet().containsAll(node.dependencies()))
                    throw new IllegalArgumentException("DAG 引用了不存在的依赖");
                if (visited.containsAll(node.dependencies())) visited.add(node.id());
            }
            if (before == visited.size()) throw new IllegalArgumentException("DAG 含循环依赖");
        }
        return graph;
    }
}
