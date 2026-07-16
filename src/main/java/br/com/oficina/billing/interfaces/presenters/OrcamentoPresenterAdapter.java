package br.com.oficina.billing.interfaces.presenters;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.interfaces.presenters.view_model.ItemOrcamentoViewModel;
import br.com.oficina.billing.interfaces.presenters.view_model.OrcamentoViewModel;
import java.util.List;

public class OrcamentoPresenterAdapter {
    private OrcamentoViewModel viewModel;
    private List<OrcamentoViewModel> viewModels;

    public void present(Orcamento orcamento) {
        this.viewModel = toViewModel(orcamento);
    }

    public void present(List<Orcamento> orcamentos) {
        this.viewModels = orcamentos.stream()
                .map(this::toViewModel)
                .toList();
    }

    public OrcamentoViewModel viewModel() {
        return viewModel;
    }

    public List<OrcamentoViewModel> viewModels() {
        return viewModels;
    }

    private OrcamentoViewModel toViewModel(Orcamento orcamento) {
        return new OrcamentoViewModel(
                orcamento.orcamentoId(),
                orcamento.ordemServicoId(),
                orcamento.itens().stream().map(this::toViewModel).toList(),
                orcamento.valorTotal(),
                orcamento.status(),
                orcamento.criadoEm(),
                orcamento.atualizadoEm(),
                orcamento.acoesPermitidas());
    }

    private ItemOrcamentoViewModel toViewModel(ItemOrcamento item) {
        return new ItemOrcamentoViewModel(
                item.tipo(),
                item.itemId(),
                item.referenciaCatalogoId(),
                item.nome(),
                item.quantidade(),
                item.valorUnitario(),
                item.valorTotal());
    }
}
