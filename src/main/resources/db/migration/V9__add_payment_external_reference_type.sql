ALTER TABLE pagamento
    ADD COLUMN tipo_referencia_externa varchar(20);

UPDATE pagamento
SET tipo_referencia_externa = 'PAYMENT'
WHERE lower(provedor) = 'mercado-pago'
  AND transacao_externa_id IS NOT NULL;

ALTER TABLE pagamento
    ADD CONSTRAINT ck_pagamento_tipo_referencia_externa
        CHECK (tipo_referencia_externa IN ('ORDER', 'PAYMENT'));

DROP INDEX ux_pagamento_transacao_externa;

CREATE UNIQUE INDEX ux_pagamento_tipo_transacao_externa
    ON pagamento (tipo_referencia_externa, transacao_externa_id)
    WHERE tipo_referencia_externa IS NOT NULL
      AND transacao_externa_id IS NOT NULL;

CREATE UNIQUE INDEX ux_pagamento_transacao_externa_sem_tipo
    ON pagamento (transacao_externa_id)
    WHERE tipo_referencia_externa IS NULL
      AND transacao_externa_id IS NOT NULL;
