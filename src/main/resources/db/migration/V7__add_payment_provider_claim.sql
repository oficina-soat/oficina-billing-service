CREATE TABLE pagamento_provider_claim (
    orcamento_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    claim_until timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_pagamento_provider_claim_orcamento
        FOREIGN KEY (orcamento_id)
        REFERENCES orcamento (id)
        ON DELETE CASCADE
);

CREATE INDEX ix_pagamento_provider_claim_until
    ON pagamento_provider_claim (claim_until);
