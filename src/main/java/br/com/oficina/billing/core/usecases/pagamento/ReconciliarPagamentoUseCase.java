package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.InstrucoesPix;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ReconciliarPagamentoUseCase {
    private final PagamentoRepositoryGateway repository;
    private final PagamentoGateway gateway;
    private final OutboxEventSender outboxEventSender;
    private final Clock clock;

    public ReconciliarPagamentoUseCase(
            PagamentoRepositoryGateway repository,
            PagamentoGateway gateway,
            OutboxEventSender outboxEventSender) {
        this(repository, gateway, outboxEventSender, Clock.systemUTC());
    }

    ReconciliarPagamentoUseCase(
            PagamentoRepositoryGateway repository,
            PagamentoGateway gateway,
            OutboxEventSender outboxEventSender,
            Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.outboxEventSender = outboxEventSender;
        this.clock = clock;
    }

    public CompletableFuture<Pagamento> executar(Command command) {
        return repository.findById(command.pagamentoId())
                .thenCompose(optional -> reconciliar(optional.orElseThrow(() ->
                        new ResourceNotFoundException("Pagamento nao encontrado."))));
    }

    public CompletableFuture<Pagamento> executarPorTransacaoExternaId(String transacaoExternaId) {
        return repository.findByTransacaoExternaId(transacaoExternaId)
                .thenCompose(optional -> reconciliar(optional.orElseThrow(() ->
                        new ResourceNotFoundException("Pagamento do Mercado Pago nao encontrado."))));
    }

    private CompletableFuture<Pagamento> reconciliar(Pagamento pagamento) {
        if (pagamento.status() != StatusPagamento.CRIADO) {
            return registrarEventoTerminal(pagamento, null);
        }
        if (pagamento.provedor() == null
                || pagamento.provedor().isBlank()
                || pagamento.transacaoExternaId() == null
                || pagamento.transacaoExternaId().isBlank()) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Somente pagamentos integrados pendentes podem ser reconciliados.");
        }
        return gateway.consultar(pagamento)
                .thenCompose(resultado -> aplicar(pagamento, resultado));
    }

    private CompletableFuture<Pagamento> aplicar(Pagamento pagamento, PagamentoGatewayResult resultado) {
        validarResultado(pagamento, resultado);
        var atualizado = new Pagamento(
                pagamento.pagamentoId(),
                pagamento.ordemServicoId(),
                pagamento.orcamentoId(),
                pagamento.valor(),
                pagamento.metodo(),
                resultado.status(),
                resultado.provedor(),
                resultado.transacaoExternaId(),
                instrucoes(resultado.instrucoesPix(), pagamento.instrucoesPix()),
                pagamento.criadoEm(),
                OffsetDateTime.now(clock));
        return repository.updateIfStatus(atualizado, StatusPagamento.CRIADO)
                .thenCompose(update -> registrarEventoTerminal(update.pagamento(), resultado));
    }

    private void validarResultado(Pagamento pagamento, PagamentoGatewayResult resultado) {
        if (resultado == null
                || !resultado.integrado()
                || !pagamento.provedor().equals(resultado.provedor())
                || !pagamento.transacaoExternaId().equals(resultado.transacaoExternaId())) {
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Resposta de reconciliacao inconsistente com o pagamento registrado.");
        }
    }

    private InstrucoesPix instrucoes(InstrucoesPix atualizadas, InstrucoesPix existentes) {
        return atualizadas == null ? existentes : atualizadas;
    }

    private CompletableFuture<Pagamento> registrarEventoTerminal(
            Pagamento pagamento,
            PagamentoGatewayResult resultado) {
        if (resultado != null && resultado.status() == StatusPagamento.CONFIRMADO) {
            return registrarEventoIdempotente(
                    pagamento,
                    PagamentoEventPayloads.PAGAMENTO_CONFIRMADO,
                    resultado.provedor(),
                    null);
        }
        if (pagamento.status() == StatusPagamento.CONFIRMADO) {
            return registrarEventoIdempotente(
                    pagamento,
                    PagamentoEventPayloads.PAGAMENTO_CONFIRMADO,
                    pagamento.provedor(),
                    null);
        }
        if (pagamento.status() == StatusPagamento.RECUSADO) {
            return registrarEventoIdempotente(
                    pagamento,
                    PagamentoEventPayloads.PAGAMENTO_RECUSADO,
                    resultado == null ? pagamento.provedor() : resultado.provedor(),
                    resultado == null ? null : resultado.motivo());
        }
        return CompletableFuture.completedFuture(pagamento);
    }

    private CompletableFuture<Pagamento> registrarEventoIdempotente(
            Pagamento pagamento,
            PagamentoEventPayloads.Evento evento,
            String provedor,
            String motivo) {
        var eventId = UUID.nameUUIDFromBytes(
                (pagamento.pagamentoId() + ":" + evento.eventType()).getBytes(StandardCharsets.UTF_8));
        return PagamentoEventPayloads.registrarEventoIdempotente(eventId, new PagamentoEventPayloads.Registro(
                    outboxEventSender,
                    pagamento,
                    evento,
                    pagamento.atualizadoEm(),
                    provedor,
                    motivo)).thenApply(ignored -> pagamento);
    }

    public record Command(UUID pagamentoId) {
    }
}
