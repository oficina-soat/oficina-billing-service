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

## Saga orquestrada

A plataforma usa **Saga orquestrada** pelo `oficina-os-service`, conforme a [ADR-009 - Estratégia de Saga Pattern](../oficina-platform/adr/ADR-009%20-%20Estratégia%20de%20Saga%20Pattern.md), os [Fluxos da Saga da Ordem de Serviço](../oficina-platform/docs/saga-flows.md) e o [Contrato de Saga do oficina-os-service](../oficina-platform/contracts/saga/oficina-os-saga-v1.md).

O `oficina-os-service` foi escolhido como orquestrador porque é a autoridade sobre o estado global da Ordem de Serviço e concentra a sequência distribuída do processo. Essa escolha mantém o fluxo explícito, melhora a rastreabilidade e evita que compensações fiquem dispersas entre os serviços participantes.

O `oficina-billing-service` participa da Saga como autoridade financeira. Ele gera orçamento, registra aprovação ou recusa, solicita pagamento e publica os eventos financeiros consumidos pelo orquestrador. O serviço não decide sozinho o estado global da OS; ele preserva seu banco e domínio financeiro enquanto responde a comandos idempotentes e eventos definidos nos contratos da plataforma.

## Stack

- Java 25
- Quarkus 3.37.0
- PostgreSQL no database `oficina_billing`
- Flyway para migrations
- JWT, OpenAPI, Health, métricas Prometheus, logs JSON e OpenTelemetry

## Setup local

Pré-requisitos:

- Java 25;
- Docker, para build de imagem e dependências locais;
- acesso ao repositório `../oficina-platform`, usado pelos testes de contrato;
- acesso opcional ao repositório `../oficina-infra`, usado para subir dependências compartilhadas da suíte.

Dependências locais compartilhadas podem ser iniciadas pelo `oficina-infra`:

```bash
cd ../oficina-infra
docker compose -f compose.local.yml up -d postgres dynamodb localstack
scripts/local/bootstrap-local.sh
```

Volte para este repositório antes de executar o serviço:

```bash
cd ../oficina-billing-service
```

## Execução local

```bash
./mvnw quarkus:dev -Ppostgresql
./mvnw test -Ppostgresql
./mvnw -B verify -Ppostgresql -DskipITs=false -DfailIfNoTests=false
./mvnw -B package -Ppostgresql
```

O comando `verify` executa testes unitários, integração, contrato e verificação de cobertura JaCoCo.

## Cobertura

O JaCoCo é executado no `verify`, gera relatório em `target/jacoco-report/` e falha o build quando a cobertura de instruções do bundle fica abaixo de 80%. O [Template GitHub Actions para Microsserviços](../oficina-platform/templates/github-actions/README.md) publica esse diretório como artifact `jacoco-report-oficina-billing-service`.

Evidência local de cobertura em 2026-07-01:

