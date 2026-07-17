package br.com.oficina.billing.core.interfaces.sender;

import br.com.oficina.billing.core.entities.Orcamento;
import java.util.concurrent.CompletableFuture;

public interface OrcamentoApprovalSender {
    CompletableFuture<Void> enviar(Orcamento orcamento);

    static OrcamentoApprovalSender noop() {
        return ignored -> CompletableFuture.completedFuture(null);
    }
}
