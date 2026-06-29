package br.com.oficina.billing.framework.db;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.interfaces.OrcamentoRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryOrcamentoRepository implements OrcamentoRepository {
    private final ConcurrentHashMap<UUID, Orcamento> storage = new ConcurrentHashMap<>();

    @Override
    public Orcamento save(Orcamento orcamento) {
        storage.put(orcamento.orcamentoId(), orcamento);
        return orcamento;
    }

    @Override
    public Optional<Orcamento> findById(UUID orcamentoId) {
        return Optional.ofNullable(storage.get(orcamentoId));
    }

    @Override
    public List<Orcamento> findByOrdemServicoId(UUID ordemServicoId) {
        return storage.values().stream()
                .filter(orcamento -> orcamento.ordemServicoId().equals(ordemServicoId))
                .sorted(Comparator.comparing(Orcamento::criadoEm))
                .toList();
    }
}
