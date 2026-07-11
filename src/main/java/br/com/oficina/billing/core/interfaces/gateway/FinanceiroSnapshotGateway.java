package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface FinanceiroSnapshotGateway {
    CompletableFuture<List<ItemOrcamento>> snapshotFinanceiro(UUID ordemServicoId);
}
