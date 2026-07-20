-- Durable record of each payment attempt, so a charge that succeeds at the gateway is never
-- lost even if confirming the booking fails. One payment row per booking.

CREATE TABLE payment (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,
    booking_id      BIGINT         NOT NULL,
    idempotency_key VARCHAR(80)    NOT NULL,
    customer_ref    VARCHAR(255)   NOT NULL,
    amount          DECIMAL(12, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    reference       VARCHAR(64),
    failure_reason  VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT uq_payment_booking UNIQUE (booking_id),
    CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_payment_booking FOREIGN KEY (booking_id) REFERENCES booking (id)
) ENGINE = InnoDB;

CREATE INDEX idx_payment_status ON payment (status);
