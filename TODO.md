# TODO do oficina-billing-service

## Próximas Tarefas

- [x] Criar domínio de Orçamento e itens financeiros.
- [x] Criar domínio de aprovação e recusa de orçamento.
- [x] Criar domínio de Pagamento e status financeiro.
- [x] Alinhar controllers, presenters, DTOs e validações às rotas da [OpenAPI do oficina-billing-service](../oficina-platform/contracts/openapi/oficina-billing-service.yaml).
- [x] Criar migrations e seed limpo para o database `oficina_billing`.
- [x] Implementar geração de orçamento a partir de dados da OS por consulta ou projeção local.
- [x] Implementar confirmação, recusa e cancelamento de pagamento.
- [x] Implementar Outbox para eventos financeiros.
- [x] Implementar publicação dos eventos financeiros.
- [x] Implementar consumo dos eventos de OS e Execution necessários para projeções financeiras.
- [x] Implementar integração opcional de pagamento PIX com Mercado Pago.
- [ ] Substituir repositórios em memória por adapters PostgreSQL.
- [ ] Conectar a Outbox à publicação real em SNS/SQS.
- [x] Criar testes unitários, de integração e de contrato para APIs, persistência, eventos, idempotência e fluxos financeiros da Saga.
- [x] Configurar cobertura mínima de 80% com JaCoCo e evidência no README/CI, conforme [Padrão BDD, Cobertura e Qualidade](../oficina-platform/docs/bdd-testing.md).
- [x] Validar contratos OpenAPI, schemas JSON de eventos, erro padronizado, idempotência e Saga.
- [x] Copiar e adaptar workflows de CI/CD, garantindo build, testes, Quality Gate, publicação de imagem e deploy automatizado condicionado por variáveis de ambiente.

## Eventos Produzidos

- `orcamentoGerado`
- `orcamentoAprovado`
- `orcamentoRecusado`
- `pagamentoSolicitado`
- `pagamentoConfirmado`
- `pagamentoRecusado`

## Eventos Consumidos

- `ordemDeServicoCriada`
- `pecaIncluidaNaOrdemDeServico`
- `servicoIncluidoNaOrdemDeServico`
- `diagnosticoFinalizado`
- `execucaoFinalizada`
- `ordemDeServicoFinalizada`
- `ordemDeServicoEntregue`
- `estoqueAcrescentado`
- `estoqueBaixado`
- `sagaCompensada`
- `sagaFinalizadaComSucesso`
