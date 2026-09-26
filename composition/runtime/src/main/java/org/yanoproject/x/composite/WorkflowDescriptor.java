package org.yanoproject.x.composite;

import java.util.List;
import java.util.Objects;

/** Consensus-relevant declaration of one atomic cross-component workflow route. */
public record WorkflowDescriptor(
        String workflowId,
        String semanticVersion,
        List<String> topics,
        long fromHeight,
        long untilHeight,
        List<ComponentGeneration> participants,
        int maxEffectsPerBlock
) {
    public WorkflowDescriptor {
        workflowId = CompositeValidation.id(workflowId, "workflowId");
        semanticVersion = CompositeValidation.printable(semanticVersion, "semanticVersion");
        topics = Objects.requireNonNull(topics, "topics").stream()
                .map(topic -> CompositeValidation.route(topic, "workflow topic")).sorted().toList();
        if (topics.isEmpty() || topics.size() > 64 || topics.stream().distinct().count() != topics.size()) {
            throw new IllegalArgumentException("workflow topics must contain 1-64 unique routes");
        }
        CompositeValidation.activation(fromHeight, untilHeight, "workflow activation");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        if (participants.isEmpty() || participants.size() > 16) {
            throw new IllegalArgumentException("workflow participants must contain 1-16 generations");
        }
        if (participants.stream().distinct().count() != participants.size()) {
            throw new IllegalArgumentException("workflow participants must not contain duplicates");
        }
        if (maxEffectsPerBlock < 0 || maxEffectsPerBlock > 1_048_576) {
            throw new IllegalArgumentException("workflow maxEffectsPerBlock must be between 0 and 1048576");
        }
    }

    public boolean activeAt(long height) {
        return height >= fromHeight && (untilHeight == 0 || height < untilHeight);
    }

    /** Single-topic constructor preserves the frozen schema-v1 authoring contract. */
    public WorkflowDescriptor(String workflowId, String semanticVersion, String topic,
                              long fromHeight, long untilHeight,
                              List<ComponentGeneration> participants, int maxEffectsPerBlock) {
        this(workflowId, semanticVersion, List.of(topic), fromHeight, untilHeight,
                participants, maxEffectsPerBlock);
    }

    /** Only for schema-v1 consumers; multi-topic callers must use topics(). */
    public String topic() {
        if (topics.size() != 1) throw new IllegalStateException("workflow has multiple topics");
        return topics.getFirst();
    }
}
