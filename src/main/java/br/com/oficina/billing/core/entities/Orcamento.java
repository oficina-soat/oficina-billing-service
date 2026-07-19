package br.com.oficina.billing.core.entities;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Orcamento(
        UUID orcamentoId,
        UUID ordemServicoId,
        List<ItemOrcamento> itens,
        BigDecimal valorTotal,
        StatusOrcamento status,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm) {
    public List<AcaoPermitidaOrcamento> acoesPermitidas() {
        return status == StatusOrcamento.GERADO
                ? List.of(
                        AcaoPermitidaOrcamento.APROVAR,
                        AcaoPermitidaOrcamento.RECUSAR,
                        AcaoPermitidaOrcamento.REENVIAR_EMAIL)
                : List.of();
    }
}
