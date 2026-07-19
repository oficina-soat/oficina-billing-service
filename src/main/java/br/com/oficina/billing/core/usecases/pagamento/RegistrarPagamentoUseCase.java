package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class RegistrarPagamentoUseCase {
    private static final Duration DEFAULT_PROVIDER_CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration DEFAULT_COORDINATION_POLL_INTERVAL = Duration.ofMillis(25);
    private static final int DEFAULT_COORDINATION_ATTEMPTS = 240;

    private final PagamentoRepositoryGateway pagamentoRepository;
    private final OrcamentoRepositoryGateway orcamentoRepository;
    private final OutboxEventSender outboxEventSender;
    private final PagamentoGateway pagamentoGateway;
    private final Clock clock;
    private final Duration providerClaimLease;
    private final Duration coordinationPollInterval;
    private final int coordinationAttempts;

    public RegistrarPagamentoUseCase(
            PagamentoRepositoryGateway pagamentoRepository,
            OrcamentoRepositoryGateway orcamentoRepository,
            OutboxEventSender outboxEventSender,
            PagamentoGateway pagamentoGateway) {
        this(pagamentoRepository, orcamentoRepository, outboxEventSender, pagamentoGateway, Clock.systemUTC());
    }

    RegistrarPagamentoUseCase(
            PagamentoRepositoryGateway pagamentoRepository,
            OrcamentoRepositoryGateway orcamentoRepository,
            OutboxEventSender outboxEventSender,
            PagamentoGateway pagamentoGateway,
            Clock clock) {
        this(
                pagamentoRepository,
                orcamentoRepository,
                outboxEventSender,
                pagamentoGateway,
                clock,
                DEFAULT_PROVIDER_CLAIM_LEASE,
                DEFAULT_COORDINATION_POLL_INTERVAL,
                DEFAULT_COORDINATION_ATTEMPTS);
    }

    RegistrarPagamentoUseCase(
            PagamentoRepositoryGateway pagamentoRepository,
            OrcamentoRepositoryGateway orcamentoRepository,
            OutboxEventSender outboxEventSender,
            PagamentoGateway pagamentoGateway,
            Clock clock,
            Duration providerClaimLease,
            Duration coordinationPollInterval,
            int coordinationAttempts) {
        this.pagamentoRepository = pagamentoRepository;
        this.orcamentoRepository = orcamentoRepository;
        this.outboxEventSender = outboxEventSender;
        this.pagamentoGateway = pagamentoGateway;
        this.clock = clock;
        this.providerClaimLease = positive(providerClaimLease, "providerClaimLease");
        this.coordinationPollInterval = positive(coordinationPollInterval, "coordinationPollInterval");
        if (coordinationAttempts < 1) {
            throw new IllegalArgumentException("coordinationAttempts deve ser positivo.");
        }
        this.coordinationAttempts = coordinationAttempts;
    }

    public CompletableFuture<Pagamento> executar(Command command) {
        return executar(command, null);
    }

    CompletableFuture<Pagamento> executarIdempotente(Command command, UUID idempotencyReferenceId) {
        return executar(command, new IdempotentIdentity(
                deterministicId("pagamento", idempotencyReferenceId),
                deterministicId("pagamentoSolicitado", idempotencyReferenceId)));
    }

    private CompletableFuture<Pagamento> executar(Command command, IdempotentIdentity identity) {
        return pagamentoRepository.findByOrcamentoId(command.orcamentoId())
                .thenCompose(existente -> {
                    if (existente.isPresent()) {
                        return pagamentoExistente(existente.orElseThrow(), identity);
                    }
                    return criarPagamento(command, identity);
                });
    }

    private CompletableFuture<Pagamento> criarPagamento(Command command, IdempotentIdentity identity) {
        return orcamentoRepository.findById(command.orcamentoId())
                .thenCompose(optional -> {
                    var orcamento = optional.orElseThrow(() ->
                            new ResourceNotFoundException("Orcamento nao encontrado."));
                    if (!orcamento.ordemServicoId().equals(command.ordemServicoId())) {
                        throw new BusinessException(
                                "BUSINESS_RULE_VIOLATION",
                                "Orcamento nao pertence a ordem de servico informada.");
                    }
                    if (orcamento.status() != StatusOrcamento.APROVADO) {
                        throw new BusinessException(
                                "INVALID_STATE_TRANSITION",
                                "Somente orcamentos aprovados podem receber pagamento.");
                    }
                    if (command.valor().compareTo(BigDecimal.ZERO) < 0) {
                        throw new BusinessException("VALIDATION_ERROR", "Valor do pagamento nao pode ser negativo.");
                    }

                    var now = OffsetDateTime.now(clock);
                    var pagamento = new Pagamento(
                            identity == null ? UUID.randomUUID() : identity.pagamentoId(),
                            command.ordemServicoId(),
                            command.orcamentoId(),
                            command.valor(),
                            command.metodo(),
                            StatusPagamento.CRIADO,
                            null,
                            null,
                            now,
                            now);
                    return solicitarComOwnership(
                            pagamento,
                            identity,
                            now,
                            UUID.randomUUID(),
                            coordinationAttempts);
                });
    }

    private CompletableFuture<Pagamento> solicitarComOwnership(
            Pagamento pagamento,
            IdempotentIdentity identity,
            OffsetDateTime ocorridoEm,
            UUID ownerId,
            int remainingAttempts) {
        return pagamentoRepository.findByOrcamentoId(pagamento.orcamentoId())
                .thenCompose(existing -> {
                    if (existing.isPresent()) {
                        return pagamentoExistente(existing.orElseThrow(), identity);
                    }
                    var claimedAt = OffsetDateTime.now(clock);
                    return pagamentoRepository.claimProviderRequest(
                                    pagamento.orcamentoId(),
                                    ownerId,
                                    claimedAt,
                                    claimedAt.plus(providerClaimLease))
                            .thenCompose(claimed -> {
                                if (claimed) {
                                    return solicitarComoOwner(pagamento, identity, ocorridoEm, ownerId);
                                }
                                if (remainingAttempts <= 1) {
                                    return CompletableFuture.failedFuture(providerRequestInProgress());
                                }
                                return delay().thenCompose(ignored -> solicitarComOwnership(
                                        pagamento,
                                        identity,
                                        ocorridoEm,
                                        ownerId,
                                        remainingAttempts - 1));
                            });
                });
    }

    private CompletableFuture<Pagamento> solicitarComoOwner(
            Pagamento pagamento,
            IdempotentIdentity identity,
            OffsetDateTime ocorridoEm,
            UUID ownerId) {
        var request = solicitarAoGatewayEPersistir(pagamento, identity, ocorridoEm);
        return request.handle(ProviderRequestOutcome::new)
                .thenCompose(outcome -> releaseProviderClaim(pagamento.orcamentoId(), ownerId, outcome))
                .thenCompose(outcome -> outcome.failure() == null
                        ? CompletableFuture.completedFuture(outcome.pagamento())
                        : awaitConcurrentPayment(
                                pagamento.orcamentoId(),
                                identity,
                                unwrap(outcome.failure()),
                                coordinationAttempts));
    }

    private CompletableFuture<Pagamento> solicitarAoGatewayEPersistir(
            Pagamento pagamento,
            IdempotentIdentity identity,
            OffsetDateTime ocorridoEm) {
        try {
            return pagamentoGateway.solicitar(pagamento)
                    .thenCompose(resultadoGateway -> pagamentoRepository.createIfAbsent(pagamento)
                            .thenCompose(resultadoCriacao -> concluirCriacao(
                                    resultadoCriacao, resultadoGateway, identity, ocorridoEm)));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<ProviderRequestOutcome> releaseProviderClaim(
            UUID orcamentoId,
            UUID ownerId,
            ProviderRequestOutcome outcome) {
        CompletableFuture<Void> release;
        try {
            release = pagamentoRepository.releaseProviderRequest(orcamentoId, ownerId);
        } catch (RuntimeException failure) {
            release = CompletableFuture.failedFuture(failure);
        }
        return release.handle((ignored, releaseFailure) -> mergeReleaseFailure(outcome, releaseFailure));
    }

    private ProviderRequestOutcome mergeReleaseFailure(
            ProviderRequestOutcome outcome,
            Throwable releaseFailure) {
        if (releaseFailure == null) {
            return outcome;
        }
        var unwrappedReleaseFailure = unwrap(releaseFailure);
        if (outcome.failure() == null) {
            return new ProviderRequestOutcome(null, unwrappedReleaseFailure);
        }
        var originalFailure = unwrap(outcome.failure());
        originalFailure.addSuppressed(unwrappedReleaseFailure);
        return new ProviderRequestOutcome(null, originalFailure);
    }

    private CompletableFuture<Pagamento> awaitConcurrentPayment(
            UUID orcamentoId,
            IdempotentIdentity identity,
            Throwable originalFailure,
            int remainingAttempts) {
        return pagamentoRepository.findByOrcamentoId(orcamentoId)
                .thenCompose(existing -> {
                    if (existing.isPresent()) {
                        return pagamentoExistente(existing.orElseThrow(), identity);
                    }
                    if (remainingAttempts <= 1) {
                        return CompletableFuture.failedFuture(originalFailure);
                    }
                    return delay().thenCompose(ignored -> awaitConcurrentPayment(
                            orcamentoId,
                            identity,
                            originalFailure,
                            remainingAttempts - 1));
                });
    }

    private CompletableFuture<Void> delay() {
        return CompletableFuture.runAsync(
                () -> { },
                CompletableFuture.delayedExecutor(
                        coordinationPollInterval.toMillis(),
                        TimeUnit.MILLISECONDS));
    }

    private CompletableFuture<Pagamento> pagamentoExistente(Pagamento pagamento, IdempotentIdentity identity) {
        if (identity == null) {
            throw duplicatePayment();
        }
        if (!pagamento.pagamentoId().equals(identity.pagamentoId())) {
            return CompletableFuture.completedFuture(pagamento);
        }
        return registrarPagamentoSolicitado(pagamento, identity, pagamento.criadoEm())
                .thenApply(ignored -> pagamento);
    }

    private CompletableFuture<Pagamento> concluirCriacao(
            PagamentoRepositoryGateway.CreateResult resultadoCriacao,
            PagamentoGatewayResult resultadoGateway,
            IdempotentIdentity identity,
            OffsetDateTime ocorridoEm) {
        var pagamento = resultadoCriacao.pagamento();
        if (!resultadoCriacao.created()) {
            if (identity == null) {
                throw duplicatePayment();
            }
            return pagamentoExistente(pagamento, identity);
        }
        return registrarPagamentoSolicitado(pagamento, identity, ocorridoEm)
                .thenCompose(ignored -> aplicarResultadoGateway(pagamento, resultadoGateway));
    }

    private CompletableFuture<Void> registrarPagamentoSolicitado(
            Pagamento pagamento,
            IdempotentIdentity identity,
            OffsetDateTime ocorridoEm) {
        var registro = new PagamentoEventPayloads.Registro(
                outboxEventSender,
                pagamento,
                PagamentoEventPayloads.PAGAMENTO_SOLICITADO,
                ocorridoEm,
                null,
                null);
        return identity == null
                ? PagamentoEventPayloads.registrarEvento(registro)
                : PagamentoEventPayloads.registrarEventoIdempotente(identity.outboxEventId(), registro);
    }

    private BusinessException duplicatePayment() {
        return new BusinessException(
                "DUPLICATE_RESOURCE",
                "O orcamento informado ja possui pagamento registrado.");
    }

    private BusinessException providerRequestInProgress() {
        return new BusinessException(
                "DEPENDENCY_UNAVAILABLE",
                "Solicitacao de pagamento ainda esta em processamento.");
    }

    private Duration positive(Duration duration, String field) {
        Objects.requireNonNull(duration, field + " nao pode ser nulo.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " deve ser positivo.");
        }
        return duration;
    }

    private Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private UUID deterministicId(String purpose, UUID idempotencyReferenceId) {
        return UUID.nameUUIDFromBytes(
                ("pagamento:" + purpose + ":" + idempotencyReferenceId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private CompletableFuture<Pagamento> aplicarResultadoGateway(Pagamento pagamento, PagamentoGatewayResult resultado) {
        if (resultado == null || !resultado.integrado()) {
            return CompletableFuture.completedFuture(pagamento);
        }

        return PagamentoStatusUpdater.atualizarStatus(
                        pagamentoRepository,
                        clock,
                        pagamento,
                        resultado.status(),
                        resultado.provedor(),
                        resultado.transacaoExternaId(),
                        resultado.tipoReferenciaExterna(),
                        resultado.instrucoesPix())
                .thenCompose(atualizado -> registrarEventoResultadoGateway(atualizado, resultado));
    }

    private CompletableFuture<Pagamento> registrarEventoResultadoGateway(
            Pagamento pagamento,
            PagamentoGatewayResult resultado) {
        if (resultado.status() == StatusPagamento.CONFIRMADO) {
            return PagamentoEventPayloads.registrarEvento(new PagamentoEventPayloads.Registro(
                    outboxEventSender,
                    pagamento,
                    PagamentoEventPayloads.PAGAMENTO_CONFIRMADO,
                    pagamento.atualizadoEm(),
                    resultado.provedor(),
                    null))
                    .thenApply(ignored -> pagamento);
        }
        if (resultado.status() == StatusPagamento.RECUSADO) {
            return PagamentoEventPayloads.registrarEvento(new PagamentoEventPayloads.Registro(
                    outboxEventSender,
                    pagamento,
                    PagamentoEventPayloads.PAGAMENTO_RECUSADO,
                    pagamento.atualizadoEm(),
                    resultado.provedor(),
                    resultado.motivo()))
                    .thenApply(ignored -> pagamento);
        }
        return CompletableFuture.completedFuture(pagamento);
    }

    public record Command(UUID ordemServicoId, UUID orcamentoId, BigDecimal valor, MetodoPagamento metodo) {
    }

    private record IdempotentIdentity(UUID pagamentoId, UUID outboxEventId) {
    }

    private record ProviderRequestOutcome(Pagamento pagamento, Throwable failure) {
    }
}
