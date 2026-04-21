package dev.cuong.payment.infrastructure.persistence.adapter;

import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.infrastructure.persistence.mapper.TransactionMapper;
import dev.cuong.payment.infrastructure.persistence.repository.TransactionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TransactionRepositoryAdapter implements TransactionRepository {

    private final TransactionJpaRepository jpaRepository;

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jpaRepository.findById(id).map(TransactionMapper::toDomain);
    }

    @Override
    public Optional<Transaction> findByIdAndUserId(UUID id, UUID userId) {
        return jpaRepository.findByIdAndUserId(id, userId).map(TransactionMapper::toDomain);
    }

    @Override
    public List<Transaction> findByUserId(UUID userId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return jpaRepository.findByUserId(userId, pageable)
                .map(TransactionMapper::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(UUID userId) {
        return jpaRepository.countByUserId(userId);
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(TransactionMapper::toDomain);
    }

    @Override
    public Transaction save(Transaction transaction) {
        return TransactionMapper.toDomain(jpaRepository.save(TransactionMapper.toEntity(transaction)));
    }
}
