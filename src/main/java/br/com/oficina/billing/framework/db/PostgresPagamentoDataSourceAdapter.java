package br.com.oficina.billing.framework.db;

import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.offsetDateTime;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.persistenceFailure;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.uuid;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.framework.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

@ApplicationScoped
@IfBuildProperty(name = "oficina.persistence.kind", stringValue = "postgresql")
public class PostgresPagamentoDataSourceAdapter implements PagamentoRepositoryGateway {
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
                criado_em,
                atualizado_em
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                ordem_de_servico_id = EXCLUDED.ordem_de_servico_id,
                orcamento_id = EXCLUDED.orcamento_id,
                valor = EXCLUDED.valor,
                metodo = EXCLUDED.metodo,
                status = EXCLUDED.status,
                provedor = EXCLUDED.provedor,
                transacao_externa_id = EXCLUDED.transacao_externa_id,
                criado_em = EXCLUDED.criado_em,
                atualizado_em = EXCLUDED.atualizado_em
            """;

    private static final String SELECT_PAGAMENTO_BY_ID = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, criado_em, atualizado_em
            FROM pagamento
            WHERE id = ?
            """;

    private static final String SELECT_PAGAMENTOS_BY_ORDEM = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, criado_em, atualizado_em
            FROM pagamento
            WHERE ordem_de_servico_id = ?
            ORDER BY criado_em
            """;

    private static final String SELECT_PAGAMENTO_BY_ORCAMENTO = """
            SELECT id, ordem_de_servico_id, orcamento_id, valor, metodo, status, provedor,
                   transacao_externa_id, criado_em, atualizado_em
            FROM pagamento
            WHERE orcamento_id = ?
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
                "postgresql", "pagamento", "save", () -> saveBlocking(pagamento)));
    }

    private Pagamento saveBlocking(Pagamento pagamento) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(UPSERT_PAGAMENTO)) {
            statement.setObject(1, pagamento.pagamentoId());
            statement.setObject(2, pagamento.ordemServicoId());
            statement.setObject(3, pagamento.orcamentoId());
            statement.setBigDecimal(4, pagamento.valor());
            statement.setString(5, pagamento.metodo().name());
            statement.setString(6, pagamento.status().name());
            statement.setString(7, pagamento.provedor());
            statement.setString(8, pagamento.transacaoExternaId());
            statement.setObject(9, pagamento.criadoEm());
            statement.setObject(10, pagamento.atualizadoEm());
            statement.executeUpdate();
            return pagamento;
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findById(UUID pagamentoId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                "postgresql", "pagamento", "find_by_id", () -> findOne(SELECT_PAGAMENTO_BY_ID, pagamentoId)));
    }

    @Override
    public CompletableFuture<List<Pagamento>> findByOrdemServicoId(UUID ordemServicoId) {
        return CompletableFuture.completedFuture(metrics.persistence(
                "postgresql", "pagamento", "find_by_ordem", () -> findByOrdemServicoIdBlocking(ordemServicoId)));
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
                "postgresql", "pagamento", "find_by_orcamento", () -> findOne(SELECT_PAGAMENTO_BY_ORCAMENTO, orcamentoId)));
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
                offsetDateTime(resultSet, "criado_em"),
                offsetDateTime(resultSet, "atualizado_em"));
    }
}
