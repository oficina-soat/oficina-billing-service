package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.entities.InstrucoesPix;

public record PagamentoGatewayResult(
        boolean integrado,
        StatusPagamento status,
        String provedor,
        String transacaoExternaId,
        String motivo,
        InstrucoesPix instrucoesPix) {
    public static PagamentoGatewayResult naoIntegrado() {
        return new PagamentoGatewayResult(false, StatusPagamento.CRIADO, null, null, null, null);
    }

    public static PagamentoGatewayResult criado(String provedor, String transacaoExternaId) {
        return criado(provedor, transacaoExternaId, null);
    }

    public static PagamentoGatewayResult criado(
            String provedor,
            String transacaoExternaId,
            InstrucoesPix instrucoesPix) {
        return new PagamentoGatewayResult(
                true, StatusPagamento.CRIADO, provedor, transacaoExternaId, null, instrucoesPix);
    }

    public static PagamentoGatewayResult confirmado(String provedor, String transacaoExternaId) {
        return new PagamentoGatewayResult(
                true, StatusPagamento.CONFIRMADO, provedor, transacaoExternaId, null, null);
    }

    public static PagamentoGatewayResult recusado(String provedor, String transacaoExternaId, String motivo) {
        return new PagamentoGatewayResult(
                true, StatusPagamento.RECUSADO, provedor, transacaoExternaId, motivo, null);
    }
}
