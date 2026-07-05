package br.com.oficina.billing.framework.db;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.interfaces.PagamentoRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryPagamentoRepository implements PagamentoRepository {
    private final ConcurrentHashMap<UUID, Pagamento> storage = new ConcurrentHashMap<>();

    @Override
    public Pagamento save(Pagamento pagamento) {
        storage.put(pagamento.pagamentoId(), pagamento);
        return pagamento;
    }

    @Override
    public Optional<Pagamento> findById(UUID pagamentoId) {
        return Optional.ofNullable(storage.get(pagamentoId));
    }

    @Override
    public List<Pagamento> findByOrdemServicoId(UUID ordemServicoId) {
        return storage.values().stream()
                .filter(pagamento -> pagamento.ordemServicoId().equals(ordemServicoId))
                .sorted(Comparator.comparing(Pagamento::criadoEm))
                .toList();
    }

    @Override
    public Optional<Pagamento> findByOrcamentoId(UUID orcamentoId) {
        return storage.values().stream()
                .filter(pagamento -> pagamento.orcamentoId().equals(orcamentoId))
                .findFirst();
    }
}
