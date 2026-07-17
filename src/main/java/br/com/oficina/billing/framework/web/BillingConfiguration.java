package br.com.oficina.billing.framework.web;

import br.com.oficina.billing.core.interfaces.gateway.FinanceiroSnapshotGateway;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import br.com.oficina.billing.core.interfaces.sender.OrcamentoApprovalSender;
import br.com.oficina.billing.core.usecases.orcamento.AprovarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.dashboard.ConsultarDashboardFaturamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.ConsultarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.ConsultarOrcamentosDaOrdemServicoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.GerarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.RecusarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.CancelarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.CancelarPagamentosCriadosDaOrdemUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConfirmarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConsultarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConsultarPagamentosDaOrdemServicoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.RecusarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.RegistrarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.SolicitarPagamentoDaOrdemUseCase;
import br.com.oficina.billing.interfaces.controllers.OrcamentoController;
import br.com.oficina.billing.interfaces.controllers.PagamentoController;
import br.com.oficina.billing.interfaces.controllers.StatusController;
import br.com.oficina.billing.interfaces.presenters.OrcamentoPresenterAdapter;
import br.com.oficina.billing.interfaces.presenters.PagamentoPresenterAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class BillingConfiguration {
    @Produces
    @ApplicationScoped
    ConsultarDashboardFaturamentoUseCase consultarDashboardFaturamentoUseCase(
            OrcamentoRepositoryGateway orcamentos,
            PagamentoRepositoryGateway pagamentos) {
        return new ConsultarDashboardFaturamentoUseCase(orcamentos, pagamentos);
    }

    @Produces
    @ApplicationScoped
    GerarOrcamentoUseCase gerarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            FinanceiroSnapshotGateway financeiroSnapshotGateway,
            OutboxEventSender outboxEventSender,
            OrcamentoApprovalSender approvalSender) {
        return new GerarOrcamentoUseCase(repository, financeiroSnapshotGateway, outboxEventSender, approvalSender);
    }

    @Produces
    @ApplicationScoped
    ConsultarOrcamentoUseCase consultarOrcamentoUseCase(OrcamentoRepositoryGateway repository) {
        return new ConsultarOrcamentoUseCase(repository);
    }

    @Produces
    @ApplicationScoped
    ConsultarOrcamentosDaOrdemServicoUseCase consultarOrcamentosDaOrdemServicoUseCase(
            OrcamentoRepositoryGateway repository) {
        return new ConsultarOrcamentosDaOrdemServicoUseCase(repository);
    }

    @Produces
    @ApplicationScoped
    AprovarOrcamentoUseCase aprovarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            OutboxEventSender outboxEventSender) {
        return new AprovarOrcamentoUseCase(repository, outboxEventSender);
    }

    @Produces
    @ApplicationScoped
    RecusarOrcamentoUseCase recusarOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            OutboxEventSender outboxEventSender) {
        return new RecusarOrcamentoUseCase(repository, outboxEventSender);
    }

    @Produces
    @ApplicationScoped
    RegistrarPagamentoUseCase registrarPagamentoUseCase(
            PagamentoRepositoryGateway pagamentoRepository,
            OrcamentoRepositoryGateway orcamentoRepository,
            OutboxEventSender outboxEventSender,
            PagamentoGateway pagamentoGateway) {
        return new RegistrarPagamentoUseCase(
                pagamentoRepository,
                orcamentoRepository,
                outboxEventSender,
                pagamentoGateway);
    }

    @Produces
    @ApplicationScoped
    ConsultarPagamentoUseCase consultarPagamentoUseCase(PagamentoRepositoryGateway repository) {
        return new ConsultarPagamentoUseCase(repository);
    }

    @Produces
    @ApplicationScoped
    ConsultarPagamentosDaOrdemServicoUseCase consultarPagamentosDaOrdemServicoUseCase(
            PagamentoRepositoryGateway repository) {
        return new ConsultarPagamentosDaOrdemServicoUseCase(repository);
    }

    @Produces
    @ApplicationScoped
    ConfirmarPagamentoUseCase confirmarPagamentoUseCase(
            PagamentoRepositoryGateway repository,
            OutboxEventSender outboxEventSender) {
        return new ConfirmarPagamentoUseCase(repository, outboxEventSender);
    }

    @Produces
    @ApplicationScoped
    RecusarPagamentoUseCase recusarPagamentoUseCase(
            PagamentoRepositoryGateway repository,
            OutboxEventSender outboxEventSender) {
        return new RecusarPagamentoUseCase(repository, outboxEventSender);
    }

    @Produces
    @ApplicationScoped
    CancelarPagamentoUseCase cancelarPagamentoUseCase(PagamentoRepositoryGateway repository) {
        return new CancelarPagamentoUseCase(repository);
    }

    @Produces
    @ApplicationScoped
    SolicitarPagamentoDaOrdemUseCase solicitarPagamentoDaOrdemUseCase(
            OrcamentoRepositoryGateway orcamentoRepository,
            PagamentoRepositoryGateway pagamentoRepository,
            RegistrarPagamentoUseCase registrarPagamentoUseCase) {
        return new SolicitarPagamentoDaOrdemUseCase(
                orcamentoRepository,
                pagamentoRepository,
                registrarPagamentoUseCase);
    }

    @Produces
    @ApplicationScoped
    CancelarPagamentosCriadosDaOrdemUseCase cancelarPagamentosCriadosDaOrdemUseCase(
            PagamentoRepositoryGateway repository) {
        return new CancelarPagamentosCriadosDaOrdemUseCase(repository);
    }

    @Produces
    @ApplicationScoped
    OrcamentoController orcamentoController(
            GerarOrcamentoUseCase gerarOrcamentoUseCase,
            ConsultarOrcamentoUseCase consultarOrcamentoUseCase,
            ConsultarOrcamentosDaOrdemServicoUseCase consultarOrcamentosDaOrdemServicoUseCase,
            AprovarOrcamentoUseCase aprovarOrcamentoUseCase,
            RecusarOrcamentoUseCase recusarOrcamentoUseCase) {
        return new OrcamentoController(
                gerarOrcamentoUseCase,
                consultarOrcamentoUseCase,
                consultarOrcamentosDaOrdemServicoUseCase,
                aprovarOrcamentoUseCase,
                recusarOrcamentoUseCase);
    }

    @Produces
    @ApplicationScoped
    PagamentoController pagamentoController(
            RegistrarPagamentoUseCase registrarPagamentoUseCase,
            ConsultarPagamentoUseCase consultarPagamentoUseCase,
            ConsultarPagamentosDaOrdemServicoUseCase consultarPagamentosDaOrdemServicoUseCase,
            ConfirmarPagamentoUseCase confirmarPagamentoUseCase,
            RecusarPagamentoUseCase recusarPagamentoUseCase,
            CancelarPagamentoUseCase cancelarPagamentoUseCase) {
        return new PagamentoController(
                registrarPagamentoUseCase,
                consultarPagamentoUseCase,
                consultarPagamentosDaOrdemServicoUseCase,
                confirmarPagamentoUseCase,
                recusarPagamentoUseCase,
                cancelarPagamentoUseCase);
    }

    @Produces
    @ApplicationScoped
    StatusController statusController() {
        return new StatusController();
    }

    @Produces
    @RequestScoped
    OrcamentoPresenterAdapter orcamentoPresenter() {
        return new OrcamentoPresenterAdapter();
    }

    @Produces
    @RequestScoped
    PagamentoPresenterAdapter pagamentoPresenter() {
        return new PagamentoPresenterAdapter();
    }
}
