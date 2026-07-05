package br.com.oficina.billing.core.entities;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemOrcamento(
        TipoItemOrcamento tipo,
        UUID itemId,
        UUID referenciaCatalogoId,
        String nome,
        BigDecimal quantidade,
        BigDecimal valorUnitario,
        BigDecimal valorTotal) {
}
