package br.com.oficina.billing.core.usecases.orcamento;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.interfaces.gateway.FinanceiroSnapshotGateway;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class GerarOrcamentoUseCase {
    private final OrcamentoRepositoryGateway repository;
    private final FinanceiroSnapshotGateway financeiroSnapshotGateway;
    private final OutboxEventSender outboxEventSender;
    private final Clock clock;

    public GerarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            FinanceiroSnapshotGateway financeiroSnapshotGateway,
            OutboxEventSender outboxEventSender) {
        this(repository, financeiroSnapshotGateway, outboxEventSender, Clock.systemUTC());
    }

    GerarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            FinanceiroSnapshotGateway financeiroSnapshotGateway,
            OutboxEventSender outboxEventSender,
            Clock clock) {
        this.repository = repository;
        this.financeiroSnapshotGateway = financeiroSnapshotGateway;
        this.outboxEventSender = outboxEventSender;
        this.clock = clock;
    }

    public CompletableFuture<Orcamento> executar(Command command) {
        var now = OffsetDateTime.now(clock);
        return financeiroSnapshotGateway.snapshotFinanceiro(command.ordemServicoId())
                .thenCompose(snapshot -> {
                    var itens = snapshot.isEmpty() ? snapshotInicial() : snapshot;
                    var valorTotal = itens.stream()
                            .map(ItemOrcamento::valorTotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    var orcamento = new Orcamento(
                            UUID.randomUUID(),
                            command.ordemServicoId(),
                            itens,
                            valorTotal,
                            StatusOrcamento.GERADO,
                            now,
                            now);
                    return repository.save(orcamento);
                })
                .thenCompose(salvo -> OrcamentoEventPayloads.registrarEvento(
                        outboxEventSender,
                        salvo,
                        "orcamentoGerado",
                        "oficina.billing.orcamento-gerado",
                        "geradoEm",
                        now,
                        command.correlationId())
                        .thenApply(ignored -> salvo));
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

    public record Command(UUID ordemServicoId, String correlationId) {
        public Command(UUID ordemServicoId) {
            this(ordemServicoId, null);
        }
    }
}
