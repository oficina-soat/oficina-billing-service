ALTER TABLE pagamento
    ADD COLUMN pix_copia_e_cola text,
    ADD COLUMN pix_qr_code_base64 text,
    ADD COLUMN pix_ticket_url text,
    ADD COLUMN pix_expira_em timestamptz;

CREATE UNIQUE INDEX ux_pagamento_transacao_externa
    ON pagamento (transacao_externa_id)
    WHERE transacao_externa_id IS NOT NULL;
