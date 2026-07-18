package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.Pagamento;
import java.util.concurrent.CompletableFuture;

public interface PagamentoGateway {
    CompletableFuture<PagamentoGatewayResult> solicitar(Pagamento pagamento);

    default CompletableFuture<PagamentoGatewayResult> consultar(Pagamento pagamento) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Consulta nao suportada."));
    }
}
