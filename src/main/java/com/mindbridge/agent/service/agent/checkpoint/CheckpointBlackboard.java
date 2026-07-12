package com.mindbridge.agent.service.agent.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.agent.AgentContext.ResponseArtifactPayload;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentEvent;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.time.Instant;
import java.util.List;

/**
 * 可序列化的 Blackboard 快照。
 *
 * <p>将不可变 {@link AgentBlackboard} 的 flags、artifacts 和 events 转换为纯 JSON 结构。
 * artifact 的 payload 通过 {@link CheckpointArtifact#getPayloadType()} 显式标记类型，
 * 避免使用 Jackson default typing 带来的安全风险。</p>
 */
public record CheckpointBlackboard(
        List<String> flags,
        List<CheckpointArtifact> artifacts,
        List<CheckpointEvent> events
) {

    /** payload 类型常量，用于序列化/反序列化时识别 payload 类别。 */
    public static final String PAYLOAD_INTENT = "IntentType";
    public static final String PAYLOAD_ASSESSMENT = "PsychologyAssessment";
    public static final String PAYLOAD_KNOWLEDGE = "SearchResultList";
    public static final String PAYLOAD_RESPONSE = "ResponseArtifactPayload";
    public static final String PAYLOAD_UNKNOWN = "Unknown";

    /** 从不可变 Blackboard 构建可序列化快照。 */
    public static CheckpointBlackboard from(AgentBlackboard bb, ObjectMapper mapper) {
        List<String> flagNames = bb.flags().stream().map(Enum::name).toList();
        List<CheckpointArtifact> arts = bb.artifacts().values().stream()
                .map(a -> CheckpointArtifact.from(a, mapper))
                .toList();
        List<CheckpointEvent> evs = bb.events().stream()
                .map(CheckpointEvent::from)
                .toList();
        return new CheckpointBlackboard(flagNames, arts, evs);
    }

    /** 将快照还原为不可变 Blackboard 实例。 */
    public AgentBlackboard toBlackboard(ObjectMapper mapper) {
        AgentBlackboard bb = AgentBlackboard.empty();
        if (flags != null) {
            for (String flag : flags) {
                try {
                    bb = bb.setFlag(AgentFlag.valueOf(flag));
                } catch (IllegalArgumentException e) {
                    // 未知 flag，跳过
                }
            }
        }
        if (artifacts != null) {
            for (CheckpointArtifact a : artifacts) {
                try {
                    bb = bb.addArtifact(a.toArtifact(mapper));
                } catch (Exception e) {
                    // 单个 artifact 损坏不影响其他恢复
                }
            }
        }
        if (events != null) {
            for (CheckpointEvent e : events) {
                try {
                    bb = bb.addEvent(e.toEvent());
                } catch (Exception ex) {
                    // 单个 event 损坏不影响恢复
                }
            }
        }
        return bb;
    }

    // ────────────── Artifact ──────────────

    public record CheckpointArtifact(
            String name,
            String producer,
            Instant timestamp,
            String payloadType,
            JsonNode payload
    ) {
        static CheckpointArtifact from(AgentArtifact a, ObjectMapper mapper) {
            String type = payloadTypeName(a.payload());
            JsonNode node = mapper.valueToTree(a.payload());
            return new CheckpointArtifact(
                    a.name(),
                    a.producer() != null ? a.producer().name() : null,
                    a.timestamp(),
                    type,
                    node);
        }

        AgentArtifact toArtifact(ObjectMapper mapper) {
            AgentName prod = producer != null ? AgentName.valueOf(producer) : null;
            Object pl = deserializePayload(payloadType, payload, mapper);
            return new AgentArtifact(name, prod, timestamp != null ? timestamp : Instant.now(), pl);
        }

        static String payloadTypeName(Object payload) {
            if (payload == null) return PAYLOAD_UNKNOWN;
            if (payload instanceof IntentType) return PAYLOAD_INTENT;
            if (payload instanceof PsychologyAssessment) return PAYLOAD_ASSESSMENT;
            if (payload instanceof ResponseArtifactPayload) return PAYLOAD_RESPONSE;
            if (payload instanceof List<?> list) {
                if (!list.isEmpty() && list.get(0) instanceof SearchResult) {
                    return PAYLOAD_KNOWLEDGE;
                }
                if (list.isEmpty()) {
                    return PAYLOAD_KNOWLEDGE;
                }
            }
            return PAYLOAD_UNKNOWN;
        }

        static Object deserializePayload(String type, JsonNode node, ObjectMapper mapper) {
            if (node == null || node.isNull()) return null;
            try {
                return switch (type != null ? type : PAYLOAD_UNKNOWN) {
                    case PAYLOAD_INTENT -> mapper.treeToValue(node, IntentType.class);
                    case PAYLOAD_ASSESSMENT -> mapper.treeToValue(node, PsychologyAssessment.class);
                    case PAYLOAD_RESPONSE -> mapper.treeToValue(node, ResponseArtifactPayload.class);
                    case PAYLOAD_KNOWLEDGE -> mapper.readerFor(
                                    mapper.getTypeFactory().constructCollectionType(List.class, SearchResult.class))
                            .readValue(node);
                    default -> null;
                };
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ────────────── Event ──────────────

    public record CheckpointEvent(
            String eventType,
            String agent,
            Instant timestamp,
            String summary
    ) {
        static CheckpointEvent from(AgentEvent e) {
            return new CheckpointEvent(
                    e.eventType(),
                    e.agent() != null ? e.agent().name() : null,
                    e.timestamp(),
                    e.summary());
        }

        AgentEvent toEvent() {
            AgentName ag = agent != null ? AgentName.valueOf(agent) : null;
            return new AgentEvent(eventType, ag, timestamp != null ? timestamp : Instant.now(),
                    summary != null ? summary : "");
        }
    }
}
