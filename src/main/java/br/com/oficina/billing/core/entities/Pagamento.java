package br.com.oficina.billing.core.entities;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Pagamento(
        UUID pagamentoId,
        UUID ordemServicoId,
        UUID orcamentoId,
        BigDecimal valor,
        MetodoPagamento metodo,
        StatusPagamento status,
        String provedor,
        String transacaoExternaId,
        TipoReferenciaExternaPagamento tipoReferenciaExterna,
        InstrucoesPix instrucoesPix,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm) {
    public Pagamento(
            UUID pagamentoId,
            UUID ordemServicoId,
            UUID orcamentoId,
            BigDecimal valor,
            MetodoPagamento metodo,
            StatusPagamento status,
            String provedor,
            String transacaoExternaId,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
        this(
                pagamentoId,
                ordemServicoId,
                orcamentoId,
                valor,
                metodo,
                status,
                provedor,
                transacaoExternaId,
                tipoReferenciaExterna(provedor, transacaoExternaId),
                null,
                criadoEm,
                atualizadoEm);
    }

    public Pagamento(
            UUID pagamentoId,
            UUID ordemServicoId,
            UUID orcamentoId,
            BigDecimal valor,
            MetodoPagamento metodo,
            StatusPagamento status,
            String provedor,
            String transacaoExternaId,
            InstrucoesPix instrucoesPix,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
        this(
                pagamentoId,
                ordemServicoId,
                orcamentoId,
                valor,
                metodo,
                status,
                provedor,
                transacaoExternaId,
                tipoReferenciaExterna(provedor, transacaoExternaId),
                instrucoesPix,
                criadoEm,
                atualizadoEm);
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

    public List<AcaoPermitidaPagamento> acoesPermitidas() {
        if (status != StatusPagamento.CRIADO) {
            return List.of();
        }
        if (provedor != null && !provedor.isBlank()) {
            return List.of(AcaoPermitidaPagamento.ATUALIZAR_STATUS);
        }
        return List.of(
                AcaoPermitidaPagamento.CONFIRMAR,
                AcaoPermitidaPagamento.RECUSAR,
                AcaoPermitidaPagamento.CANCELAR);
    }
}
