package br.com.oficina.billing.interfaces.presenters;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.interfaces.presenters.view_model.PagamentoViewModel;
import java.util.List;

public class PagamentoPresenterAdapter {
    private PagamentoViewModel viewModel;
    private List<PagamentoViewModel> viewModels;

    public void present(Pagamento pagamento) {
        this.viewModel = toViewModel(pagamento);
    }

    public void present(List<Pagamento> pagamentos) {
        this.viewModels = pagamentos.stream()
                .map(this::toViewModel)
                .toList();
    }

    public PagamentoViewModel viewModel() {
        return viewModel;
    }

    public List<PagamentoViewModel> viewModels() {
        return viewModels;
    }

    private PagamentoViewModel toViewModel(Pagamento pagamento) {
        return new PagamentoViewModel(
                pagamento.pagamentoId(),
                pagamento.ordemServicoId(),
                pagamento.orcamentoId(),
                pagamento.valor(),
                pagamento.metodo(),
                pagamento.status(),
                pagamento.provedor(),
                pagamento.transacaoExternaId(),
                pagamento.criadoEm(),
                pagamento.atualizadoEm(),
                pagamento.acoesPermitidas());
    }
}
