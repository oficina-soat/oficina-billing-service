# Divergência de assinatura no webhook do Mercado Pago

Este relatório compara duas tentativas reais em sandbox com segredos de webhook distintos. O segredo nunca é persistido: cada chave é identificada exclusivamente por seu fingerprint SHA-256.

## Tentativa 1 — chave atual

- Data da captura: `2026-07-22T10:09:52.146735363Z`
- Fingerprint SHA-256 do segredo: `4ff71c1ea1adda26039b595a7d9f4a160e166c21aea428ba96f59571d1f51a31`
- Resultado HTTP devolvido pelo Billing: `401 Unauthorized`
- Motivo registrado: `hash_mismatch`
- Ambiente do Billing: `test`
- `live_mode` da notificação: `false`
- `application_id`: `8137145073096167`
- Ação: `order.action_required`
- `data.id` recebido: `ORDTST01KY4MVYRJRNS2Z8JWD8J5ZEEE`
- `data.external_reference`: `e4f58ecc-a0b7-4685-bfff-abf93c00183b`
- `x-request-id`: `3d9bde19-23b6-4e24-92b2-df47122087ac`
- `x-signature`: `ts=1784715018,v1=255cd14ba98d1bb20b7277039965be41e3953f6e7d862daaa04faa10762ac085`

### Cálculo conforme o exemplo oficial

O `data.id` foi convertido para minúsculas antes da composição. O `x-request-id` e o `ts` foram usados sem alteração.

```text
id:ordtst01ky4mvyrjrns2z8jwd8j5zeee;request-id:3d9bde19-23b6-4e24-92b2-df47122087ac;ts:1784715018;
```

- HMAC-SHA256 calculado: `fa3f5a17e96f888ae9aecb29f883f74980012e2810eb1c2065fd0cfef662a7e1`
- HMAC-SHA256 recebido em `v1`: `255cd14ba98d1bb20b7277039965be41e3953f6e7d862daaa04faa10762ac085`
- Comparação em tempo constante: `false`

### Corpo recebido

```json
{"action":"order.action_required","api_version":"v1","application_id":"8137145073096167","data":{"currency_id":"BRL","external_reference":"e4f58ecc-a0b7-4685-bfff-abf93c00183b","id":"ORDTST01KY4MVYRJRNS2Z8JWD8J5ZEEE","status":"action_required","status_detail":"waiting_transfer","total_amount":"1.00","total_paid_amount":"1.00","transactions":{"payments":[{"amount":"1.00","id":"PAY01KY4MVYS7AT41VCG1KCFTF1R5","payment_method":{"id":"pix","installments":0,"type":"bank_transfer"},"reference":{"id":"000dv7fo0h"},"status":"action_required","status_detail":"waiting_transfer"}]},"type":"online","version":1},"date_created":"2026-07-22T10:10:18.056479695Z","live_mode":false,"type":"order","user_id":"3535715534"}
```

## Tentativa 2 — nova chave

Pendente de rotação do segredo e repetição do mesmo fluxo. A captura temporária permanece habilitada no ambiente local de teste até esta seção ser preenchida.

## Conclusão provisória

A requisição chegou diretamente ao endpoint configurado, contendo todos os componentes exigidos para o manifesto. Na primeira tentativa, a implementação reproduziu o algoritmo documentado, mas o hash recebido não corresponde ao segredo configurado no Billing. A segunda tentativa permitirá distinguir uma chave desatualizada de uma divergência persistente entre a assinatura produzida pelo Mercado Pago e o segredo exibido para a aplicação.

## Encerramento obrigatório

Após a segunda tentativa, remover a classe de captura, seu teste, a propriedade `OFICINA_MERCADO_PAGO_WEBHOOK_RAW_CAPTURE_ENABLED`, a entrada em `.gitignore` e esta orientação temporária do README. O arquivo bruto em `.oficina-diagnostics/` também deve ser apagado.
