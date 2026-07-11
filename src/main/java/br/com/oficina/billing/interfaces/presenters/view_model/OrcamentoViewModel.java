package br.com.oficina.billing.interfaces.presenters.view_model;

import br.com.oficina.billing.core.entities.StatusOrcamento;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrcamentoViewModel(
        UUID orcamentoId,
        UUID ordemServicoId,
        List<ItemOrcamentoViewModel> itens,
        BigDecimal valorTotal,
        StatusOrcamento status,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm) {
}
