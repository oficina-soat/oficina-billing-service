package br.com.oficina.billing.core.usecases.orcamento;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.interfaces.gateway.FinanceiroSnapshotGateway;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import br.com.oficina.billing.core.interfaces.sender.OrcamentoApprovalSender;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class GerarOrcamentoUseCase {
    private final OrcamentoRepositoryGateway repository;
    private final FinanceiroSnapshotGateway financeiroSnapshotGateway;
    private final OutboxEventSender outboxEventSender;
    private final OrcamentoApprovalSender approvalSender;
    private final Clock clock;

    public GerarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            FinanceiroSnapshotGateway financeiroSnapshotGateway,
            OutboxEventSender outboxEventSender) {
        this(repository, financeiroSnapshotGateway, outboxEventSender, OrcamentoApprovalSender.noop(), Clock.systemUTC());
    }

    public GerarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            FinanceiroSnapshotGateway financeiroSnapshotGateway,
            OutboxEventSender outboxEventSender,
            OrcamentoApprovalSender approvalSender) {
        this(repository, financeiroSnapshotGateway, outboxEventSender, approvalSender, Clock.systemUTC());
    }

    GerarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            FinanceiroSnapshotGateway financeiroSnapshotGateway,
            OutboxEventSender outboxEventSender,
            OrcamentoApprovalSender approvalSender,
            Clock clock) {
        this.repository = repository;
        this.financeiroSnapshotGateway = financeiroSnapshotGateway;
        this.outboxEventSender = outboxEventSender;
        this.approvalSender = approvalSender;
        this.clock = clock;
    }

    public CompletableFuture<Orcamento> executar(Command command) {
        var now = OffsetDateTime.now(clock);
        if (command.sourceEventId() != null) {
            var orcamentoId = deterministicId("orcamento", command.sourceEventId());
            return repository.findById(orcamentoId)
                    .thenCompose(existing -> existing
                            .map(orcamento -> concluirProcessamentoDoEvento(orcamento, command, orcamento.criadoEm()))
                            .orElseGet(() -> criarOrcamento(command, orcamentoId, now)));
        }
        return criarOrcamento(command, UUID.randomUUID(), now);
    }

    private CompletableFuture<Orcamento> criarOrcamento(Command command, UUID orcamentoId, OffsetDateTime now) {
        return financeiroSnapshotGateway.snapshotFinanceiro(command.ordemServicoId())
                .thenCompose(snapshot -> {
                    var itens = snapshot.isEmpty() ? snapshotInicial() : snapshot;
                    var valorTotal = itens.stream()
                            .map(ItemOrcamento::valorTotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    var orcamento = new Orcamento(
                            orcamentoId,
                            command.ordemServicoId(),
                            itens,
                            valorTotal,
                            StatusOrcamento.GERADO,
                            now,
                            now);
                    return repository.save(orcamento);
                })
                .thenCompose(salvo -> concluirProcessamentoDoEvento(salvo, command, now));
    }

    private CompletableFuture<Orcamento> concluirProcessamentoDoEvento(
            Orcamento orcamento,
            Command command,
            OffsetDateTime ocorridoEm) {
        var outbox = command.sourceEventId() == null
                ? OrcamentoEventPayloads.registrarEvento(
                        outboxEventSender,
                        orcamento,
                        "orcamentoGerado",
                        "oficina.billing.orcamento-gerado",
                        "geradoEm",
                        ocorridoEm,
                        null)
                : OrcamentoEventPayloads.registrarEventoIdempotente(
                        outboxEventSender,
                        deterministicId("orcamentoGerado", command.sourceEventId()),
                        orcamento,
                        "orcamentoGerado",
                        "oficina.billing.orcamento-gerado",
                        "geradoEm",
                        ocorridoEm,
                        command.correlationId());
        return outbox
                .thenCompose(ignored -> orcamento.status() == StatusOrcamento.GERADO
                        ? approvalSender.enviar(orcamento)
                        : CompletableFuture.completedFuture(null))
                .thenApply(ignored -> orcamento);
    }

    private UUID deterministicId(String purpose, UUID sourceEventId) {
        return UUID.nameUUIDFromBytes(
                ("diagnosticoFinalizado:" + purpose + ":" + sourceEventId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private List<ItemOrcamento> snapshotInicial() {
        return List.of(new ItemOrcamento(
                TipoItemOrcamento.SERVICO,
                UUID.randomUUID(),
                null,
                "Snapshot financeiro inicial",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    }

    public record Command(UUID ordemServicoId, String correlationId, UUID sourceEventId) {
        public Command(UUID ordemServicoId) {
            this(ordemServicoId, null, null);
        }

        public Command(UUID ordemServicoId, String correlationId) {
            this(ordemServicoId, correlationId, null);
        }
    }
}
