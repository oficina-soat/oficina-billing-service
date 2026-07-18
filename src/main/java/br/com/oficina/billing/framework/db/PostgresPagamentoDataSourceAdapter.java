package br.com.oficina.billing.framework.db;

import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.offsetDateTime;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.nullableOffsetDateTime;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.persistenceFailure;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.uuid;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.InstrucoesPix;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.framework.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

@ApplicationScoped
@IfBuildProperty(name = "oficina.persistence.kind", stringValue = "postgresql")
public class PostgresPagamentoDataSourceAdapter implements PagamentoRepositoryGateway {
    private static final String DATABASE = "postgresql";
    private static final String RESOURCE = "pagamento";
    private static final String UPSERT_PAGAMENTO = """
            INSERT INTO pagamento (
                id,
                ordem_de_servico_id,
                orcamento_id,
                valor,
                metodo,
                status,
                provedor,
                transacao_externa_id,
                pix_copia_e_cola,
                pix_qr_code_base64,
                pix_ticket_url,
                pix_expira_em,
                criado_em,
                atualizado_em
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                ordem_de_servico_id = EXCLUDED.ordem_de_servico_id,
                orcamento_id = EXCLUDED.orcamento_id,
                valor = EXCLUDED.valor,
                metodo = EXCLUDED.metodo,
                status = EXCLUDED.status,
                provedor = EXCLUDED.provedor,
                transacao_externa_id = EXCLUDED.transacao_externa_id,
                pix_copia_e_cola = EXCLUDED.pix_copia_e_cola,
                pix_qr_code_base64 = EXCLUDED.pix_qr_code_base64,
                pix_ticket_url = EXCLUDED.pix_ticket_url,
                pix_expira_em = EXCLUDED.pix_expira_em,
                criado_em = EXCLUDED.criado_em,
                atualizado_em = EXCLUDED.atualizado_em
            """;
    private static final String INSERT_PAGAMENTO_IF_ABSENT = """
            INSERT INTO pagamento (
                id,
                ordem_de_servico_id,
                orcamento_id,
                valor,
                metodo,
                status,
                provedor,
                transacao_externa_id,
                pix_copia_e_cola,
                pix_qr_code_base64,
                pix_ticket_url,
                pix_expira_em,
                criado_em,
                atualizado_em
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String SELECT_PAGAMENTO_BY_ID = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, pix_copia_e_cola, pix_qr_code_base64, pix_ticket_url,
                   pix_expira_em, criado_em, atualizado_em
            FROM pagamento
            WHERE id = ?
            """;
    private static final String SELECT_PAGAMENTO_BY_TRANSACAO = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, pix_copia_e_cola, pix_qr_code_base64, pix_ticket_url,
                   pix_expira_em, criado_em, atualizado_em
            FROM pagamento
            WHERE transacao_externa_id = ?
            """;

    private static final String SELECT_PAGAMENTOS_BY_ORDEM = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, pix_copia_e_cola, pix_qr_code_base64, pix_ticket_url,
                   pix_expira_em, criado_em, atualizado_em
            FROM pagamento
            WHERE ordem_de_servico_id = ?
            ORDER BY criado_em
            """;

    private static final String SELECT_PAGAMENTO_BY_ORCAMENTO = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, pix_copia_e_cola, pix_qr_code_base64, pix_ticket_url,
                   pix_expira_em, criado_em, atualizado_em
            FROM pagamento
            WHERE orcamento_id = ?
            """;
    private static final String SELECT_PAGAMENTOS = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, pix_copia_e_cola, pix_qr_code_base64, pix_ticket_url,
                   pix_expira_em, criado_em, atualizado_em
            FROM pagamento
            ORDER BY atualizado_em
            """;
    private static final String UPDATE_PAGAMENTO_IF_STATUS = """
            UPDATE pagamento SET
                status = ?,
                provedor = ?,
                transacao_externa_id = ?,
                pix_copia_e_cola = ?,
                pix_qr_code_base64 = ?,
                pix_ticket_url = ?,
                pix_expira_em = ?,
                atualizado_em = ?
            WHERE id = ? AND status = ?
            """;
    private static final String CLAIM_PROVIDER_REQUEST = """
            INSERT INTO pagamento_provider_claim (
                orcamento_id,
                owner_id,
                claim_until,
                updated_at
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT (orcamento_id) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                claim_until = EXCLUDED.claim_until,
                updated_at = EXCLUDED.updated_at
            WHERE pagamento_provider_claim.claim_until <= EXCLUDED.updated_at
            RETURNING owner_id
            """;
    private static final String RELEASE_PROVIDER_REQUEST = """
            DELETE FROM pagamento_provider_claim
            WHERE orcamento_id = ?
              AND owner_id = ?
            """;

