package br.com.oficina.billing.core.interfaces;

import br.com.oficina.billing.core.entities.StatusPagamento;

public record PagamentoGatewayResult(
        boolean integrado,
        StatusPagamento status,
        String provedor,
        String transacaoExternaId,
        String motivo) {
    public static PagamentoGatewayResult naoIntegrado() {
        return new PagamentoGatewayResult(false, StatusPagamento.CRIADO, null, null, null);
    }

    public static PagamentoGatewayResult criado(String provedor, String transacaoExternaId) {
        return new PagamentoGatewayResult(true, StatusPagamento.CRIADO, provedor, transacaoExternaId, null);
    }

    public static PagamentoGatewayResult confirmado(String provedor, String transacaoExternaId) {
        return new PagamentoGatewayResult(true, StatusPagamento.CONFIRMADO, provedor, transacaoExternaId, null);
    }

    public static PagamentoGatewayResult recusado(String provedor, String transacaoExternaId, String motivo) {
        return new PagamentoGatewayResult(true, StatusPagamento.RECUSADO, provedor, transacaoExternaId, motivo);
    }
}
