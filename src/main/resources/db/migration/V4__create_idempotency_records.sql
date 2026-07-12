CREATE TABLE idempotency_record (
    scope varchar(300) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    processing_status varchar(32) NOT NULL,
    response_status integer,
    response_body text,
    correlation_id varchar(128) NOT NULL,
    request_id varchar(128),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT ck_idempotency_record_status CHECK (
        processing_status IN ('PROCESSING', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_FINAL')
    )
);

CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);
CREATE INDEX ix_idempotency_record_status_updated ON idempotency_record (processing_status, updated_at);
