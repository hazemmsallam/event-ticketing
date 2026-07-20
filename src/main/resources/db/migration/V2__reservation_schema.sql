-- Reservation schema: bookings (orders) and per-seat holds.

CREATE TABLE booking (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   DATETIME(6)    NOT NULL,
    updated_at   DATETIME(6)    NOT NULL,
    event_id     BIGINT         NOT NULL,
    customer_ref VARCHAR(255)   NOT NULL,
    status       VARCHAR(20)    NOT NULL,
    quantity     INT            NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    expires_at   DATETIME(6)    NOT NULL,
    confirmed_at DATETIME(6),
    payment_ref  VARCHAR(64),
    PRIMARY KEY (id),
    CONSTRAINT fk_booking_event FOREIGN KEY (event_id) REFERENCES event (id)
) ENGINE = InnoDB;

CREATE INDEX idx_booking_event ON booking (event_id);
CREATE INDEX idx_booking_status_expires ON booking (status, expires_at);

CREATE TABLE booking_seat (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    version    BIGINT         NOT NULL DEFAULT 0,
    created_at DATETIME(6)    NOT NULL,
    updated_at DATETIME(6)    NOT NULL,
    booking_id BIGINT         NOT NULL,
    event_id   BIGINT         NOT NULL,
    seat_id    BIGINT         NOT NULL,
    seat_type  VARCHAR(16)    NOT NULL,
    price      DECIMAL(12, 2) NOT NULL,
    status     VARCHAR(16)    NOT NULL,
    -- Non-null only while the seat is actively HELD or BOOKED; NULL once RELEASED.
    -- MySQL allows many NULLs in a unique index, so released rows never collide, while any
    -- two active holds on the same (event, seat) do. This is the double-booking guard.
    active_lock VARCHAR(48) GENERATED ALWAYS AS (
        CASE WHEN status IN ('HELD', 'BOOKED')
             THEN CONCAT(event_id, '-', seat_id)
             ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_booking_seat_active UNIQUE (active_lock),
    CONSTRAINT fk_booking_seat_booking FOREIGN KEY (booking_id) REFERENCES booking (id),
    CONSTRAINT fk_booking_seat_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT fk_booking_seat_seat FOREIGN KEY (seat_id) REFERENCES seat (id)
) ENGINE = InnoDB;

CREATE INDEX idx_booking_seat_event_status ON booking_seat (event_id, status);
CREATE INDEX idx_booking_seat_booking ON booking_seat (booking_id);
