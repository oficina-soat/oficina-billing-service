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
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RegistrarPagamentoUseCase {
    private final PagamentoRepositoryGateway pagamentoRepository;
    private final OrcamentoRepositoryGateway orcamentoRepository;
    private final OutboxEventSender outboxEventSender;
    private final PagamentoGateway pagamentoGateway;
    private final Clock clock;

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
        this.pagamentoRepository = pagamentoRepository;
        this.orcamentoRepository = orcamentoRepository;
        this.outboxEventSender = outboxEventSender;
        this.pagamentoGateway = pagamentoGateway;
        this.clock = clock;
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
                    return pagamentoGateway.solicitar(pagamento)
                            .thenCompose(resultadoGateway -> pagamentoRepository.createIfAbsent(pagamento)
                                    .thenCompose(resultadoCriacao -> concluirCriacao(
                                            resultadoCriacao, resultadoGateway, identity, now)));
                });
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
                        resultado.transacaoExternaId())
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
}
