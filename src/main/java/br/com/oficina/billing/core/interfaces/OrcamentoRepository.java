package br.com.oficina.billing.core.interfaces;

import br.com.oficina.billing.core.entities.Orcamento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrcamentoRepository {
    Orcamento save(Orcamento orcamento);

    Optional<Orcamento> findById(UUID orcamentoId);

    List<Orcamento> findByOrdemServicoId(UUID ordemServicoId);
}
