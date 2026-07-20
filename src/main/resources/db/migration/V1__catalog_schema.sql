-- Catalog schema: organizers, halls, seats, events and per-event pricing.

CREATE TABLE organizer (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255),
    phone      VARCHAR(255),
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE hall (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    name             VARCHAR(255) NOT NULL,
    address          VARCHAR(255),
    capacity         INT          NOT NULL,
    is_seated        TINYINT(1)   NOT NULL,
    num_rows         INT,
    num_columns      INT,
    numbering_scheme VARCHAR(40),
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE seat (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    hall_id     BIGINT      NOT NULL,
    row_label   VARCHAR(8)  NOT NULL,
    row_index   INT         NOT NULL,
    seat_number INT         NOT NULL,
    label       VARCHAR(16) NOT NULL,
    seat_type   VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_seat_hall_label UNIQUE (hall_id, label),
    CONSTRAINT fk_seat_hall FOREIGN KEY (hall_id) REFERENCES hall (id)
) ENGINE = InnoDB;

CREATE INDEX idx_seat_hall ON seat (hall_id);

CREATE TABLE event (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    description  VARCHAR(2000),
    category     VARCHAR(80),
    start_at     DATETIME(6)  NOT NULL,
    end_at       DATETIME(6)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    organizer_id BIGINT       NOT NULL,
    hall_id      BIGINT       NOT NULL,
    max_capacity INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_event_organizer FOREIGN KEY (organizer_id) REFERENCES organizer (id),
    CONSTRAINT fk_event_hall FOREIGN KEY (hall_id) REFERENCES hall (id)
) ENGINE = InnoDB;

CREATE INDEX idx_event_status ON event (status);

CREATE TABLE event_pricing (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    version    BIGINT         NOT NULL DEFAULT 0,
    created_at DATETIME(6)    NOT NULL,
    updated_at DATETIME(6)    NOT NULL,
    event_id   BIGINT         NOT NULL,
    seat_type  VARCHAR(16),
    price      DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pricing_event FOREIGN KEY (event_id) REFERENCES event (id)
) ENGINE = InnoDB;

CREATE INDEX idx_pricing_event ON event_pricing (event_id);
