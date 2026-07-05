package br.com.oficina.billing.core.entities;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
}
