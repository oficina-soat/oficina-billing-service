# AGENTS.md

## Contexto

Este repositório implementa o microsserviço `oficina-billing-service`.

O repositório normativo da plataforma é [../oficina-platform](../oficina-platform/). Antes de alterar contratos, rotas, eventos, banco, mensageria, autenticação, observabilidade ou decisões arquiteturais, consulte os artefatos relacionados no `oficina-platform`.

## Ownership do Serviço

O `oficina-billing-service` é dono de:

- orçamento;
- itens financeiros do orçamento;
- aprovação de orçamento;
- recusa de orçamento;
- pagamento;
- confirmação, recusa e cancelamento de pagamento;
- integração financeira.

Banco canônico:

```text
Amazon RDS for PostgreSQL
database: oficina_billing
usuario: oficina_billing_user
```

## Referências Normativas

- [Matriz de Ownership por Microsserviço](../oficina-platform/docs/service-ownership.md)
- [Plano de Decomposição do oficina-app](../oficina-platform/docs/oficina-app-decomposition.md)
- [Contrato de APIs REST](../oficina-platform/contracts/Contrato%20de%20APIs%20REST.md)
- [OpenAPI do oficina-billing-service](../oficina-platform/contracts/openapi/oficina-billing-service.yaml)
- [Contrato de Eventos de Domínio](../oficina-platform/contracts/Contrato%20de%20Eventos%20de%20Domínio.md)
- [Contrato de Tópicos de Mensageria](../oficina-platform/contracts/Contrato%20de%20Tópicos%20de%20Mensageria.md)
- [Contrato de Erros REST](../oficina-platform/contracts/error-model.md)
- [Contrato de Idempotência](../oficina-platform/contracts/idempotency.md)
- [Padrão Outbox por Serviço](../oficina-platform/docs/outbox-pattern.md)
- [Fluxos da Saga da Ordem de Serviço](../oficina-platform/docs/saga-flows.md)
- [Padrão de Observabilidade Distribuída](../oficina-platform/docs/observability.md)
- [Proposta de Migrations PostgreSQL Decompostas](../oficina-platform/docs/postgres-migrations-decomposition.md)
- [Padrão de isolamento PostgreSQL no RDS compartilhado](../oficina-platform/docs/rds-postgresql-isolation.md)

## Regras de Implementação

- Preserve a estrutura Clean Architecture do repositório:

```text
src/main/java/br/com/oficina/billing/
  core/
    entities/
    exceptions/
    interfaces/
    usecases/
  interfaces/
    controllers/
    presenters/
  framework/
    db/
    messaging/
    web/
```

- Não crie biblioteca Java compartilhada entre microsserviços.
- Não acesse o database `oficina_os` nem tabelas DynamoDB do `oficina-execution-service`.
- Não implemente Cliente, Veículo, Ordem de Serviço, catálogo técnico, estoque, diagnóstico ou reparo neste serviço.
- Este serviço não deve alterar diretamente o estado global da OS.
- Publique eventos somente após persistência local bem-sucedida, usando Outbox.
- Operações mutáveis devem exigir `Idempotency-Key`.
- Propague `X-Correlation-Id` em HTTP, eventos, logs e traces.
- Respostas de erro REST devem seguir o contrato de erro da plataforma.
- A autenticação deve usar JWT conforme issuer, audience e JWKS documentados na plataforma.
- Se precisar alterar rota, evento, payload ou ownership, atualize primeiro ou em conjunto os contratos no `oficina-platform`.

## Fontes de Implementação

Não há módulo financeiro equivalente no [../oficina-app](../oficina-app/). Crie o domínio financeiro a partir dos contratos da plataforma, dos eventos e da matriz de ownership.

Use o `oficina-app` apenas como referência indireta para dados de OS e itens quando necessário. Não adapte o `oficina-app` diretamente neste fluxo.

## Validação

Antes de encerrar alterações relevantes, execute validação proporcional ao impacto:

```bash
./mvnw test -Ppostgresql
```

Quando as ferramentas estiverem disponíveis, use também as validações complementares documentadas em [Ferramentas de validação local](../oficina-platform/docs/validation-tooling.md):

- alterações em GitHub Actions: `actionlint`;
- alterações em `Dockerfile`: `hadolint Dockerfile`;
- alterações em scripts shell: `bash -n`, `shellcheck` e `shfmt -d`;
- mudanças prontas para CI/CD ou release: `./mvnw -B verify -Ppostgresql -DskipITs=false -DfailIfNoTests=false`;
- investigação de falhas remotas de CI/CD: use `gh` autenticado para consultar runs, jobs e logs.

Se uma ferramenta complementar esperada não estiver instalada, registre a limitação na resposta final e execute a melhor validação equivalente disponível.

Para mudanças em contratos de API, eventos ou Saga, valide também os artefatos correspondentes em [../oficina-platform](../oficina-platform/).

## Versionamento

Antes de concluir qualquer alteração relevante, verifique o `project.version` em [pom.xml](pom.xml). Não deixe versões `*-SNAPSHOT` em mudanças prontas para merge, publicação de imagem, release ou deploy; feche a versão no mesmo escopo da alteração ou incremente para uma nova versão fechada quando a mudança exigir novo artefato publicável.

## Commits

Ao concluir alteração relevante neste repositório, crie commit local em português seguindo Conventional Commits, por exemplo:

```bash
git commit -m "feat: implementa geração de orçamento"
```