```text
./mvnw -B verify -Ppostgresql -DskipITs=false -DfailIfNoTests=false
instruction=84.55% branch=59.91% line=86.36% complexity=60.47%
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## CI/CD

Os workflows ficam em [.github/workflows/service-ci.yml](.github/workflows/service-ci.yml) e [.github/workflows/open-pr-to-main.yml](.github/workflows/open-pr-to-main.yml), derivados do [Template GitHub Actions para Microsserviços](../oficina-platform/templates/github-actions/README.md).

Pull requests e pushes na `main` executam o check `service-ci-validate` com `./mvnw -B verify -Ppostgresql -DskipITs=false -DfailIfNoTests=false`, validam a cobertura mínima de 80%, executam o Quality Gate SonarCloud quando `SONAR_TOKEN`, `SONAR_ORGANIZATION` e `SONAR_PROJECT_KEY` estiverem configurados, e publicam o artifact `jacoco-report-oficina-billing-service`.

A publicação de imagem e o deploy Kubernetes são condicionais:

- `ENABLE_IMAGE_PUBLISH=true` habilita consulta ao ECR, build/push da imagem Docker e release com metadados da imagem;
- `ENABLE_K8S_DEPLOY=true` habilita atualização do Deployment no EKS;
- em `workflow_dispatch`, os inputs `publish_image` e `deploy` permitem acionar esses estágios manualmente.

O workflow não usa GitHub Environment para evitar aprovação manual nos jobs. As variáveis e secrets de AWS/ECR/EKS devem estar em nível de repositório ou organização, e o controle manual do fluxo acontece no merge do PR aberto automaticamente a partir da branch `develop`.

Enquanto os manifests executáveis não estiverem materializados no `oficina-infra`, mantenha `ENABLE_K8S_DEPLOY=false` e use o job de validação como checagem obrigatória de branch.

## Validação de contratos

O teste [PlatformContractsTest](src/test/java/br/com/oficina/billing/contracts/PlatformContractsTest.java) valida o serviço contra os contratos canônicos em `../oficina-platform/contracts`: OpenAPI, schemas JSON de eventos, [Contrato de Erros REST](../oficina-platform/contracts/error-model.md), [Contrato de Idempotência](../oficina-platform/contracts/idempotency.md) e [Contrato de Saga do oficina-os-service](../oficina-platform/contracts/saga/oficina-os-saga-v1.md).

## Docker

```bash
docker build --build-arg MAVEN_PROFILE=postgresql -t oficina-billing-service:local .
docker run --rm -p 8080:8080 oficina-billing-service:local
```

## Kubernetes

A estratégia de entrega dos manifests está definida em [Estratégia de entrega dos manifestos Kubernetes](../oficina-platform/docs/kubernetes-manifest-strategy.md).

Este repositório mantém o Dockerfile do serviço e não mantém cópia executável dos manifests Kubernetes para evitar divergência. A referência normativa do serviço fica em [Template Kubernetes do oficina-billing-service](../oficina-platform/templates/kubernetes/base/oficina-billing-service/), e o destino canônico de deploy é `../oficina-infra/k8s/base/microservices/oficina-billing-service/`.

O deploy automatizado só deve ser habilitado com `ENABLE_K8S_DEPLOY=true` depois que o Deployment `oficina-billing-service` estiver materializado no `oficina-infra` e renderizado pelo overlay `../oficina-infra/k8s/overlays/lab/`.

## Endpoint técnico

- `GET /api/v1/status`: expõe identidade do serviço, ambiente e status técnico básico.

Health checks do Quarkus ficam em `/q/health`, `/q/health/live` e `/q/health/ready`.

## Swagger/OpenAPI

O contrato canônico do serviço é a [OpenAPI do oficina-billing-service](../oficina-platform/contracts/openapi/oficina-billing-service.yaml), mantida no repositório de plataforma.

Com o serviço em execução local na porta `8080`, a documentação gerada pelo Quarkus fica disponível em:

- Swagger UI: `http://localhost:8080/q/swagger-ui/`;
- OpenAPI YAML: `http://localhost:8080/q/openapi`;
- OpenAPI JSON: `http://localhost:8080/q/openapi?format=json`.

O teste [PlatformContractsTest](src/test/java/br/com/oficina/billing/contracts/PlatformContractsTest.java) valida que a OpenAPI gerada em runtime mantém os caminhos e métodos definidos no contrato canônico.

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

Em ambiente local, valores de desenvolvimento ficam em `src/main/resources/application.properties`. Em Kubernetes, variáveis de banco vêm do secret `oficina-billing-service-database-env`, e variáveis não sensíveis vêm do ConfigMap definido pelo manifest canônico no `oficina-infra`.

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

O backlog local está em [TODO.md](TODO.md). Os próximos incrementos esperados no Épico B2 são configurar a proteção da branch `main` e manter a documentação local atualizada conforme novos manifests, variáveis e evidências forem materializados. Em paralelo, seguem no backlog técnico a substituição dos repositórios em memória por adapters PostgreSQL e a conexão da Outbox à publicação real em SNS/SQS.