    private final DataSource dataSource;
    private final OperationalMetrics metrics;

    public PostgresPagamentoDataSourceAdapter(DataSource dataSource) {
        this(dataSource, new OperationalMetrics(new SimpleMeterRegistry(), "oficina-billing-service"));
    }

    @Inject
    public PostgresPagamentoDataSourceAdapter(DataSource dataSource, OperationalMetrics metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    @Override
    public CompletableFuture<Pagamento> save(Pagamento pagamento) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "save", () -> saveBlocking(pagamento)));
    }

    private Pagamento saveBlocking(Pagamento pagamento) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(UPSERT_PAGAMENTO)) {
            bindPagamento(statement, pagamento);
            statement.executeUpdate();
            return pagamento;
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<CreateResult> createIfAbsent(Pagamento pagamento) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "create_if_absent", () -> createIfAbsentBlocking(pagamento)));
    }

    private CreateResult createIfAbsentBlocking(Pagamento pagamento) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(INSERT_PAGAMENTO_IF_ABSENT)) {
            bindPagamento(statement, pagamento);
            if (statement.executeUpdate() > 0) {
                return new CreateResult(pagamento, true);
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
        var existente = findOne(SELECT_PAGAMENTO_BY_ORCAMENTO, pagamento.orcamentoId())
                .orElseThrow(() -> new IllegalStateException("Pagamento concorrente nao encontrado apos conflito."));
        return new CreateResult(existente, false);
    }

    private void bindPagamento(java.sql.PreparedStatement statement, Pagamento pagamento) throws SQLException {
        statement.setObject(1, pagamento.pagamentoId());
        statement.setObject(2, pagamento.ordemServicoId());
        statement.setObject(3, pagamento.orcamentoId());
        statement.setBigDecimal(4, pagamento.valor());
        statement.setString(5, pagamento.metodo().name());
        statement.setString(6, pagamento.status().name());
        statement.setString(7, pagamento.provedor());
        statement.setString(8, pagamento.transacaoExternaId());
        bindInstrucoesPix(statement, pagamento.instrucoesPix(), 9);
        statement.setObject(13, pagamento.criadoEm());
        statement.setObject(14, pagamento.atualizadoEm());
    }

    private void bindInstrucoesPix(
            java.sql.PreparedStatement statement,
            InstrucoesPix instrucoes,
            int start) throws SQLException {
        statement.setString(start, instrucoes == null ? null : instrucoes.copiaECola());
        statement.setString(start + 1, instrucoes == null ? null : instrucoes.qrCodeBase64());
        statement.setString(start + 2, instrucoes == null ? null : instrucoes.ticketUrl());
        statement.setObject(start + 3, instrucoes == null ? null : instrucoes.expiraEm());
    }

    @Override
    public CompletableFuture<Boolean> claimProviderRequest(
            UUID orcamentoId,
            UUID ownerId,
            OffsetDateTime claimedAt,
            OffsetDateTime claimUntil) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE,
                RESOURCE,
                "claim_provider_request",
                () -> claimProviderRequestBlocking(orcamentoId, ownerId, claimedAt, claimUntil)));
    }

    private boolean claimProviderRequestBlocking(
            UUID orcamentoId,
            UUID ownerId,
            OffsetDateTime claimedAt,
            OffsetDateTime claimUntil) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(CLAIM_PROVIDER_REQUEST)) {
            statement.setObject(1, orcamentoId);
            statement.setObject(2, ownerId);
            statement.setObject(3, claimUntil);
            statement.setObject(4, claimedAt);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() && ownerId.equals(uuid(resultSet, "owner_id"));
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<Void> releaseProviderRequest(UUID orcamentoId, UUID ownerId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE,
                RESOURCE,
                "release_provider_request",
                () -> releaseProviderRequestBlocking(orcamentoId, ownerId)));
    }

    private Void releaseProviderRequestBlocking(UUID orcamentoId, UUID ownerId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(RELEASE_PROVIDER_REQUEST)) {
            statement.setObject(1, orcamentoId);
            statement.setObject(2, ownerId);
            statement.executeUpdate();
            return null;
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findById(UUID pagamentoId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "find_by_id", () -> findOne(SELECT_PAGAMENTO_BY_ID, pagamentoId)));
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findByTransacaoExternaId(String transacaoExternaId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE,
                RESOURCE,
                "find_by_external_transaction",
                () -> findOneByString(SELECT_PAGAMENTO_BY_TRANSACAO, transacaoExternaId)));
    }

    @Override
    public CompletableFuture<List<Pagamento>> findByOrdemServicoId(UUID ordemServicoId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "find_by_ordem", () -> findByOrdemServicoIdBlocking(ordemServicoId)));
    }

    private List<Pagamento> findByOrdemServicoIdBlocking(UUID ordemServicoId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_PAGAMENTOS_BY_ORDEM)) {
            statement.setObject(1, ordemServicoId);
            try (var resultSet = statement.executeQuery()) {
                var pagamentos = new ArrayList<Pagamento>();
                while (resultSet.next()) {
                    pagamentos.add(toPagamento(resultSet));
                }
                return List.copyOf(pagamentos);
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findByOrcamentoId(UUID orcamentoId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "find_by_orcamento", () -> findOne(SELECT_PAGAMENTO_BY_ORCAMENTO, orcamentoId)));
    }

    private Optional<Pagamento> findOne(String sql, UUID id) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(toPagamento(resultSet));
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private Optional<Pagamento> findOneByString(String sql, String value) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toPagamento(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<List<Pagamento>> findAll() {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "find_all", this::findAllBlocking));
    }

    @Override
    public CompletableFuture<UpdateResult> updateIfStatus(Pagamento pagamento, StatusPagamento expectedStatus) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE,
                RESOURCE,
                "update_if_status",
                () -> updateIfStatusBlocking(pagamento, expectedStatus)));
    }

    private UpdateResult updateIfStatusBlocking(Pagamento pagamento, StatusPagamento expectedStatus) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(UPDATE_PAGAMENTO_IF_STATUS)) {
            statement.setString(1, pagamento.status().name());
            statement.setString(2, pagamento.provedor());
            statement.setString(3, pagamento.transacaoExternaId());
            bindInstrucoesPix(statement, pagamento.instrucoesPix(), 4);
            statement.setObject(8, pagamento.atualizadoEm());
            statement.setObject(9, pagamento.pagamentoId());
            statement.setString(10, expectedStatus.name());
            if (statement.executeUpdate() > 0) {
                return new UpdateResult(pagamento, true);
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
        var atual = findOne(SELECT_PAGAMENTO_BY_ID, pagamento.pagamentoId())
                .orElseThrow(() -> new IllegalStateException("Pagamento nao encontrado apos atualizacao concorrente."));
        return new UpdateResult(atual, false);
    }

    private List<Pagamento> findAllBlocking() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_PAGAMENTOS);
                var resultSet = statement.executeQuery()) {
            var pagamentos = new ArrayList<Pagamento>();
            while (resultSet.next()) {
                pagamentos.add(toPagamento(resultSet));
            }
            return List.copyOf(pagamentos);
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private Pagamento toPagamento(java.sql.ResultSet resultSet) throws SQLException {
        return new Pagamento(
                uuid(resultSet, "id"),
                uuid(resultSet, "ordem_de_servico_id"),
                uuid(resultSet, "orcamento_id"),
                resultSet.getBigDecimal("valor"),
                MetodoPagamento.valueOf(resultSet.getString("metodo")),
                StatusPagamento.valueOf(resultSet.getString("status")),
                resultSet.getString("provedor"),
                resultSet.getString("transacao_externa_id"),
                instrucoesPix(resultSet),
                offsetDateTime(resultSet, "criado_em"),
                offsetDateTime(resultSet, "atualizado_em"));
    }

    private InstrucoesPix instrucoesPix(java.sql.ResultSet resultSet) throws SQLException {
        var copiaECola = resultSet.getString("pix_copia_e_cola");
        if (copiaECola == null || copiaECola.isBlank()) {
            return null;
        }
        return new InstrucoesPix(
                copiaECola,
                resultSet.getString("pix_qr_code_base64"),
                resultSet.getString("pix_ticket_url"),
                nullableOffsetDateTime(resultSet, "pix_expira_em"));
    }
}
