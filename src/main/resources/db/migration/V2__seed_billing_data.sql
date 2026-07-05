INSERT INTO dominio_status_orcamento (codigo, descricao) VALUES
    ('GERADO', 'Gerado'),
    ('APROVADO', 'Aprovado'),
    ('RECUSADO', 'Recusado');

INSERT INTO dominio_tipo_item_orcamento (codigo, descricao) VALUES
    ('PECA', 'Peca'),
    ('SERVICO', 'Servico');

INSERT INTO dominio_metodo_pagamento (codigo, descricao) VALUES
    ('PIX', 'Pix'),
    ('CARTAO_CREDITO', 'Cartao de credito'),
    ('CARTAO_DEBITO', 'Cartao de debito'),
    ('DINHEIRO', 'Dinheiro');

INSERT INTO dominio_status_pagamento (codigo, descricao) VALUES
    ('CRIADO', 'Criado'),
    ('CONFIRMADO', 'Confirmado'),
    ('RECUSADO', 'Recusado'),
    ('CANCELADO', 'Cancelado');

INSERT INTO orcamento (id, ordem_de_servico_id, valor_total, status, criado_em, atualizado_em) VALUES
    ('91000000-0000-4000-8000-000000000001', '5b2276e8-fa72-4f4c-a3b0-2c5b1bf427ef', 220.00, 'GERADO', '2026-01-17 12:30:00+00', '2026-01-17 12:30:00+00'),
    ('91000000-0000-4000-8000-000000000002', '6b2276e8-fa72-4f4c-a3b0-2c5b1bf427ef', 190.00, 'APROVADO', '2026-01-17 13:25:00+00', '2026-01-17 13:45:00+00'),
    ('91000000-0000-4000-8000-000000000003', '7b2276e8-fa72-4f4c-a3b0-2c5b1bf427ef', 190.00, 'APROVADO', '2026-01-17 14:25:00+00', '2026-01-17 14:40:00+00');

INSERT INTO orcamento_item (
    orcamento_id,
    tipo,
    item_id,
    referencia_catalogo_id,
    nome,
    quantidade,
    valor_unitario,
    valor_total
) VALUES
    ('91000000-0000-4000-8000-000000000001', 'PECA', '60000000-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000001', 'Volante', 2.000, 50.00, 100.00),
    ('91000000-0000-4000-8000-000000000001', 'SERVICO', '80000000-0000-4000-8000-000000000001', '90000000-0000-4000-8000-000000000001', 'Diagnostico eletrico', 1.000, 120.00, 120.00),
    ('91000000-0000-4000-8000-000000000002', 'PECA', '60000000-0000-4000-8000-000000000002', '70000000-0000-4000-8000-000000000002', 'Filtro de oleo', 1.000, 40.00, 40.00),
    ('91000000-0000-4000-8000-000000000002', 'SERVICO', '80000000-0000-4000-8000-000000000002', '90000000-0000-4000-8000-000000000002', 'Troca de oleo', 1.000, 150.00, 150.00),
    ('91000000-0000-4000-8000-000000000003', 'PECA', '60000000-0000-4000-8000-000000000002', '70000000-0000-4000-8000-000000000002', 'Filtro de oleo', 1.000, 40.00, 40.00),
    ('91000000-0000-4000-8000-000000000003', 'SERVICO', '80000000-0000-4000-8000-000000000002', '90000000-0000-4000-8000-000000000002', 'Troca de oleo', 1.000, 150.00, 150.00);

INSERT INTO pagamento (
    id,
    ordem_de_servico_id,
    orcamento_id,
    valor,
    metodo,
    status,
    provedor,
    transacao_externa_id,
    motivo_recusa,
    criado_em,
    atualizado_em
) VALUES
    ('92000000-0000-4000-8000-000000000001', '6b2276e8-fa72-4f4c-a3b0-2c5b1bf427ef', '91000000-0000-4000-8000-000000000002', 190.00, 'PIX', 'CRIADO', NULL, NULL, NULL, '2026-01-17 13:50:00+00', '2026-01-17 13:50:00+00'),
    ('92000000-0000-4000-8000-000000000002', '7b2276e8-fa72-4f4c-a3b0-2c5b1bf427ef', '91000000-0000-4000-8000-000000000003', 190.00, 'PIX', 'CONFIRMADO', 'mercado-pago', 'mp-lab-0001', NULL, '2026-01-17 15:05:00+00', '2026-01-17 15:10:00+00');
