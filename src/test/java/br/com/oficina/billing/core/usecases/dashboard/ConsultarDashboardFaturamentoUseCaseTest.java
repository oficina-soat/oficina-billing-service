package br.com.oficina.billing.core.usecases.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ConsultarDashboardFaturamentoUseCaseTest {
    @Test
    void agregaAutoridadesFinanceirasESelecionaAtencoes() {
        var orcamentos = mock(OrcamentoRepositoryGateway.class);
        var pagamentos = mock(PagamentoRepositoryGateway.class);
        var budget = mock(Orcamento.class);
        var payment = mock(Pagamento.class);
        var now = OffsetDateTime.parse("2026-07-17T18:30:00Z");
        when(budget.status()).thenReturn(StatusOrcamento.GERADO);
        when(budget.ordemServicoId()).thenReturn(UUID.randomUUID());
        when(budget.orcamentoId()).thenReturn(UUID.randomUUID());
        when(budget.valorTotal()).thenReturn(BigDecimal.TEN);
        when(budget.atualizadoEm()).thenReturn(now.minusHours(2));
        when(budget.acoesPermitidas()).thenReturn(List.of());
        when(payment.status()).thenReturn(StatusPagamento.CRIADO);
        when(payment.ordemServicoId()).thenReturn(UUID.randomUUID());
        when(payment.pagamentoId()).thenReturn(UUID.randomUUID());
        when(payment.valor()).thenReturn(BigDecimal.ONE);
        when(payment.atualizadoEm()).thenReturn(now.minusHours(1));
        when(payment.acoesPermitidas()).thenReturn(List.of());
        when(orcamentos.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(budget)));
        when(pagamentos.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(payment)));
        var useCase = new ConsultarDashboardFaturamentoUseCase(
                orcamentos, pagamentos, Clock.fixed(Instant.from(now), ZoneOffset.UTC));

        var result = useCase.executar().join();

        assertEquals(2, result.atencoes().size());
        assertEquals("ORCAMENTO", result.atencoes().getFirst().tipo());
        assertEquals(1, result.contagensOrcamentos().stream()
                .filter(item -> item.status() == StatusOrcamento.GERADO).findFirst().orElseThrow().quantidade());
        assertEquals(1, result.contagensPagamentos().stream()
                .filter(item -> item.status() == StatusPagamento.CRIADO).findFirst().orElseThrow().quantidade());
    }

    @Test
    void limitaEOrdenaAtencoesIgnorandoEstadosConcluidos() {
        var orcamentos = mock(OrcamentoRepositoryGateway.class);
        var pagamentos = mock(PagamentoRepositoryGateway.class);
        var now = OffsetDateTime.parse("2026-07-17T18:30:00Z");
        var budgets = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> budget(StatusOrcamento.GERADO, now.minusHours(6 - index)))
                .toList();
        var approved = budget(StatusOrcamento.APROVADO, now);
        var refused = payment(StatusPagamento.RECUSADO, now.minusDays(1));
        var confirmed = payment(StatusPagamento.CONFIRMADO, now);
        when(orcamentos.findAll()).thenReturn(CompletableFuture.completedFuture(
                java.util.stream.Stream.concat(budgets.stream(), java.util.stream.Stream.of(approved)).toList()));
        when(pagamentos.findAll()).thenReturn(CompletableFuture.completedFuture(List.of(refused, confirmed)));

        var result = new ConsultarDashboardFaturamentoUseCase(orcamentos, pagamentos).executar().join();

        assertEquals(5, result.atencoes().size());
        assertEquals("PAGAMENTO", result.atencoes().getFirst().tipo());
        assertEquals("RECUSADO", result.atencoes().getFirst().status());
        assertEquals(1, result.contagensOrcamentos().stream()
                .filter(item -> item.status() == StatusOrcamento.APROVADO).findFirst().orElseThrow().quantidade());
        assertEquals(1, result.contagensPagamentos().stream()
                .filter(item -> item.status() == StatusPagamento.CONFIRMADO).findFirst().orElseThrow().quantidade());
    }

    private Orcamento budget(StatusOrcamento status, OffsetDateTime updatedAt) {
        var item = mock(Orcamento.class);
        when(item.status()).thenReturn(status);
        when(item.ordemServicoId()).thenReturn(UUID.randomUUID());
        when(item.orcamentoId()).thenReturn(UUID.randomUUID());
        when(item.valorTotal()).thenReturn(BigDecimal.TEN);
        when(item.atualizadoEm()).thenReturn(updatedAt);
        when(item.acoesPermitidas()).thenReturn(List.of());
        return item;
    }

    private Pagamento payment(StatusPagamento status, OffsetDateTime updatedAt) {
        var item = mock(Pagamento.class);
        when(item.status()).thenReturn(status);
        when(item.ordemServicoId()).thenReturn(UUID.randomUUID());
        when(item.pagamentoId()).thenReturn(UUID.randomUUID());
        when(item.valor()).thenReturn(BigDecimal.ONE);
        when(item.atualizadoEm()).thenReturn(updatedAt);
        when(item.acoesPermitidas()).thenReturn(List.of());
        return item;
    }
}
