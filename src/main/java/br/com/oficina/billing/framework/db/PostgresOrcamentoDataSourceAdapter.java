package br.com.oficina.billing.framework.db;

import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.offsetDateTime;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.persistenceFailure;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.uuid;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.framework.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

@ApplicationScoped
@IfBuildProperty(name = "oficina.persistence.kind", stringValue = "postgresql")
public class PostgresOrcamentoDataSourceAdapter implements OrcamentoRepositoryGateway {
    private static final String DATABASE = "postgresql";
    private static final String RESOURCE = "orcamento";
    private static final String UPSERT_ORCAMENTO = """
            INSERT INTO orcamento (
                id,
                ordem_de_servico_id,
                valor_total,
                status,
                criado_em,
                atualizado_em
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                ordem_de_servico_id = EXCLUDED.ordem_de_servico_id,
                valor_total = EXCLUDED.valor_total,
                status = EXCLUDED.status,
                criado_em = EXCLUDED.criado_em,
                atualizado_em = EXCLUDED.atualizado_em
            """;

    private static final String DELETE_ITENS = "DELETE FROM orcamento_item WHERE orcamento_id = ?";

    private static final String INSERT_ITEM = """
            INSERT INTO orcamento_item (
                orcamento_id,
                tipo,
                item_id,
                referencia_catalogo_id,
                nome,
                quantidade,
                valor_unitario,
                valor_total
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ORCAMENTO_BY_ID = """
            SELECT id, ordem_de_servico_id, valor_total, status, criado_em, atualizado_em
            FROM orcamento
            WHERE id = ?
            """;

    private static final String SELECT_ORCAMENTOS_BY_ORDEM = """
            SELECT id, ordem_de_servico_id, valor_total, status, criado_em, atualizado_em
            FROM orcamento
            WHERE ordem_de_servico_id = ?
            ORDER BY criado_em
            """;

    private static final String SELECT_ITENS = """
            SELECT tipo, item_id, referencia_catalogo_id, nome, quantidade, valor_unitario, valor_total
            FROM orcamento_item
            WHERE orcamento_id = ?
            ORDER BY id
            """;

    private final DataSource dataSource;
    private final OperationalMetrics metrics;

    public PostgresOrcamentoDataSourceAdapter(DataSource dataSource) {
        this(dataSource, new OperationalMetrics(new SimpleMeterRegistry(), "oficina-billing-service"));
    }

    @Inject
    public PostgresOrcamentoDataSourceAdapter(DataSource dataSource, OperationalMetrics metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    @Override
    public CompletableFuture<Orcamento> save(Orcamento orcamento) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "save", () -> saveBlocking(orcamento)));
    }

    private Orcamento saveBlocking(Orcamento orcamento) {
        return inTransaction(connection -> {
            upsertOrcamento(connection, orcamento);
            replaceItens(connection, orcamento);
            return orcamento;
        });
    }

    @Override
    public CompletableFuture<Optional<Orcamento>> findById(UUID orcamentoId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "find_by_id", () -> findByIdBlocking(orcamentoId)));
    }

    private Optional<Orcamento> findByIdBlocking(UUID orcamentoId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_ORCAMENTO_BY_ID)) {
            statement.setObject(1, orcamentoId);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(toOrcamento(connection, resultSet));
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<List<Orcamento>> findByOrdemServicoId(UUID ordemServicoId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                DATABASE, RESOURCE, "find_by_ordem", () -> findByOrdemServicoIdBlocking(ordemServicoId)));
    }

    private List<Orcamento> findByOrdemServicoIdBlocking(UUID ordemServicoId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_ORCAMENTOS_BY_ORDEM)) {
            statement.setObject(1, ordemServicoId);
            try (var resultSet = statement.executeQuery()) {
                var orcamentos = new ArrayList<Orcamento>();
                while (resultSet.next()) {
                    orcamentos.add(toOrcamento(connection, resultSet));
                }
                return List.copyOf(orcamentos);
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private void upsertOrcamento(Connection connection, Orcamento orcamento) throws SQLException {
        try (var statement = connection.prepareStatement(UPSERT_ORCAMENTO)) {
            statement.setObject(1, orcamento.orcamentoId());
            statement.setObject(2, orcamento.ordemServicoId());
            statement.setBigDecimal(3, orcamento.valorTotal());
            statement.setString(4, orcamento.status().name());
            statement.setObject(5, orcamento.criadoEm());
            statement.setObject(6, orcamento.atualizadoEm());
            statement.executeUpdate();
        }
    }

    private void replaceItens(Connection connection, Orcamento orcamento) throws SQLException {
        try (var statement = connection.prepareStatement(DELETE_ITENS)) {
            statement.setObject(1, orcamento.orcamentoId());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement(INSERT_ITEM)) {
            statement.setObject(1, orcamento.orcamentoId());
            for (var item : orcamento.itens()) {
                statement.setString(2, item.tipo().name());
                statement.setObject(3, item.itemId());
                statement.setObject(4, item.referenciaCatalogoId(), Types.OTHER);
                statement.setString(5, item.nome());
                statement.setBigDecimal(6, item.quantidade());
                statement.setBigDecimal(7, item.valorUnitario());
                statement.setBigDecimal(8, item.valorTotal());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Orcamento toOrcamento(Connection connection, java.sql.ResultSet resultSet) throws SQLException {
        var orcamentoId = uuid(resultSet, "id");
        return new Orcamento(
                orcamentoId,
                uuid(resultSet, "ordem_de_servico_id"),
                loadItens(connection, orcamentoId),
                resultSet.getBigDecimal("valor_total"),
                StatusOrcamento.valueOf(resultSet.getString("status")),
                offsetDateTime(resultSet, "criado_em"),
                offsetDateTime(resultSet, "atualizado_em"));
    }

    private List<ItemOrcamento> loadItens(Connection connection, UUID orcamentoId) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT_ITENS)) {
            statement.setObject(1, orcamentoId);
            try (var resultSet = statement.executeQuery()) {
                var itens = new ArrayList<ItemOrcamento>();
                while (resultSet.next()) {
                    itens.add(new ItemOrcamento(
                            TipoItemOrcamento.valueOf(resultSet.getString("tipo")),
                            uuid(resultSet, "item_id"),
                            uuid(resultSet, "referencia_catalogo_id"),
                            resultSet.getString("nome"),
                            resultSet.getBigDecimal("quantidade"),
                            resultSet.getBigDecimal("valor_unitario"),
                            resultSet.getBigDecimal("valor_total")));
                }
                return List.copyOf(itens);
            }
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException _) {
            // The original persistence failure is more useful to callers.
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (var connection = dataSource.getConnection()) {
            return executeInTransaction(connection, operation);
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private <T> T executeInTransaction(Connection connection, SqlOperation<T> operation) throws SQLException {
        var previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            var result = operation.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
