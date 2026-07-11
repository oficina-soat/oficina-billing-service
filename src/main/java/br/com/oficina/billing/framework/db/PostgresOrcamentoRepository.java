package br.com.oficina.billing.framework.db;

import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.offsetDateTime;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.persistenceFailure;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.uuid;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.interfaces.OrcamentoRepository;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@IfBuildProperty(name = "oficina.persistence.kind", stringValue = "postgresql", enableIfMissing = true)
public class PostgresOrcamentoRepository implements OrcamentoRepository {
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

    public PostgresOrcamentoRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Orcamento save(Orcamento orcamento) {
        try (var connection = dataSource.getConnection()) {
            var previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertOrcamento(connection, orcamento);
                replaceItens(connection, orcamento);
                connection.commit();
                return orcamento;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public Optional<Orcamento> findById(UUID orcamentoId) {
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
    public List<Orcamento> findByOrdemServicoId(UUID ordemServicoId) {
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
            for (var item : orcamento.itens()) {
                statement.setObject(1, orcamento.orcamentoId());
                statement.setString(2, item.tipo().name());
                statement.setObject(3, item.itemId());
                if (item.referenciaCatalogoId() == null) {
                    statement.setNull(4, Types.OTHER);
                } else {
                    statement.setObject(4, item.referenciaCatalogoId());
                }
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
        } catch (SQLException ignored) {
            // The original persistence failure is more useful to callers.
        }
    }
}
