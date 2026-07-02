# oficina-billing-service

Microsserviço responsável por orçamento, aprovação, recusa, pagamento e integração financeira da plataforma de oficina.

Este repositório segue a governança definida em [../oficina-platform](../oficina-platform/). Para tarefas automatizadas, leia também [AGENTS.md](AGENTS.md) e [TODO.md](TODO.md).

## Responsabilidades

- gerar e consultar orçamentos;
- aprovar e recusar orçamentos;
- registrar, confirmar, recusar e cancelar pagamentos;
- manter histórico e status financeiro da OS;
- integrar com provedor financeiro quando aplicável;
- produzir e consumir eventos financeiros usados pela Saga.

O serviço não é dono de Cliente, Veículo, Ordem de Serviço, catálogo técnico, estoque, diagnóstico ou execução.

## Stack

- Java 25
- Quarkus 3.37.0
- PostgreSQL no database `oficina_billing`
- Flyway para migrations
- JWT, OpenAPI, Health, métricas Prometheus, logs JSON e OpenTelemetry

## Execução local

```bash
./mvnw test -Ppostgresql
./mvnw package -Ppostgresql
```

## Cobertura

O JaCoCo é executado no `verify`, gera relatório em `target/jacoco-report/` e falha o build quando a cobertura de instruções do bundle fica abaixo de 80%. O [Template GitHub Actions para Microsserviços](../oficina-platform/templates/github-actions/README.md) publica esse diretório como artifact `jacoco-report-oficina-billing-service`.

Evidência local de cobertura em 2026-07-01:

```text
./mvnw -B verify -Ppostgresql -DskipITs=false -DfailIfNoTests=false
instruction=84.55% branch=59.91% line=86.36% complexity=60.47%
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Validação de contratos

O teste [PlatformContractsTest](src/test/java/br/com/oficina/billing/contracts/PlatformContractsTest.java) valida o serviço contra os contratos canônicos em `../oficina-platform/contracts`: OpenAPI, schemas JSON de eventos, [Contrato de Erros REST](../oficina-platform/contracts/error-model.md), [Contrato de Idempotência](../oficina-platform/contracts/idempotency.md) e [Contrato de Saga do oficina-os-service](../oficina-platform/contracts/saga/oficina-os-saga-v1.md).

## Docker

```bash
docker build --build-arg MAVEN_PROFILE=postgresql -t oficina-billing-service:local .
docker run --rm -p 8080:8080 oficina-billing-service:local
```

## Endpoint técnico

- `GET /api/v1/status`: expõe identidade do serviço, ambiente e status técnico básico.

Health checks do Quarkus ficam em `/q/health`, `/q/health/live` e `/q/health/ready`.

## APIs financeiras

O domínio financeiro inicial expõe as rotas canônicas da OpenAPI:

- `POST /api/v1/orcamentos`
- `GET /api/v1/orcamentos/{orcamentoId}`
- `GET /api/v1/ordens-servico/{ordemServicoId}/orcamentos`
- `POST /api/v1/orcamentos/{orcamentoId}/aprovacao`
- `POST /api/v1/orcamentos/{orcamentoId}/recusa`
- `POST /api/v1/pagamentos`
- `GET /api/v1/pagamentos/{pagamentoId}`
- `GET /api/v1/ordens-servico/{ordemServicoId}/pagamentos`
- `POST /api/v1/pagamentos/{pagamentoId}/confirmacao`
- `POST /api/v1/pagamentos/{pagamentoId}/recusa`
- `POST /api/v1/pagamentos/{pagamentoId}/cancelamento`

Nesta primeira versão, os repositórios são em memória para validar domínio, controllers e transições de estado. As migrations e o seed limpo do PostgreSQL já existem em `src/main/resources/db/migration/`; os adapters PostgreSQL e a publicação real em SNS/SQS permanecem nos próximos incrementos do backlog.

O serviço já mantém uma projeção financeira local alimentada por eventos, gera orçamento a partir do snapshot de peças e serviços da OS, registra eventos financeiros em Outbox e possui consumer idempotente para os eventos de Saga definidos na plataforma.

## Integração Mercado Pago

A integração com Mercado Pago é opcional em ambiente local e fica desabilitada por padrão. Quando `OFICINA_MERCADO_PAGO_ENABLED=true`, o registro de pagamento PIX em `POST /api/v1/pagamentos` cria uma cobrança no endpoint `/v1/payments` do Mercado Pago usando `Authorization: Bearer` e `X-Idempotency-Key` com o `pagamentoId`.

Mapeamento de status do provedor:

- `approved`: pagamento local `CONFIRMADO` e evento `pagamentoConfirmado`;
- `rejected`, `cancelled`, `refunded` ou `charged_back`: pagamento local `RECUSADO` e evento `pagamentoRecusado`;
- demais estados, como `pending` e `in_process`: pagamento local permanece `CRIADO` com `provedor=mercado-pago` e `transacaoExternaId`.

Na Fase 4, a chamada direta ao Mercado Pago cobre PIX. Cartão exige tokenização/dados de captura fora do contrato REST atual e deve permanecer para incremento posterior ou fluxo operacional manual.

## Contratos

- [Contrato de APIs REST](../oficina-platform/contracts/Contrato%20de%20APIs%20REST.md)
- [OpenAPI do oficina-billing-service](../oficina-platform/contracts/openapi/oficina-billing-service.yaml)
- [Contrato de Eventos de Domínio](../oficina-platform/contracts/Contrato%20de%20Eventos%20de%20Domínio.md)
- [Contrato de Tópicos de Mensageria](../oficina-platform/contracts/Contrato%20de%20Tópicos%20de%20Mensageria.md)
- [Contrato de Erros REST](../oficina-platform/contracts/error-model.md)
- [Contrato de Idempotência](../oficina-platform/contracts/idempotency.md)
- [Fluxos da Saga da Ordem de Serviço](../oficina-platform/docs/saga-flows.md)

## Variáveis principais

- `DB_USERNAME`
- `DB_PASSWORD`
- `JDBC_DATABASE_URL`
- `REACTIVE_DATABASE_URL`
- `OFICINA_AUTH_ISSUER`
- `OFICINA_AUTH_AUDIENCE`
- `MP_JWT_VERIFY_PUBLICKEY_LOCATION`
- `OFICINA_MERCADO_PAGO_ENABLED`
- `OFICINA_MERCADO_PAGO_ACCESS_TOKEN`
- `OFICINA_MERCADO_PAGO_PAYER_EMAIL`
- `OFICINA_MERCADO_PAGO_API_URL`
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `DEPLOYMENT_ENVIRONMENT`

## Estrutura

```text
src/main/java/br/com/oficina/billing/
  core/
  interfaces/
  framework/
