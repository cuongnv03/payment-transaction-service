package dev.cuong.payment.application.service;

import dev.cuong.payment.application.dto.CreateTransactionCommand;
import dev.cuong.payment.application.dto.PagedResult;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.application.port.in.CreateTransactionUseCase;
import dev.cuong.payment.application.port.in.GetTransactionUseCase;
import dev.cuong.payment.application.port.in.RefundTransactionUseCase;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.exception.AccountNotFoundException;
import dev.cuong.payment.domain.exception.SameAccountTransferException;
import dev.cuong.payment.domain.exception.TransactionNotFoundException;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionApplicationService implements CreateTransactionUseCase, GetTransactionUseCase, RefundTransactionUseCase {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    @Override
    @Transactional
    public TransactionResult createTransaction(CreateTransactionCommand command) {
        // Idempotency: return cached result if this key was already processed
        Optional<Transaction> existing =
                transactionRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay: key={}, transactionId={}",
                    command.idempotencyKey(), existing.get().getId());
            return toResult(existing.get());
        }

        // Pessimistic lock on sender's account — serialises concurrent balance updates
        Account fromAccount = accountRepository.findByUserIdForUpdate(command.userId())
                .orElseThrow(() -> new AccountNotFoundException(command.userId()));

        // Load receiver's account (no lock — we only credit it on SUCCESS in the consumer)
        Account toAccount = accountRepository.findById(command.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(command.toAccountId()));

        // Fail fast before hitting the DB diff_accounts constraint
        if (fromAccount.getId().equals(toAccount.getId())) {
            throw new SameAccountTransferException(fromAccount.getId());
        }

        // Hold the funds immediately — restored on FAILED/TIMEOUT by the processing consumer
        fromAccount.debit(command.amount());

        Instant now = Instant.now();
        Transaction transaction = Transaction.builder()
                .fromAccountId(fromAccount.getId())
                .toAccountId(toAccount.getId())
                .amount(command.amount())
                .currency(fromAccount.getCurrency())
                .status(TransactionStatus.PENDING)
                .description(command.description())
                .idempotencyKey(command.idempotencyKey())
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        accountRepository.save(fromAccount);
        Transaction saved = transactionRepository.save(transaction);

        log.info("Transaction created: transactionId={}, fromAccountId={}, toAccountId={}, amount={}",
                saved.getId(), fromAccount.getId(), toAccount.getId(), command.amount());

        return toResult(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<TransactionResult> getMyTransactions(UUID userId, TransactionStatus status, int page, int size) {
        UUID fromAccountId = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId))
                .getId();

        List<Transaction> txs;
        long total;
        if (status != null) {
            txs = transactionRepository.findByFromAccountIdAndStatus(fromAccountId, status, page, size);
            total = transactionRepository.countByFromAccountIdAndStatus(fromAccountId, status);
        } else {
            txs = transactionRepository.findByFromAccountId(fromAccountId, page, size);
            total = transactionRepository.countByFromAccountId(fromAccountId);
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        log.debug("Fetched transactions: userId={}, status={}, page={}, size={}, total={}",
                userId, status, page, size, total);

        return new PagedResult<>(txs.stream().map(this::toResult).toList(), page, size, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResult getMyTransaction(UUID userId, UUID transactionId) {
        UUID fromAccountId = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId))
                .getId();

        return transactionRepository.findByIdAndFromAccountId(transactionId, fromAccountId)
                .map(this::toResult)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    @Override
    @Transactional
    public TransactionResult refundTransaction(UUID userId, UUID transactionId) {
        UUID fromAccountId = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId))
                .getId();

        // Scoped load — 404 if the transaction belongs to a different account
        Transaction transaction = transactionRepository.findByIdAndFromAccountId(transactionId, fromAccountId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        // State machine validates SUCCESS → REFUNDED; throws InvalidTransactionStateException otherwise
        transaction.refund();

        // Pessimistic lock on the account for the credit — prevents concurrent balance corruption
        Account fromAccount = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        fromAccount.credit(transaction.getAmount());

        transactionRepository.save(transaction);
        accountRepository.save(fromAccount);

        log.info("Refund processed: transactionId={}, fromAccountId={}, amount={}",
                transactionId, fromAccountId, transaction.getAmount());

        return toResult(transaction);
    }

    private TransactionResult toResult(Transaction tx) {
        return new TransactionResult(
                tx.getId(),
                tx.getFromAccountId(),
                tx.getToAccountId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus().name(),
                tx.getDescription(),
                tx.getIdempotencyKey(),
                tx.getGatewayReference(),
                tx.getFailureReason(),
                tx.getRetryCount(),
                tx.getProcessedAt(),
                tx.getRefundedAt(),
                tx.getCreatedAt(),
                tx.getUpdatedAt());
    }
}
