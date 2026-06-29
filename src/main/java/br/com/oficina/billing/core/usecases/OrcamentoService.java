package br.com.oficina.billing.core.usecases;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.OrcamentoRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrcamentoService {
    private final OrcamentoRepository repository;
    private final Clock clock;

    public OrcamentoService(OrcamentoRepository repository) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
    }

    public Orcamento gerar(UUID ordemServicoId) {
        var now = OffsetDateTime.now(clock);
        var item = new ItemOrcamento(
                TipoItemOrcamento.SERVICO,
                UUID.randomUUID(),
                null,
                "Snapshot financeiro inicial",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        var orcamento = new Orcamento(
                UUID.randomUUID(),
                ordemServicoId,
                List.of(item),
                BigDecimal.ZERO,
                StatusOrcamento.GERADO,
                now,
                now);
        return repository.save(orcamento);
    }

    public Orcamento consultar(UUID orcamentoId) {
        return repository.findById(orcamentoId)
                .orElseThrow(() -> new ResourceNotFoundException("Orcamento nao encontrado."));
    }

    public List<Orcamento> consultarPorOrdemServico(UUID ordemServicoId) {
        return repository.findByOrdemServicoId(ordemServicoId);
    }

    public Orcamento aprovar(UUID orcamentoId) {
        var orcamento = consultar(orcamentoId);
        if (orcamento.status() != StatusOrcamento.GERADO) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Somente orcamentos gerados podem ser aprovados.");
        }
        return atualizarStatus(orcamento, StatusOrcamento.APROVADO);
    }

    public Orcamento recusar(UUID orcamentoId) {
        var orcamento = consultar(orcamentoId);
        if (orcamento.status() != StatusOrcamento.GERADO) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Somente orcamentos gerados podem ser recusados.");
        }
        return atualizarStatus(orcamento, StatusOrcamento.RECUSADO);
    }

    private Orcamento atualizarStatus(Orcamento orcamento, StatusOrcamento status) {
        var atualizado = new Orcamento(
                orcamento.orcamentoId(),
                orcamento.ordemServicoId(),
                orcamento.itens(),
                orcamento.valorTotal(),
                status,
                orcamento.criadoEm(),
                OffsetDateTime.now(clock));
        return repository.save(atualizado);
    }
}