src/main/resources/
  db/migration/
```

## Banco de dados local

O profile `postgresql` executa as migrations Flyway para o database `oficina_billing`.

Arquivos atuais:

- `src/main/resources/db/migration/V1__create_billing_schema.sql`
- `src/main/resources/db/migration/V2__seed_billing_data.sql`

O seed usa IDs de Ordem de Serviço compatíveis com o seed do `oficina-os-service`, mas não cria foreign keys para tabelas de outro microsserviço.

## Mensageria local

A base de mensageria em memória fica em `src/main/java/br/com/oficina/billing/framework/messaging/`.

Ela cobre:

- consumo idempotente de eventos por `eventId`;
- projeção financeira de itens recebidos por eventos de OS e Execution;
- geração de Outbox para `orcamentoGerado`, `orcamentoAprovado`, `orcamentoRecusado`, `pagamentoSolicitado`, `pagamentoConfirmado` e `pagamentoRecusado`;
- publicação local de pendentes, alterando status de `PENDING` para `PUBLISHED`.

A integração com SNS/SQS deve reutilizar essa fronteira de Outbox sem alterar os contratos de eventos.

## Próximo Trabalho

O backlog local está em [TODO.md](TODO.md). O próximo incremento esperado no Épico B2 é copiar e adaptar workflows de CI/CD, garantindo build, testes, Quality Gate, publicação de imagem e deploy automatizado. Em paralelo, seguem no backlog técnico a substituição dos repositórios em memória por adapters PostgreSQL e a conexão da Outbox à publicação real em SNS/SQS.
