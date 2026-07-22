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

- Data da captura: `2026-07-22T10:17:51.280684333Z`
- Fingerprint SHA-256 do segredo: `25e9adc48fa027931699e5d42ef4d4dceff387df68928f80fe9926e254f0b941`
- Resultado HTTP devolvido pelo Billing: `401 Unauthorized`
- Motivo registrado: `hash_mismatch`
- Ambiente do Billing: `test`
- `live_mode` da notificação: `false`
- `application_id`: `8137145073096167`
- Ação: `order.action_required`
- `data.id` recebido: `ORDTST01KY4NAK01FNK2CJHB11QM1W6X`
- `data.external_reference`: `a5a352a1-742e-4cd9-ab91-c3f9d4f2bb7f`
- `x-request-id`: `23482c4e-5449-471d-8b7b-9131d62a8612`
- `x-signature`: `ts=1784715497,v1=b8c6d1ac9e2f844ce043a497d69bfd006b6761a720c14167d83c6d7d1427ffc4`

### Cálculo conforme o exemplo oficial

```text
id:ordtst01ky4nak01fnk2cjhb11qm1w6x;request-id:23482c4e-5449-471d-8b7b-9131d62a8612;ts:1784715497;
```

- HMAC-SHA256 calculado: `6b4194c5ef5bbcb07504c0c8c42532e55828dd873714a34df5bcc24a3763d749`
- HMAC-SHA256 recebido em `v1`: `b8c6d1ac9e2f844ce043a497d69bfd006b6761a720c14167d83c6d7d1427ffc4`
- Comparação em tempo constante: `false`

### Corpo recebido

```json
{"action":"order.action_required","api_version":"v1","application_id":"8137145073096167","data":{"currency_id":"BRL","external_reference":"a5a352a1-742e-4cd9-ab91-c3f9d4f2bb7f","id":"ORDTST01KY4NAK01FNK2CJHB11QM1W6X","status":"action_required","status_detail":"waiting_transfer","total_amount":"1.00","total_paid_amount":"1.00","transactions":{"payments":[{"amount":"1.00","id":"PAY01KY4NAK0T382TV5T5TT586FCH","payment_method":{"id":"pix","installments":0,"type":"bank_transfer"},"reference":{"id":"000dv7ime5"},"status":"action_required","status_detail":"waiting_transfer"}]},"type":"online","version":1},"date_created":"2026-07-22T10:18:17.50725864Z","live_mode":false,"type":"order","user_id":"3535715534"}
```

## Conclusão

A requisição chegou ao endpoint configurado nas duas tentativas, contendo todos os componentes exigidos para o manifesto. Em ambas, a implementação reproduziu o algoritmo documentado e converteu o `data.id` para minúsculas, mas o hash recebido não correspondeu ao segredo configurado no Billing.

Os fingerprints comprovam que segredos diferentes foram efetivamente carregados nas tentativas. A persistência de `hash_mismatch` após a rotação afasta a hipótese de cache local ou reutilização acidental da primeira chave e indica divergência entre o segredo exibido para a aplicação e a chave usada pelo Mercado Pago para assinar notificações reais de Orders.

## Encerramento da instrumentação

A captura bruta foi removida após a segunda tentativa. O comportamento normal permanece restrito aos logs estruturados sem headers, identificadores, assinatura, corpo ou segredo.
