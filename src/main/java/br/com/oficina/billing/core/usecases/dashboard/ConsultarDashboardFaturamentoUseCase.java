package br.com.oficina.billing.core.usecases.dashboard;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ConsultarDashboardFaturamentoUseCase {
    private final OrcamentoRepositoryGateway orcamentos;
    private final PagamentoRepositoryGateway pagamentos;
    private final Clock clock;

    public ConsultarDashboardFaturamentoUseCase(
            OrcamentoRepositoryGateway orcamentos,
            PagamentoRepositoryGateway pagamentos) {
        this(orcamentos, pagamentos, Clock.systemUTC());
    }

    ConsultarDashboardFaturamentoUseCase(
            OrcamentoRepositoryGateway orcamentos,
            PagamentoRepositoryGateway pagamentos,
            Clock clock) {
        this.orcamentos = orcamentos;
        this.pagamentos = pagamentos;
        this.clock = clock;
    }

    public CompletableFuture<DashboardFaturamento> executar() {
        return orcamentos.findAll().thenCombine(pagamentos.findAll(), (budgets, payments) -> {
            var now = OffsetDateTime.now(clock);
            var atencoes = java.util.stream.Stream.concat(
                            budgets.stream().filter(item -> item.status() == StatusOrcamento.GERADO).map(this::atencao),
                            payments.stream()
                                    .filter(item -> item.status() == StatusPagamento.CRIADO
                                            || item.status() == StatusPagamento.RECUSADO)
                                    .map(this::atencao))
                    .sorted(Comparator.comparing(FaturamentoAtencao::atualizadoEm))
                    .limit(5)
                    .toList();
            return new DashboardFaturamento(
                    now,
                    now,
                    30,
                    Arrays.stream(StatusOrcamento.values())
                            .map(status -> new ContagemOrcamento(status, budgets.stream().filter(o -> o.status() == status).count()))
                            .toList(),
                    Arrays.stream(StatusPagamento.values())
                            .map(status -> new ContagemPagamento(status, payments.stream().filter(p -> p.status() == status).count()))
                            .toList(),
                    atencoes);
        });
    }

    private FaturamentoAtencao atencao(Orcamento item) {
        return new FaturamentoAtencao("ORCAMENTO", item.ordemServicoId(), item.orcamentoId(),
                item.status().name(), item.valorTotal(), item.atualizadoEm(),
                item.acoesPermitidas().stream().map(Enum::name).toList());
    }

    private FaturamentoAtencao atencao(Pagamento item) {
        return new FaturamentoAtencao("PAGAMENTO", item.ordemServicoId(), item.pagamentoId(),
                item.status().name(), item.valor(), item.atualizadoEm(),
                item.acoesPermitidas().stream().map(Enum::name).toList());
    }

    public record DashboardFaturamento(
            OffsetDateTime generatedAt,
            OffsetDateTime dataAsOf,
            int refreshAfterSeconds,
            List<ContagemOrcamento> contagensOrcamentos,
            List<ContagemPagamento> contagensPagamentos,
            List<FaturamentoAtencao> atencoes) {
    }

    public record ContagemOrcamento(StatusOrcamento status, long quantidade) {
    }

    public record ContagemPagamento(StatusPagamento status, long quantidade) {
    }

    public record FaturamentoAtencao(
            String tipo,
            UUID ordemServicoId,
            UUID referenciaId,
            String status,
            BigDecimal valor,
            OffsetDateTime atualizadoEm,
            List<String> acoesPermitidas) {
    }
}
