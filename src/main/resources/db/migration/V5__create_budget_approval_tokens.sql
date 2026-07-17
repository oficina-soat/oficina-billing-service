CREATE TABLE financeiro_ordem_projection (
    ordem_de_servico_id uuid PRIMARY KEY,
    cliente_email varchar(320) NOT NULL,
    atualizado_em timestamptz NOT NULL
);

CREATE TABLE orcamento_action_token (
    token_id uuid PRIMARY KEY,
    ordem_de_servico_id uuid NOT NULL,
    orcamento_id uuid NOT NULL,
    cliente_email varchar(320) NOT NULL,
    acao varchar(20) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    criado_em timestamptz NOT NULL,
    expira_em timestamptz NOT NULL,
    usado_em timestamptz,
    CONSTRAINT fk_orcamento_action_token_orcamento
        FOREIGN KEY (orcamento_id) REFERENCES orcamento (id),
    CONSTRAINT ck_orcamento_action_token_acao
        CHECK (acao IN ('ACOMPANHAR', 'APROVAR', 'RECUSAR')),
    CONSTRAINT ck_orcamento_action_token_validade
        CHECK (expira_em > criado_em)
);

CREATE INDEX ix_orcamento_action_token_orcamento
    ON orcamento_action_token (orcamento_id, usado_em, expira_em);
