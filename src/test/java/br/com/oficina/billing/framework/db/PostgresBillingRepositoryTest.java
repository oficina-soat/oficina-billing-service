package br.com.oficina.billing.framework.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import br.com.oficina.billing.framework.idempotency.PersistentIdempotencyStore;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import br.com.oficina.billing.framework.messaging.ApprovalTokenRecord;
import br.com.oficina.billing.framework.messaging.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@QuarkusTest
@TestProfile(PostgresBillingRepositoryTest.PostgresRepositoryProfile.class)
@QuarkusTestResource(value = PostgresBillingRepositoryTest.PostgresResource.class, restrictToAnnotatedClass = true)
class PostgresBillingRepositoryTest {
    @Inject
    OrcamentoRepositoryGateway orcamentoRepository;

    @Inject
    PagamentoRepositoryGateway pagamentoRepository;

    @Inject
    BillingEventStore eventStore;

    @Inject
    DataSource dataSource;

    @Test
    void deveCoordenarClaimDaOutboxEntreReplicasERecuperarLeaseExpirado() {
        var eventId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        eventStore.registrarOutboxIdempotente(
                eventId,
                UUID.randomUUID().toString(),
                "pagamentoSolicitado",
                "oficina.billing.pagamento-solicitado",
                Map.of("pagamentoId", UUID.randomUUID().toString()),
                "postgres-claim-test",
                now).join();

        var firstClaim = eventStore.reivindicarPendentesParaPublicacao(100, "replica-a", now.plusMinutes(1));
        var concurrentClaim = eventStore.reivindicarPendentesParaPublicacao(100, "replica-b", now.plusMinutes(1));

        assertTrue(firstClaim.stream().anyMatch(candidate -> candidate.eventId().equals(eventId)));
        assertTrue(concurrentClaim.stream().noneMatch(candidate -> candidate.eventId().equals(eventId)));
        assertThrows(IllegalStateException.class, () -> eventStore.marcarPublicado(eventId, "replica-b"));

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE outbox_event SET claim_until = ? WHERE id = ?")) {
            statement.setObject(1, now.minusSeconds(1));
            statement.setObject(2, eventId);
            statement.executeUpdate();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }

