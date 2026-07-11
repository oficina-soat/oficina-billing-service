package br.com.oficina.billing.interfaces.presenters.view_model;

import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import java.math.BigDecimal;
import java.util.UUID;

public record ItemOrcamentoViewModel(
        TipoItemOrcamento tipo,
        UUID itemId,
        UUID referenciaCatalogoId,
        String nome,
        BigDecimal quantidade,
        BigDecimal valorUnitario,
        BigDecimal valorTotal) {
}
