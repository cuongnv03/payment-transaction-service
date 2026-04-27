package dev.cuong.payment.infrastructure.persistence.adapter;

import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
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
    public Optional<Transaction> findByIdAndFromAccountId(UUID id, UUID fromAccountId) {
        return jpaRepository.findByIdAndFromAccountId(id, fromAccountId).map(TransactionMapper::toDomain);
    }

    @Override
    public List<Transaction> findByFromAccountId(UUID fromAccountId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return jpaRepository.findByFromAccountId(fromAccountId, pageable)
                .map(TransactionMapper::toDomain)
                .toList();
    }

    @Override
    public List<Transaction> findByFromAccountIdAndStatus(UUID fromAccountId, TransactionStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return jpaRepository.findByFromAccountIdAndStatus(fromAccountId, status, pageable)
                .map(TransactionMapper::toDomain)
                .toList();
    }

    @Override
    public long countByFromAccountId(UUID fromAccountId) {
        return jpaRepository.countByFromAccountId(fromAccountId);
    }

    @Override
    public long countByFromAccountIdAndStatus(UUID fromAccountId, TransactionStatus status) {
        return jpaRepository.countByFromAccountIdAndStatus(fromAccountId, status);
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(TransactionMapper::toDomain);
    }

    @Override
    public Transaction save(Transaction transaction) {
        return TransactionMapper.toDomain(jpaRepository.save(TransactionMapper.toEntity(transaction)));
    }

    @Override
    public List<Transaction> findAllTransactions(int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return jpaRepository.findAll(pageable)
                .map(TransactionMapper::toDomain)
                .toList();
    }

    @Override
    public List<Transaction> findAllTransactionsByStatus(TransactionStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return jpaRepository.findByStatus(status, pageable)
                .map(TransactionMapper::toDomain)
                .toList();
    }

    @Override
    public long countAllTransactions() {
        return jpaRepository.count();
    }

    @Override
    public long countAllTransactionsByStatus(TransactionStatus status) {
        return jpaRepository.countByStatus(status);
    }
}
