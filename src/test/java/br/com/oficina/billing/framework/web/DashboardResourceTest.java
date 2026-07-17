package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.oficina.billing.core.usecases.dashboard.ConsultarDashboardFaturamentoUseCase;
import br.com.oficina.billing.core.usecases.dashboard.ConsultarDashboardFaturamentoUseCase.DashboardFaturamento;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DashboardResourceTest {
    @Test
    void delegaSnapshotAoCasoDeUso() {
        var useCase = mock(ConsultarDashboardFaturamentoUseCase.class);
        var expected = mock(DashboardFaturamento.class);
        when(useCase.executar()).thenReturn(CompletableFuture.completedFuture(expected));

        var result = new DashboardResource(useCase).consultar().await().indefinitely();

        assertSame(expected, result);
    }
}