        var recovered = eventStore.reivindicarPendentesParaPublicacao(100, "replica-b", now.plusMinutes(1));
        assertTrue(recovered.stream().anyMatch(candidate -> candidate.eventId().equals(eventId)));
        assertEquals("PUBLISHED", eventStore.marcarPublicado(eventId, "replica-b").status());
    }

    @Test
    void devePersistirOrcamentoItensEPagamentoNoPostgreSQL() {
        assertInstanceOf(PostgresOrcamentoDataSourceAdapter.class, orcamentoRepository);
        assertInstanceOf(PostgresPagamentoDataSourceAdapter.class, pagamentoRepository);

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

        orcamentoRepository.save(orcamento).join();

        var salvo = orcamentoRepository.findById(orcamentoId).join().orElseThrow();
        assertEquals(orcamentoId, salvo.orcamentoId());
        assertEquals(ordemServicoId, salvo.ordemServicoId());
        assertEquals(StatusOrcamento.GERADO, salvo.status());
        assertEquals(new BigDecimal("190.00"), salvo.valorTotal());
        assertEquals(2, salvo.itens().size());
        assertEquals(referenciaCatalogoId, salvo.itens().getFirst().referenciaCatalogoId());
        assertEquals(List.of(orcamentoId), orcamentoRepository.findByOrdemServicoId(ordemServicoId).join().stream()
                .map(Orcamento::orcamentoId)
                .toList());
        assertTrue(orcamentoRepository.findById(UUID.randomUUID()).join().isEmpty());
        assertTrue(orcamentoRepository.findAll().join().stream()
                .anyMatch(item -> item.orcamentoId().equals(orcamentoId)));

        var aprovado = new Orcamento(
                orcamentoId,
                ordemServicoId,
                salvo.itens(),
                salvo.valorTotal(),
                StatusOrcamento.APROVADO,
                salvo.criadoEm(),
                OffsetDateTime.now(ZoneOffset.UTC));
        orcamentoRepository.save(aprovado).join();
        assertEquals(StatusOrcamento.APROVADO, orcamentoRepository.findById(orcamentoId).join().orElseThrow().status());

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

        pagamentoRepository.save(pagamento).join();

        var pagamentoSalvo = pagamentoRepository.findById(pagamentoId).join().orElseThrow();
        assertEquals(StatusPagamento.CRIADO, pagamentoSalvo.status());
        assertEquals(pagamentoId, pagamentoRepository.findByOrcamentoId(orcamentoId).join().orElseThrow().pagamentoId());
        assertEquals(List.of(pagamentoId), pagamentoRepository.findByOrdemServicoId(ordemServicoId).join().stream()
                .map(Pagamento::pagamentoId)
                .toList());
        assertTrue(pagamentoRepository.findById(UUID.randomUUID()).join().isEmpty());
        assertTrue(pagamentoRepository.findAll().join().stream()
                .anyMatch(item -> item.pagamentoId().equals(pagamentoId)));

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
        pagamentoRepository.save(confirmado).join();

        var pagamentoConfirmado = pagamentoRepository.findById(pagamentoId).join().orElseThrow();
        assertEquals(StatusPagamento.CONFIRMADO, pagamentoConfirmado.status());
        assertEquals("mercado-pago", pagamentoConfirmado.provedor());
        assertEquals("mp-postgres-test", pagamentoConfirmado.transacaoExternaId());
    }

    @Test
    void devePersistirProjecoesEventosConsumidosEOutboxNoPostgreSQL() {
        assertInstanceOf(PostgresBillingEventStore.class, eventStore);

        var ordemServicoId = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        eventStore.registrarItem(ordemServicoId, new ItemOrcamento(
                TipoItemOrcamento.SERVICO,
                itemId,
                itemId,
                "Alinhamento",
                BigDecimal.ONE,
                new BigDecimal("120.00"),
                new BigDecimal("120.00")));

        var restartedStore = new PostgresBillingEventStore(dataSource, new ObjectMapper());
        var snapshot = restartedStore.snapshotFinanceiro(ordemServicoId).join();
        assertEquals(1, snapshot.size());
        assertEquals(itemId, snapshot.getFirst().itemId());
        assertEquals(new BigDecimal("120.00"), snapshot.getFirst().valorTotal());

        var eventId = UUID.randomUUID();
        var envelope = new DomainEventEnvelope(
                eventId,
                "diagnosticoFinalizado",
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                "oficina-execution-service",
                ordemServicoId.toString(),
                Map.of("ordemServicoId", ordemServicoId.toString()));

        assertTrue(eventStore.registrarEventoConsumido(envelope));
        assertTrue(restartedStore.eventoConsumido(eventId));
        assertFalse(restartedStore.registrarEventoConsumido(envelope));
        var legacyEventId = UUID.randomUUID();
        assertTrue(restartedStore.registrarEventoConsumido(legacyEventId));
        assertTrue(restartedStore.eventoConsumido(legacyEventId));

        eventStore.registrarOutbox(
                ordemServicoId.toString(),
                "pagamentoSolicitado",
                "oficina.billing.pagamento-solicitado",
                Map.of("ordemServicoId", ordemServicoId.toString(), "valor", new BigDecimal("120.00")),
                "postgres-event-store-test",
                OffsetDateTime.now(ZoneOffset.UTC)).join();

        var pendente = restartedStore.listarOutbox().stream()
                .filter(event -> event.correlationId().equals("postgres-event-store-test"))
                .findFirst()
                .orElseThrow();
        assertEquals("PENDING", pendente.status());
        assertEquals(new BigDecimal("120.00"), pendente.payload().get("valor"));

        var publicados = restartedStore.publicarPendentes().stream()
                .filter(event -> event.correlationId().equals("postgres-event-store-test"))
                .toList();

        assertEquals(1, publicados.size());
        assertEquals("PUBLISHED", publicados.getFirst().status());
        assertEquals(1, publicados.getFirst().attempts());
        assertTrue(restartedStore.listarOutbox().stream()
                .filter(event -> event.eventId().equals(pendente.eventId()))
                .allMatch(event -> event.status().equals("PUBLISHED") && event.publishedAt() != null));

        var idempotentOutboxId = UUID.randomUUID();
        for (var attempt = 0; attempt < 2; attempt++) {
            eventStore.registrarOutboxIdempotente(
                    idempotentOutboxId,
                    ordemServicoId.toString(),
                    "orcamentoGerado",
                    "oficina.billing.orcamento-gerado",
                    Map.of("ordemServicoId", ordemServicoId.toString()),
                    "postgres-idempotent-outbox-test",
                    OffsetDateTime.now(ZoneOffset.UTC)).join();
        }
        assertEquals(1, restartedStore.listarOutbox().stream()
                .filter(event -> event.eventId().equals(idempotentOutboxId))
                .count());

        eventStore.registrarOutbox(
                ordemServicoId.toString(),
                "pagamentoSolicitado",
                "oficina.billing.pagamento-solicitado",
                Map.of("ordemServicoId", ordemServicoId.toString()),
                null,
                null).join();
        var sujeitoAFalha = restartedStore.listarOutbox().stream()
                .filter(event -> event.status().equals("PENDING") && !event.eventId().equals(pendente.eventId()))
                .findFirst()
                .orElseThrow();
        var reagendado = restartedStore.marcarFalhaPublicacao(
                sujeitoAFalha.eventId(), "endpoint indisponivel", OffsetDateTime.now(ZoneOffset.UTC), false);
        assertEquals("PENDING", reagendado.status());
        assertEquals(1, reagendado.attempts());
        var falhaDefinitiva = restartedStore.marcarFalhaPublicacao(
                sujeitoAFalha.eventId(), "limite de tentativas", null, true);
        assertEquals("FAILED", falhaDefinitiva.status());
        assertEquals(2, falhaDefinitiva.attempts());
    }

    @Test
    void devePersistirContatoETokensDeAprovacaoComConsumoUnicoNoPostgreSQL() {
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var tokenHash = "hash-token-postgres-" + UUID.randomUUID();

        orcamentoRepository.save(new Orcamento(
                orcamentoId,
                ordemServicoId,
                List.of(),
                BigDecimal.ZERO,
                StatusOrcamento.GERADO,
                now,
                now)).join();

        assertTrue(eventStore.buscarContato(ordemServicoId).isEmpty());
        eventStore.registrarContato(ordemServicoId, "cliente@oficina.test");
        assertEquals("cliente@oficina.test", eventStore.buscarContato(ordemServicoId).orElseThrow());

        eventStore.substituirTokensAprovacao(
                ordemServicoId,
                orcamentoId,
                "cliente@oficina.test",
                List.of(new ApprovalTokenRecord(
                        UUID.randomUUID(), tokenHash, "APROVAR", now, now.plusHours(1))));

        var grant = eventStore.buscarTokenAprovacao(tokenHash).orElseThrow();
        assertEquals(ordemServicoId, grant.ordemServicoId());
        assertEquals(orcamentoId, grant.orcamentoId());
        assertEquals("APROVAR", grant.action());
        assertTrue(grant.disponivelEm(now));
        assertTrue(eventStore.buscarTokenAprovacao("hash-inexistente").isEmpty());

        assertTrue(eventStore.consumirTokenAprovacao(tokenHash, now.plusMinutes(1)));
        assertFalse(eventStore.consumirTokenAprovacao(tokenHash, now.plusMinutes(2)));
        assertFalse(eventStore.buscarTokenAprovacao(tokenHash).orElseThrow().disponivelEm(now.plusMinutes(2)));
        assertFalse(eventStore.liberarTokenAprovacao(tokenHash, now.plusMinutes(2)));
        assertTrue(eventStore.liberarTokenAprovacao(tokenHash, now.plusMinutes(1)));
        assertTrue(eventStore.buscarTokenAprovacao(tokenHash).orElseThrow().disponivelEm(now.plusMinutes(2)));
        assertTrue(eventStore.consumirTokenAprovacao(tokenHash, now.plusMinutes(2)));

        var replacementHash = "hash-token-substituto-" + UUID.randomUUID();
        eventStore.substituirTokensAprovacao(
                ordemServicoId,
                orcamentoId,
                "cliente@oficina.test",
                List.of(new ApprovalTokenRecord(
                        UUID.randomUUID(), replacementHash, "RECUSAR", now, now.plusHours(1))));
        assertEquals("RECUSAR", eventStore.buscarTokenAprovacao(replacementHash).orElseThrow().action());
    }

    @Test
    void devePersistirRegistrosDeIdempotenciaNoPostgreSQL() {
        var idempotencyStore = new PersistentIdempotencyStore(dataSource);
        var idempotencyRecord = idempotencyStore.createProcessing(
                "oficina-billing-service:POST:/api/v1/orcamentos:anonymous",
                "postgres-idempotency-001",
                "hash-postgres-001",
                "correlation-postgres-001",
                "request-postgres-001",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

        assertEquals(ProcessingStatus.PROCESSING, idempotencyRecord.processingStatus());

        idempotencyStore.complete(
                idempotencyRecord.scope(),
                idempotencyRecord.key(),
                ProcessingStatus.COMPLETED,
                201,
                "{\"orcamentoId\":\"orcamento-postgres-001\"}");

        var reloaded = new PersistentIdempotencyStore(dataSource)
                .find(idempotencyRecord.scope(), idempotencyRecord.key())
                .orElseThrow();
        assertEquals(ProcessingStatus.COMPLETED, reloaded.processingStatus());
        assertEquals(201, reloaded.responseStatus());
        assertEquals("{\"orcamentoId\":\"orcamento-postgres-001\"}", reloaded.responseBody());
        assertEquals("correlation-postgres-001", reloaded.correlationId());
        assertEquals("request-postgres-001", reloaded.requestId());
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
