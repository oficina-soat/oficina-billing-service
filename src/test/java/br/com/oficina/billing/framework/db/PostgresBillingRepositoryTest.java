package br.com.oficina.billing.framework.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.interfaces.OrcamentoRepository;
import br.com.oficina.billing.core.interfaces.PagamentoRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@QuarkusTest
@TestProfile(PostgresBillingRepositoryTest.PostgresRepositoryProfile.class)
@QuarkusTestResource(value = PostgresBillingRepositoryTest.PostgresResource.class, restrictToAnnotatedClass = true)
class PostgresBillingRepositoryTest {
    @Inject
    OrcamentoRepository orcamentoRepository;

    @Inject
    PagamentoRepository pagamentoRepository;

    @Test
    void devePersistirOrcamentoItensEPagamentoNoPostgreSQL() {
        assertInstanceOf(PostgresOrcamentoRepository.class, orcamentoRepository);
        assertInstanceOf(PostgresPagamentoRepository.class, pagamentoRepository);

        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var criadoEm = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        var atualizadoEm = OffsetDateTime.now(ZoneOffset.UTC);
        var itemId = UUID.randomUUID();
        var referenciaCatalogoId = UUID.randomUUID();
        var orcamento = new Orcamento(
                orcamentoId,
                ordemServicoId,
                List.of(
                        new ItemOrcamento(
                                TipoItemOrcamento.PECA,
                                itemId,
                                referenciaCatalogoId,
                                "Filtro de oleo",
                                BigDecimal.ONE,
                                new BigDecimal("40.00"),
                                new BigDecimal("40.00")),
                        new ItemOrcamento(
                                TipoItemOrcamento.SERVICO,
                                UUID.randomUUID(),
                                null,
                                "Troca de oleo",
                                BigDecimal.ONE,
                                new BigDecimal("150.00"),
                                new BigDecimal("150.00"))),
                new BigDecimal("190.00"),
                StatusOrcamento.GERADO,
                criadoEm,
                atualizadoEm);

        orcamentoRepository.save(orcamento);

        var salvo = orcamentoRepository.findById(orcamentoId).orElseThrow();
        assertEquals(orcamentoId, salvo.orcamentoId());
        assertEquals(ordemServicoId, salvo.ordemServicoId());
        assertEquals(StatusOrcamento.GERADO, salvo.status());
        assertEquals(new BigDecimal("190.00"), salvo.valorTotal());
        assertEquals(2, salvo.itens().size());
        assertEquals(referenciaCatalogoId, salvo.itens().getFirst().referenciaCatalogoId());
        assertEquals(List.of(orcamentoId), orcamentoRepository.findByOrdemServicoId(ordemServicoId).stream()
                .map(Orcamento::orcamentoId)
                .toList());
        assertTrue(orcamentoRepository.findById(UUID.randomUUID()).isEmpty());

        var aprovado = new Orcamento(
                orcamentoId,
                ordemServicoId,
                salvo.itens(),
                salvo.valorTotal(),
                StatusOrcamento.APROVADO,
                salvo.criadoEm(),
                OffsetDateTime.now(ZoneOffset.UTC));
        orcamentoRepository.save(aprovado);
        assertEquals(StatusOrcamento.APROVADO, orcamentoRepository.findById(orcamentoId).orElseThrow().status());

        var pagamentoId = UUID.randomUUID();
        var pagamento = new Pagamento(
                pagamentoId,
                ordemServicoId,
                orcamentoId,
                new BigDecimal("190.00"),
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                null,
                null,
                criadoEm,
                atualizadoEm);

        pagamentoRepository.save(pagamento);

        var pagamentoSalvo = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertEquals(StatusPagamento.CRIADO, pagamentoSalvo.status());
        assertEquals(pagamentoId, pagamentoRepository.findByOrcamentoId(orcamentoId).orElseThrow().pagamentoId());
        assertEquals(List.of(pagamentoId), pagamentoRepository.findByOrdemServicoId(ordemServicoId).stream()
                .map(Pagamento::pagamentoId)
                .toList());
        assertTrue(pagamentoRepository.findById(UUID.randomUUID()).isEmpty());

        var confirmado = new Pagamento(
                pagamentoId,
                ordemServicoId,
                orcamentoId,
                pagamentoSalvo.valor(),
                pagamentoSalvo.metodo(),
                StatusPagamento.CONFIRMADO,
                "mercado-pago",
                "mp-postgres-test",
                pagamentoSalvo.criadoEm(),
                OffsetDateTime.now(ZoneOffset.UTC));
        pagamentoRepository.save(confirmado);

        var pagamentoConfirmado = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertEquals(StatusPagamento.CONFIRMADO, pagamentoConfirmado.status());
        assertEquals("mercado-pago", pagamentoConfirmado.provedor());
        assertEquals("mp-postgres-test", pagamentoConfirmado.transacaoExternaId());
    }

    public static class PostgresRepositoryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("oficina.persistence.kind", "postgresql"),
                    Map.entry("quarkus.datasource.active", "true"),
                    Map.entry("quarkus.datasource.db-kind", "postgresql"),
                    Map.entry("quarkus.datasource.devservices.enabled", "false"),
                    Map.entry("quarkus.flyway.active", "true"),
                    Map.entry("quarkus.flyway.migrate-at-start", "true"),
                    Map.entry("quarkus.hibernate-orm.active", "false"),
                    Map.entry("quarkus.log.console.json.enabled", "false"),
                    Map.entry("quarkus.otel.traces.enabled", "false"));
        }
    }

    public static class PostgresResource implements QuarkusTestResourceLifecycleManager {
        private PostgreSQLContainer postgres;

        @Override
        public Map<String, String> start() {
            postgres = new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("oficina_billing")
                    .withUsername("oficina_billing_user")
                    .withPassword("oficina_billing_password");
            postgres.start();
            return Map.of(
                    "quarkus.datasource.jdbc.url", postgres.getJdbcUrl(),
                    "quarkus.datasource.username", postgres.getUsername(),
                    "quarkus.datasource.password", postgres.getPassword());
        }

        @Override
        public void stop() {
            if (postgres != null) {
                postgres.stop();
            }
        }
    }
}
