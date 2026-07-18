package br.com.oficina.billing.interfaces.presenters.view_model;

import br.com.oficina.billing.core.entities.AcaoPermitidaPagamento;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PagamentoViewModel(
        UUID pagamentoId,
        UUID ordemServicoId,
        UUID orcamentoId,
        BigDecimal valor,
        MetodoPagamento metodo,
        StatusPagamento status,
        String provedor,
        String transacaoExternaId,
        InstrucoesPixViewModel instrucoesPix,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm,
        List<AcaoPermitidaPagamento> acoesPermitidas) {
    public record InstrucoesPixViewModel(
            String copiaECola,
            String qrCodeBase64,
            String ticketUrl,
            OffsetDateTime expiraEm) {
    }
}
