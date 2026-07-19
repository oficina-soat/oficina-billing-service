package br.com.oficina.billing.framework.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.InstrucoesPix;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;
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
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
    void devePersistirPixBuscarTransacaoEAtualizarStatusCondicionalmente() {
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var pagamentoId = UUID.randomUUID();
        var now = OffsetDateTime.parse("2026-07-18T18:00:00Z");
        var expiration = now.plusMinutes(30);
        orcamentoRepository.save(new Orcamento(
                orcamentoId,
                ordemServicoId,
                List.of(),
                new BigDecimal("190.00"),
                StatusOrcamento.APROVADO,
                now,
                now)).join();
        var instructions = new InstrucoesPix(
                "pix-copia-e-cola",
                "qr-code-base64",
                "https://example.test/pix",
                expiration);
        var pending = new Pagamento(
                pagamentoId,
                ordemServicoId,
                orcamentoId,
                new BigDecimal("190.00"),
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                "mercado-pago",
                "mp-pix-" + pagamentoId,
                instructions,
                now,
                now);

        pagamentoRepository.save(pending).join();

        var persisted = pagamentoRepository.findByTransacaoExternaId(pending.transacaoExternaId())
                .join().orElseThrow();
        assertEquals(instructions, persisted.instrucoesPix());
        assertEquals(TipoReferenciaExternaPagamento.PAYMENT, persisted.tipoReferenciaExterna());
        assertEquals(
                pagamentoId,
                pagamentoRepository.findByTransacaoExternaId(
                                pending.transacaoExternaId(),
                                TipoReferenciaExternaPagamento.PAYMENT)
                        .join()
                        .orElseThrow()
                        .pagamentoId());
        assertTrue(pagamentoRepository.findByTransacaoExternaId("missing-transaction").join().isEmpty());

        var confirmed = new Pagamento(
                persisted.pagamentoId(),
                persisted.ordemServicoId(),
                persisted.orcamentoId(),
                persisted.valor(),
                persisted.metodo(),
                StatusPagamento.CONFIRMADO,
                persisted.provedor(),
                persisted.transacaoExternaId(),
                persisted.instrucoesPix(),
                persisted.criadoEm(),
                now.plusMinutes(1));
        var firstUpdate = pagamentoRepository.updateIfStatus(confirmed, StatusPagamento.CRIADO).join();
        var concurrentUpdate = pagamentoRepository.updateIfStatus(
                new Pagamento(
                        confirmed.pagamentoId(),
                        confirmed.ordemServicoId(),
                        confirmed.orcamentoId(),
                        confirmed.valor(),
                        confirmed.metodo(),
                        StatusPagamento.RECUSADO,
                        confirmed.provedor(),
                        confirmed.transacaoExternaId(),
                        confirmed.instrucoesPix(),
                        confirmed.criadoEm(),
                        now.plusMinutes(2)),
                StatusPagamento.CRIADO).join();

        assertTrue(firstUpdate.updated());
        assertFalse(concurrentUpdate.updated());
        assertEquals(StatusPagamento.CONFIRMADO, concurrentUpdate.pagamento().status());
        assertEquals(instructions, concurrentUpdate.pagamento().instrucoesPix());
    }

    @Test
    void deveDistinguirOrderDePaymentComMesmoIdentificadorExterno() {
        var paymentBudgetId = UUID.randomUUID();
        var orderBudgetId = UUID.randomUUID();
        var paymentOrderServiceId = UUID.randomUUID();
        var orderOrderServiceId = UUID.randomUUID();
        var now = OffsetDateTime.parse("2026-07-19T12:00:00Z");
        for (var budget : List.of(
                new Orcamento(
                        paymentBudgetId,
                        paymentOrderServiceId,
                        List.of(),
                        BigDecimal.ONE,
                        StatusOrcamento.APROVADO,
                        now,
                        now),
                new Orcamento(
                        orderBudgetId,
                        orderOrderServiceId,
                        List.of(),
                        BigDecimal.ONE,
                        StatusOrcamento.APROVADO,
                        now,
                        now))) {
            orcamentoRepository.save(budget).join();
        }
        var externalId = "shared-provider-reference";
        var payment = new Pagamento(
                UUID.randomUUID(),
                paymentOrderServiceId,
                paymentBudgetId,
                BigDecimal.ONE,
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                "mercado-pago",
                externalId,
                TipoReferenciaExternaPagamento.PAYMENT,
                null,
                now,
                now);
        var order = new Pagamento(
                UUID.randomUUID(),
                orderOrderServiceId,
                orderBudgetId,
                BigDecimal.ONE,
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                "mercado-pago",
                externalId,
                TipoReferenciaExternaPagamento.ORDER,
                null,
                now,
                now);

        pagamentoRepository.save(payment).join();
        pagamentoRepository.save(order).join();

        assertEquals(
                payment.pagamentoId(),
                pagamentoRepository.findByTransacaoExternaId(
                                externalId,
                                TipoReferenciaExternaPagamento.PAYMENT)
                        .join()
                        .orElseThrow()
                        .pagamentoId());
        assertEquals(
                order.pagamentoId(),
                pagamentoRepository.findByTransacaoExternaId(
                                externalId,
                                TipoReferenciaExternaPagamento.ORDER)
                        .join()
                        .orElseThrow()
                        .pagamentoId());
    }

    @Test
    void deveFazerBackfillDePaymentsLegadosNaMigrationV9() {
        var schema = "migration_orders_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("8"))
                .load()
                .migrate();
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var pagamentoId = UUID.randomUUID();
        var now = OffsetDateTime.parse("2026-07-19T12:00:00Z");
        try (var connection = dataSource.getConnection();
                var budgetStatement = connection.prepareStatement("""
                        INSERT INTO %s.orcamento (
                            id, ordem_de_servico_id, valor_total, status, criado_em, atualizado_em
                        ) VALUES (?, ?, ?, 'APROVADO', ?, ?)
                        """.formatted(schema));
                var paymentStatement = connection.prepareStatement("""
                        INSERT INTO %s.pagamento (
                            id, ordem_de_servico_id, orcamento_id, valor, metodo, status,
                            provedor, transacao_externa_id, criado_em, atualizado_em
                        ) VALUES (?, ?, ?, ?, 'PIX', 'CRIADO', 'mercado-pago', ?, ?, ?)
                        """.formatted(schema))) {
            budgetStatement.setObject(1, orcamentoId);
            budgetStatement.setObject(2, ordemServicoId);
            budgetStatement.setBigDecimal(3, BigDecimal.ONE);
            budgetStatement.setObject(4, now);
            budgetStatement.setObject(5, now);
            budgetStatement.executeUpdate();

            paymentStatement.setObject(1, pagamentoId);
            paymentStatement.setObject(2, ordemServicoId);
            paymentStatement.setObject(3, orcamentoId);
            paymentStatement.setBigDecimal(4, BigDecimal.ONE);
            paymentStatement.setString(5, "legacy-payment-id");
            paymentStatement.setObject(6, now);
            paymentStatement.setObject(7, now);
            paymentStatement.executeUpdate();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT tipo_referencia_externa FROM %s.pagamento WHERE id = ?".formatted(schema))) {
            statement.setObject(1, pagamentoId);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("PAYMENT", resultSet.getString("tipo_referencia_externa"));
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void deveCriarPagamentoUmaUnicaVezSobConcorrenciaNoPostgreSQL() {
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        orcamentoRepository.save(new Orcamento(
                orcamentoId,
                ordemServicoId,
                List.of(),
                BigDecimal.ZERO,
                StatusOrcamento.APROVADO,
                now,
                now)).join();
        var pagamento = new Pagamento(
                UUID.randomUUID(),
                ordemServicoId,
                orcamentoId,
                BigDecimal.ZERO,
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                null,
                null,
                now,
                now);

        var first = CompletableFuture.supplyAsync(() -> pagamentoRepository.createIfAbsent(pagamento).join());
        var second = CompletableFuture.supplyAsync(() -> pagamentoRepository.createIfAbsent(pagamento).join());
        var results = List.of(first.join(), second.join());

        assertEquals(1, results.stream().filter(PagamentoRepositoryGateway.CreateResult::created).count());
        assertTrue(results.stream().allMatch(result -> result.pagamento().pagamentoId().equals(pagamento.pagamentoId())));
        assertEquals(1, pagamentoRepository.findByOrdemServicoId(ordemServicoId).join().size());
    }

    @Test
    void deveCoordenarSolicitacaoAoProvedorEntreReplicasERecuperarLease() {
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        orcamentoRepository.save(new Orcamento(
                orcamentoId,
                ordemServicoId,
                List.of(),
                BigDecimal.ZERO,
                StatusOrcamento.APROVADO,
                now,
                now)).join();
        var replicaA = new PostgresPagamentoDataSourceAdapter(dataSource);
        var replicaB = new PostgresPagamentoDataSourceAdapter(dataSource);
        var ownerA = UUID.randomUUID();
        var ownerB = UUID.randomUUID();

        assertTrue(replicaA.claimProviderRequest(
                orcamentoId, ownerA, now, now.plusMinutes(1)).join());
        assertFalse(replicaB.claimProviderRequest(
                orcamentoId, ownerB, now, now.plusMinutes(1)).join());

        replicaB.releaseProviderRequest(orcamentoId, ownerB).join();
        assertFalse(replicaB.claimProviderRequest(
                orcamentoId, ownerB, now.plusSeconds(1), now.plusMinutes(1)).join());

        var afterLease = now.plusMinutes(2);
        assertTrue(replicaB.claimProviderRequest(
                orcamentoId, ownerB, afterLease, afterLease.plusMinutes(1)).join());

        replicaA.releaseProviderRequest(orcamentoId, ownerA).join();
        assertFalse(replicaA.claimProviderRequest(
                orcamentoId, ownerA, afterLease.plusSeconds(1), afterLease.plusMinutes(1)).join());

        replicaB.releaseProviderRequest(orcamentoId, ownerB).join();
        assertTrue(replicaA.claimProviderRequest(
                orcamentoId, ownerA, afterLease.plusSeconds(1), afterLease.plusMinutes(1)).join());
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
