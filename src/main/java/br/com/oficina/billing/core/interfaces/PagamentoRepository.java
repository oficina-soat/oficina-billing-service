package br.com.oficina.billing.core.interfaces;

import br.com.oficina.billing.core.entities.Pagamento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PagamentoRepository {
    Pagamento save(Pagamento pagamento);

    Optional<Pagamento> findById(UUID pagamentoId);

    List<Pagamento> findByOrdemServicoId(UUID ordemServicoId);
}
