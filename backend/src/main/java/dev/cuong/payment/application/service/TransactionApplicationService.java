package dev.cuong.payment.application.service;

import dev.cuong.payment.application.dto.CreateTransactionCommand;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.application.port.in.CreateTransactionUseCase;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.exception.AccountNotFoundException;
import dev.cuong.payment.domain.exception.SameAccountTransferException;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionApplicationService implements CreateTransactionUseCase {

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
