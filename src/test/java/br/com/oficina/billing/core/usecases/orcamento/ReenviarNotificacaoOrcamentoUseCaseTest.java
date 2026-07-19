package br.com.oficina.billing.core.usecases.orcamento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.framework.db.InMemoryOrcamentoDataSourceAdapter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReenviarNotificacaoOrcamentoUseCaseTest {
    @Test
    void deveReenviarSomenteOrcamentoGerado() {
        var repository = new InMemoryOrcamentoDataSourceAdapter();
        var orcamento = orcamento(StatusOrcamento.GERADO);
        repository.save(orcamento).join();
        var envios = new AtomicInteger();
        var useCase = new ReenviarNotificacaoOrcamentoUseCase(
                repository,
                ignored -> {
                    envios.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });

        var resultado = useCase.executar(new ReenviarNotificacaoOrcamentoUseCase.Command(
                orcamento.orcamentoId())).join();

        assertEquals(orcamento, resultado);
        assertEquals(1, envios.get());
    }

    @Test
    void deveRecusarReenvioAposDecisao() {
        var repository = new InMemoryOrcamentoDataSourceAdapter();
        var orcamento = orcamento(StatusOrcamento.APROVADO);
        repository.save(orcamento).join();
        var useCase = new ReenviarNotificacaoOrcamentoUseCase(
                repository,
                ignored -> CompletableFuture.completedFuture(null));

        var failure = assertThrows(CompletionException.class, () -> useCase.executar(
                new ReenviarNotificacaoOrcamentoUseCase.Command(orcamento.orcamentoId())).join());

        assertEquals("INVALID_STATE_TRANSITION", ((BusinessException) failure.getCause()).code());
    }

    @Test
    void deveFalharQuandoOrcamentoNaoExistir() {
        var useCase = new ReenviarNotificacaoOrcamentoUseCase(
                new InMemoryOrcamentoDataSourceAdapter(),
                ignored -> CompletableFuture.completedFuture(null));

        var failure = assertThrows(CompletionException.class, () -> useCase.executar(
                new ReenviarNotificacaoOrcamentoUseCase.Command(UUID.randomUUID())).join());

        assertEquals(ResourceNotFoundException.class, failure.getCause().getClass());
    }

    @Test
    void deveClassificarFalhaDaNotificacaoComoDependenciaIndisponivel() {
        var repository = new InMemoryOrcamentoDataSourceAdapter();
        var orcamento = orcamento(StatusOrcamento.GERADO);
        repository.save(orcamento).join();
        var useCase = new ReenviarNotificacaoOrcamentoUseCase(
                repository,
                ignored -> CompletableFuture.failedFuture(new IllegalStateException("smtp indisponivel")));

        var failure = assertThrows(CompletionException.class, () -> useCase.executar(
                new ReenviarNotificacaoOrcamentoUseCase.Command(orcamento.orcamentoId())).join());

        assertEquals("DEPENDENCY_UNAVAILABLE", ((BusinessException) failure.getCause()).code());
    }

    private Orcamento orcamento(StatusOrcamento status) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Orcamento(
                UUID.randomUUID(), UUID.randomUUID(), List.of(), BigDecimal.TEN,
                status, now, now);
    }
}
