package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.entities.InstrucoesPix;
import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;

public record PagamentoGatewayResult(
        boolean integrado,
        StatusPagamento status,
        String provedor,
        String transacaoExternaId,
        TipoReferenciaExternaPagamento tipoReferenciaExterna,
        String motivo,
        InstrucoesPix instrucoesPix) {
    public static PagamentoGatewayResult naoIntegrado() {
        return new PagamentoGatewayResult(false, StatusPagamento.CRIADO, null, null, null, null, null);
    }

    public static PagamentoGatewayResult criado(String provedor, String transacaoExternaId) {
        return criado(provedor, transacaoExternaId, null);
    }

    public static PagamentoGatewayResult criado(
            String provedor,
            String transacaoExternaId,
            InstrucoesPix instrucoesPix) {
        return criado(
                provedor,
                transacaoExternaId,
                tipoReferenciaExterna(provedor, transacaoExternaId),
                instrucoesPix);
    }

    public static PagamentoGatewayResult criado(
            String provedor,
            String transacaoExternaId,
            TipoReferenciaExternaPagamento tipoReferenciaExterna,
            InstrucoesPix instrucoesPix) {
        return new PagamentoGatewayResult(
                true,
                StatusPagamento.CRIADO,
                provedor,
                transacaoExternaId,
                tipoReferenciaExterna,
                null,
                instrucoesPix);
    }

    public static PagamentoGatewayResult confirmado(String provedor, String transacaoExternaId) {
        return confirmado(provedor, transacaoExternaId, tipoReferenciaExterna(provedor, transacaoExternaId));
    }

    public static PagamentoGatewayResult confirmado(
            String provedor,
            String transacaoExternaId,
            TipoReferenciaExternaPagamento tipoReferenciaExterna) {
        return new PagamentoGatewayResult(
                true,
                StatusPagamento.CONFIRMADO,
                provedor,
                transacaoExternaId,
                tipoReferenciaExterna,
                null,
                null);
    }

    public static PagamentoGatewayResult recusado(String provedor, String transacaoExternaId, String motivo) {
        return recusado(
                provedor,
                transacaoExternaId,
                tipoReferenciaExterna(provedor, transacaoExternaId),
                motivo);
    }

    public static PagamentoGatewayResult recusado(
            String provedor,
            String transacaoExternaId,
            TipoReferenciaExternaPagamento tipoReferenciaExterna,
            String motivo) {
        return new PagamentoGatewayResult(
                true,
                StatusPagamento.RECUSADO,
                provedor,
                transacaoExternaId,
                tipoReferenciaExterna,
                motivo,
                null);
    }

    private static TipoReferenciaExternaPagamento tipoReferenciaExterna(
            String provedor,
            String transacaoExternaId) {
        return "mercado-pago".equalsIgnoreCase(provedor)
                        && transacaoExternaId != null
                        && !transacaoExternaId.isBlank()
                ? TipoReferenciaExternaPagamento.PAYMENT
                : null;
    }
}
