CREATE TABLE billing_consumed_event (
    event_id uuid PRIMARY KEY,
    event_type varchar(100),
    event_version integer,
    producer varchar(100),
    aggregate_id varchar(100),
    occurred_at timestamptz,
    consumed_at timestamptz NOT NULL
);

CREATE TABLE financeiro_item_projection (
    ordem_de_servico_id uuid NOT NULL,
    item_id uuid NOT NULL,
    tipo varchar(20) NOT NULL,
    referencia_catalogo_id uuid,
    nome varchar(255) NOT NULL,
    quantidade numeric(15, 3) NOT NULL,
    valor_unitario numeric(14, 2) NOT NULL,
    valor_total numeric(14, 2) NOT NULL,
    criado_em timestamptz NOT NULL,
    atualizado_em timestamptz NOT NULL,
    CONSTRAINT pk_financeiro_item_projection
        PRIMARY KEY (ordem_de_servico_id, item_id),
    CONSTRAINT fk_financeiro_item_projection_tipo
        FOREIGN KEY (tipo)
        REFERENCES dominio_tipo_item_orcamento (codigo),
    CONSTRAINT ck_financeiro_item_projection_quantidade_positiva CHECK (quantidade > 0),
    CONSTRAINT ck_financeiro_item_projection_valor_unitario_nao_negativo CHECK (valor_unitario >= 0),
    CONSTRAINT ck_financeiro_item_projection_valor_total_nao_negativo CHECK (valor_total >= 0)
);

CREATE INDEX ix_billing_consumed_event_aggregate ON billing_consumed_event (aggregate_id, occurred_at);
CREATE INDEX ix_financeiro_item_projection_ordem ON financeiro_item_projection (ordem_de_servico_id, criado_em);
