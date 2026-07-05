package br.com.oficina.billing.core.interfaces;

import br.com.oficina.billing.core.entities.Pagamento;

public interface PagamentoGateway {
    PagamentoGatewayResult solicitar(Pagamento pagamento);
}
