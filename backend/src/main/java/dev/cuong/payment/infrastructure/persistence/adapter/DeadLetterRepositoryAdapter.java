package dev.cuong.payment.infrastructure.persistence.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cuong.payment.application.port.out.DeadLetterRepository;
import dev.cuong.payment.domain.event.TransactionEventType;
import dev.cuong.payment.infrastructure.persistence.entity.DeadLetterEventJpaEntity;
import dev.cuong.payment.infrastructure.persistence.repository.DeadLetterEventJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeadLetterRepositoryAdapter implements DeadLetterRepository {

    private final DeadLetterEventJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void save(DeadLetterEntry entry) {
        DeadLetterEventJpaEntity entity = DeadLetterEventJpaEntity.builder()
                .topic(entry.topic())
                .kafkaPartition(entry.kafkaPartition())
                .kafkaOffset(entry.kafkaOffset())
                .payload(entry.payload())
                .errorMessage(truncate(entry.errorMessage(), 2000))
                .build();
        jpaRepository.save(entity);
    }

    @Override
    public List<DlqEvent> findAll(int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return jpaRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toDlqEvent)
                .toList();
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }

    @Override
    public Optional<DlqEvent> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDlqEvent);
    }

    @Override
    public void markResolved(UUID id, String resolvedBy) {
        jpaRepository.findById(id).ifPresent(entity -> {
            entity.resolve(resolvedBy);
            jpaRepository.save(entity);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private DlqEvent toDlqEvent(DeadLetterEventJpaEntity e) {
        UUID transactionId = null;
        TransactionEventType eventType = null;

        // Parse transactionId and eventType from the JSON payload.
        // Failures are non-fatal: callers receive null fields and handle gracefully.
        try {
            JsonNode node = objectMapper.readTree(e.getPayload());
            JsonNode txIdNode = node.get("transactionId");
            if (txIdNode != null && !txIdNode.isNull()) {
                transactionId = UUID.fromString(txIdNode.asText());
            }
            JsonNode typeNode = node.get("eventType");
            if (typeNode != null && !typeNode.isNull()) {
                eventType = TransactionEventType.valueOf(typeNode.asText());
            }
        } catch (Exception parseEx) {
            log.warn("[DLQ] Could not parse transactionId/eventType from payload: dlqId={}, error={}",
                    e.getId(), parseEx.getMessage());
        }

        return new DlqEvent(
                e.getId(), e.getTopic(), e.getKafkaPartition(), e.getKafkaOffset(),
                e.getPayload(), transactionId, eventType,
                e.getErrorMessage(), e.getRetryCount(),
                e.getCreatedAt(), e.getResolvedAt(), e.getResolvedBy()
        );
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) return "";
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "…";
    }
}
