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
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm) {
    public List<AcaoPermitidaPagamento> acoesPermitidas() {
        return status == StatusPagamento.CRIADO
                ? List.of(
                        AcaoPermitidaPagamento.CONFIRMAR,
                        AcaoPermitidaPagamento.RECUSAR,
                        AcaoPermitidaPagamento.CANCELAR)
                : List.of();
    }
}
