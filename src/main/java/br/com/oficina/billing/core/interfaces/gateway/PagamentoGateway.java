package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.Pagamento;
import java.util.concurrent.CompletableFuture;

public interface PagamentoGateway {
    CompletableFuture<PagamentoGatewayResult> solicitar(Pagamento pagamento);
}
