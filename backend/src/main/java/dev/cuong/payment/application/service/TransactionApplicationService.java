package dev.cuong.payment.application.service;

import dev.cuong.payment.application.dto.CreateTransactionCommand;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.application.port.in.CreateTransactionUseCase;
import dev.cuong.payment.application.port.out.AccountRepository;
import dev.cuong.payment.application.port.out.TransactionRepository;
import dev.cuong.payment.domain.exception.AccountNotFoundException;
import dev.cuong.payment.domain.model.Account;
import dev.cuong.payment.domain.model.Transaction;
import dev.cuong.payment.domain.vo.TransactionStatus;
import dev.cuong.payment.domain.vo.TransactionType;
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

        // Pessimistic lock: prevents concurrent balance updates for the same account
        Account account = accountRepository.findByUserIdForUpdate(command.userId())
                .orElseThrow(() -> new AccountNotFoundException(command.userId()));

        // DEPOSIT credits the account; all other types debit (throws if insufficient funds)
        if (command.type() == TransactionType.DEPOSIT) {
            account.credit(command.amount());
        } else {
            account.debit(command.amount());
        }

        Instant now = Instant.now();
        Transaction transaction = Transaction.builder()
                .userId(command.userId())
                .accountId(account.getId())
                .amount(command.amount())
                .currency(account.getCurrency())
                .type(command.type())
                .status(TransactionStatus.PENDING)
                .description(command.description())
                .idempotencyKey(command.idempotencyKey())
                .createdAt(now)
                .updatedAt(now)
                .build();

        accountRepository.save(account);
        Transaction saved = transactionRepository.save(transaction);

        log.info("Transaction created: transactionId={}, userId={}, amount={}, type={}, status={}",
                saved.getId(), command.userId(), command.amount(), command.type(), saved.getStatus());

        return toResult(saved);
    }

    private TransactionResult toResult(Transaction tx) {
        return new TransactionResult(
                tx.getId(),
                tx.getUserId(),
                tx.getAccountId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getType().name(),
                tx.getStatus().name(),
                tx.getDescription(),
                tx.getIdempotencyKey(),
                tx.getGatewayReference(),
                tx.getFailureReason(),
                tx.getProcessedAt(),
                tx.getRefundedAt(),
                tx.getCreatedAt(),
                tx.getUpdatedAt());
    }
}
