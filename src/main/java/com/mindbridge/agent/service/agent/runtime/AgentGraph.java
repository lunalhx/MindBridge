package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 项目内部确定性有向图 —— Agent 编排的图结构模型。
 *
 * <p>不依赖外部 StateGraph 框架，使用节点 + 条件边实现：
 * 每个节点绑定一个 {@link MindBridgeAgent}，每条边带一个条件谓词。
 * 运行时从入口节点出发，执行当前节点 Agent 后，按边顺序评估条件，
 * 选择第一个满足条件的边进入下一节点；若无边满足则到达终点。</p>
 *
 * <p>图构建完成后调用 {@link #validate()} 校验：</p>
 * <ul>
 *   <li>有且仅有一个入口节点</li>
 *   <li>至少一个终点节点（无出边的节点）</li>
 *   <li>所有边引用的节点都存在</li>
 *   <li>所有非终点节点至少有一条出边</li>
 *   <li>无不可达节点（从入口可达所有节点）</li>
 * </ul>
 */
public final class AgentGraph {

    private final AgentName entry;
    private final Map<AgentName, MindBridgeAgent> nodes;
    private final Map<AgentName, List<Edge>> edges;
    private final Set<AgentName> terminalNodes;

    private AgentGraph(AgentName entry,
                       Map<AgentName, MindBridgeAgent> nodes,
                       Map<AgentName, List<Edge>> edges) {
        this.entry = entry;
        this.nodes = Collections.unmodifiableMap(nodes);
        this.edges = Collections.unmodifiableMap(edges);
        Set<AgentName> terminals = new LinkedHashSet<>();
        for (AgentName name : nodes.keySet()) {
            if (!edges.containsKey(name) || edges.get(name).isEmpty()) {
                terminals.add(name);
            }
        }
        this.terminalNodes = Collections.unmodifiableSet(terminals);
    }

    /** 入口节点名称。 */
    public AgentName entry() { return entry; }

    /** 所有节点名称。 */
    public Set<AgentName> nodeNames() { return nodes.keySet(); }

    /** 终点节点（无出边）。 */
    public Set<AgentName> terminalNodes() { return terminalNodes; }

    /** 获取节点绑定的 Agent。 */
    public MindBridgeAgent agentOf(AgentName name) { return nodes.get(name); }

    /** 获取节点的出边列表（不可变）。 */
    public List<Edge> edgesFrom(AgentName name) {
        return edges.getOrDefault(name, List.of());
    }

    /**
     * 校验图结构完整性。
     *
     * @throws IllegalStateException 如果校验失败
     */
    public void validate() {
        // 1. 入口存在
        if (entry == null || !nodes.containsKey(entry)) {
            throw new IllegalStateException("Graph entry node is null or missing: " + entry);
        }

        // 2. 至少一个终点
        if (terminalNodes.isEmpty()) {
            throw new IllegalStateException("Graph has no terminal nodes (all nodes have outgoing edges — possible cycle).");
        }

        // 3. 所有边引用的节点存在
        for (Map.Entry<AgentName, List<Edge>> entry : edges.entrySet()) {
            for (Edge edge : entry.getValue()) {
                if (!nodes.containsKey(edge.to())) {
                    throw new IllegalStateException(
                            "Edge from " + entry.getKey() + " references unknown node: " + edge.to());
                }
            }
        }

        // 4. 所有非终点节点至少有一条出边
        for (AgentName name : nodes.keySet()) {
            if (!terminalNodes.contains(name)) {
                List<Edge> out = edges.getOrDefault(name, List.of());
                if (out.isEmpty()) {
                    throw new IllegalStateException(
                            "Non-terminal node " + name + " has no outgoing edges.");
                }
            }
        }

        // 5. 从入口可达所有节点
        Set<AgentName> reachable = new HashSet<>();
        collectReachable(entry, reachable);
        for (AgentName name : nodes.keySet()) {
            if (!reachable.contains(name)) {
                throw new IllegalStateException(
                        "Node " + name + " is unreachable from entry " + entry);
            }
        }
    }

    private void collectReachable(AgentName current, Set<AgentName> visited) {
        if (visited.contains(current)) return;
        visited.add(current);
        for (Edge edge : edges.getOrDefault(current, List.of())) {
            collectReachable(edge.to(), visited);
        }
    }

    /**
     * 条件边。
     *
     * @param to        目标节点
     * @param condition 进入该边的条件谓词（评估 AgentContext 当前状态）
     */
    public record Edge(AgentName to, GraphEdgeCondition condition) {
        public Edge {
            Objects.requireNonNull(to, "edge target must not be null");
            Objects.requireNonNull(condition, "edge condition must not be null");
        }
    }

    /** 条件边谓词：根据当前 AgentContext 状态判断是否走这条边。 */
    @FunctionalInterface
    public interface GraphEdgeCondition {
        boolean test(AgentContext context);
    }

    /**
     * 图构建器。
     */
    public static Builder builder(AgentName entry) {
        return new Builder(entry);
    }

    public static final class Builder {
        private final AgentName entry;
        private final Map<AgentName, MindBridgeAgent> nodes = new LinkedHashMap<>();
        private final Map<AgentName, List<Edge>> edges = new LinkedHashMap<>();

        private Builder(AgentName entry) {
            this.entry = Objects.requireNonNull(entry, "entry must not be null");
        }

        /** 添加一个节点，绑定 Agent。 */
        public Builder node(AgentName name, MindBridgeAgent agent) {
            Objects.requireNonNull(name, "node name must not be null");
            Objects.requireNonNull(agent, "agent must not be null for node: " + name);
            if (nodes.containsKey(name)) {
                throw new IllegalStateException("Duplicate node: " + name);
            }
            nodes.put(name, agent);
            return this;
        }

        /** 添加一条条件边。 */
        public Builder edge(AgentName from, AgentName to, GraphEdgeCondition condition) {
            Objects.requireNonNull(from, "edge source must not be null");
            if (!nodes.containsKey(from)) {
                throw new IllegalStateException("Edge source node not defined: " + from);
            }
            if (!nodes.containsKey(to)) {
                throw new IllegalStateException("Edge target node not defined: " + to);
            }
            edges.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, condition));
            return this;
        }

        /** 构建并校验图。 */
        public AgentGraph build() {
            AgentGraph graph = new AgentGraph(entry, nodes, edges);
            graph.validate();
            return graph;
        }
    }
}