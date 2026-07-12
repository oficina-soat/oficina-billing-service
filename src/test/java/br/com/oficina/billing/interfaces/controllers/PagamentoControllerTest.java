package br.com.oficina.billing.interfaces.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.usecases.pagamento.CancelarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConfirmarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConsultarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConsultarPagamentosDaOrdemServicoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.RecusarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.RegistrarPagamentoUseCase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PagamentoControllerTest {

    @Test
    void deveValidarCadastroDePagamento() {
        var controller = controller(new RecordingRegistrarPagamentoUseCase());
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();

        assertThrows(BusinessException.class, () -> controller.registrarPagamento(null));
        assertThrows(BusinessException.class, () -> controller.registrarPagamento(
                new PagamentoController.PagamentoCreateRequest(null, orcamentoId, BigDecimal.TEN, MetodoPagamento.PIX)));
        assertThrows(BusinessException.class, () -> controller.registrarPagamento(
                new PagamentoController.PagamentoCreateRequest(ordemServicoId, null, BigDecimal.TEN, MetodoPagamento.PIX)));
        assertThrows(BusinessException.class, () -> controller.registrarPagamento(
                new PagamentoController.PagamentoCreateRequest(ordemServicoId, orcamentoId, null, MetodoPagamento.PIX)));
        assertThrows(BusinessException.class, () -> controller.registrarPagamento(
                new PagamentoController.PagamentoCreateRequest(ordemServicoId, orcamentoId, BigDecimal.TEN, null)));
    }

    @Test
    void deveDelegarOperacoesParaUseCases() {
        var registrar = new RecordingRegistrarPagamentoUseCase();
        var consultar = new RecordingConsultarPagamentoUseCase();
        var consultarDaOrdem = new RecordingConsultarPagamentosDaOrdemServicoUseCase();
        var confirmar = new RecordingConfirmarPagamentoUseCase();
        var recusar = new RecordingRecusarPagamentoUseCase();
        var cancelar = new RecordingCancelarPagamentoUseCase();
        var controller = new PagamentoController(
                registrar,
                consultar,
                consultarDaOrdem,
                confirmar,
                recusar,
                cancelar);
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var pagamentoId = UUID.randomUUID();
        var request = new PagamentoController.PagamentoCreateRequest(
                ordemServicoId,
                orcamentoId,
                BigDecimal.TEN,
                MetodoPagamento.PIX);

        assertSame(registrar.pagamento, controller.registrarPagamento(request).join());
        assertEquals(ordemServicoId, registrar.command.ordemServicoId());
        assertEquals(orcamentoId, registrar.command.orcamentoId());
        assertEquals(BigDecimal.TEN, registrar.command.valor());
        assertEquals(MetodoPagamento.PIX, registrar.command.metodo());

        assertSame(consultar.pagamento, controller.consultarPagamento(pagamentoId).join());
        assertEquals(pagamentoId, consultar.command.pagamentoId());

        assertEquals(List.of(consultarDaOrdem.pagamento), controller.consultarPagamentosDaOrdemServico(ordemServicoId).join());
        assertEquals(ordemServicoId, consultarDaOrdem.command.ordemServicoId());

        controller.confirmarPagamento(pagamentoId, null).join();
        assertEquals(pagamentoId, confirmar.command.pagamentoId());
        assertNull(confirmar.command.provedor());
        assertNull(confirmar.command.transacaoExternaId());

        controller.confirmarPagamento(
                pagamentoId,
                new PagamentoController.ConfirmacaoPagamentoRequest("mercado-pago", "mp-001")).join();
        assertEquals("mercado-pago", confirmar.command.provedor());
        assertEquals("mp-001", confirmar.command.transacaoExternaId());

        controller.recusarPagamento(pagamentoId, null).join();
        assertEquals(pagamentoId, recusar.command.pagamentoId());
        assertNull(recusar.command.provedor());
        assertNull(recusar.command.motivo());

        controller.recusarPagamento(
                pagamentoId,
                new PagamentoController.RecusaPagamentoRequest("mercado-pago", "Recusado")).join();
        assertEquals("mercado-pago", recusar.command.provedor());
        assertEquals("Recusado", recusar.command.motivo());

        controller.cancelarPagamento(pagamentoId, null).join();
        assertEquals(pagamentoId, cancelar.command.pagamentoId());
        assertNull(cancelar.command.motivo());

        controller.cancelarPagamento(pagamentoId, new PagamentoController.CancelamentoRequest("Cliente desistiu")).join();
        assertEquals("Cliente desistiu", cancelar.command.motivo());
    }

    private static PagamentoController controller(RecordingRegistrarPagamentoUseCase registrar) {
        return new PagamentoController(
                registrar,
                new RecordingConsultarPagamentoUseCase(),
                new RecordingConsultarPagamentosDaOrdemServicoUseCase(),
                new RecordingConfirmarPagamentoUseCase(),
                new RecordingRecusarPagamentoUseCase(),
                new RecordingCancelarPagamentoUseCase());
    }

    private static Pagamento pagamento() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Pagamento(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.TEN,
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                null,
                null,
                now,
                now);
    }

    private static final class RecordingRegistrarPagamentoUseCase extends RegistrarPagamentoUseCase {
        private final Pagamento pagamento = pagamento();
        private Command command;

        private RecordingRegistrarPagamentoUseCase() {
            super(null, null, null, null);
        }

        @Override
        public CompletableFuture<Pagamento> executar(Command command) {
            this.command = command;
            return CompletableFuture.completedFuture(pagamento);
        }
    }

    private static final class RecordingConsultarPagamentoUseCase extends ConsultarPagamentoUseCase {
        private final Pagamento pagamento = pagamento();
        private Command command;

        private RecordingConsultarPagamentoUseCase() {
            super(null);
        }

        @Override
        public CompletableFuture<Pagamento> executar(Command command) {
            this.command = command;
            return CompletableFuture.completedFuture(pagamento);
        }
    }

    private static final class RecordingConsultarPagamentosDaOrdemServicoUseCase
            extends ConsultarPagamentosDaOrdemServicoUseCase {
        private final Pagamento pagamento = pagamento();
        private Command command;

        private RecordingConsultarPagamentosDaOrdemServicoUseCase() {
            super(null);
        }

        @Override
        public CompletableFuture<List<Pagamento>> executar(Command command) {
            this.command = command;
            return CompletableFuture.completedFuture(List.of(pagamento));
        }
    }

    private static final class RecordingConfirmarPagamentoUseCase extends ConfirmarPagamentoUseCase {
        private final Pagamento pagamento = pagamento();
        private Command command;

        private RecordingConfirmarPagamentoUseCase() {
            super(null, null);
        }

        @Override
        public CompletableFuture<Pagamento> executar(Command command) {
            this.command = command;
            return CompletableFuture.completedFuture(pagamento);
        }
    }

    private static final class RecordingRecusarPagamentoUseCase extends RecusarPagamentoUseCase {
        private final Pagamento pagamento = pagamento();
        private Command command;

        private RecordingRecusarPagamentoUseCase() {
            super(null, null);
        }

        @Override
        public CompletableFuture<Pagamento> executar(Command command) {
            this.command = command;
            return CompletableFuture.completedFuture(pagamento);
        }
    }

    private static final class RecordingCancelarPagamentoUseCase extends CancelarPagamentoUseCase {
        private final Pagamento pagamento = pagamento();
        private Command command;

        private RecordingCancelarPagamentoUseCase() {
            super(null);
        }

        @Override
        public CompletableFuture<Pagamento> executar(Command command) {
            this.command = command;
            return CompletableFuture.completedFuture(pagamento);
        }
    }
}
