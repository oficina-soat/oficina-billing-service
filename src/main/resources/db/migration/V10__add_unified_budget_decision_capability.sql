ALTER TABLE orcamento_action_token
    DROP CONSTRAINT ck_orcamento_action_token_acao;

ALTER TABLE orcamento_action_token
    ADD CONSTRAINT ck_orcamento_action_token_acao
        CHECK (acao IN ('ACOMPANHAR', 'APROVAR', 'RECUSAR', 'DECIDIR'));
