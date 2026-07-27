-- First-class hall sections (dynamic name/price/currency/booking-mode/capacity/geometry) and
-- admission tickets. Additive: existing seat-type pricing keeps working for halls without sections.

CREATE TABLE section (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    version       BIGINT         NOT NULL DEFAULT 0,
    created_at    DATETIME(6)    NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    hall_id       BIGINT         NOT NULL,
    name          VARCHAR(120)   NOT NULL,
    booking_mode  VARCHAR(24)    NOT NULL,
    default_price DECIMAL(12, 2),
    currency      VARCHAR(8),
    capacity      INT,
    shape_kind    VARCHAR(16),
    points        TEXT,
    color         VARCHAR(16),
    PRIMARY KEY (id),
    CONSTRAINT fk_section_hall FOREIGN KEY (hall_id) REFERENCES hall (id)
) ENGINE = InnoDB;

CREATE INDEX idx_section_hall ON section (hall_id);

-- Seats reference their section; a price line may target a section; a GA booking targets a section.
ALTER TABLE seat          ADD COLUMN section_id BIGINT,
                          ADD CONSTRAINT fk_seat_section FOREIGN KEY (section_id) REFERENCES section (id);
ALTER TABLE event_pricing ADD COLUMN section_id BIGINT,
                          ADD CONSTRAINT fk_pricing_section FOREIGN KEY (section_id) REFERENCES section (id);
ALTER TABLE booking       ADD COLUMN section_id BIGINT,
                          ADD CONSTRAINT fk_booking_section FOREIGN KEY (section_id) REFERENCES section (id);

-- Section snapshot on each held/booked seat (price already snapshotted).
ALTER TABLE booking_seat  ADD COLUMN section_id   BIGINT,
                          ADD COLUMN section_name  VARCHAR(120),
                          ADD COLUMN currency      VARCHAR(8);

CREATE TABLE ticket (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    booking_id    BIGINT       NOT NULL,
    event_id      BIGINT       NOT NULL,
    section_id    BIGINT,
    seat_id       BIGINT,
    ticket_number VARCHAR(48)  NOT NULL,
    seat_label    VARCHAR(16),
    section_name  VARCHAR(120),
    PRIMARY KEY (id),
    CONSTRAINT uq_ticket_number UNIQUE (ticket_number),
    CONSTRAINT fk_ticket_booking FOREIGN KEY (booking_id) REFERENCES booking (id)
) ENGINE = InnoDB;

CREATE INDEX idx_ticket_booking ON ticket (booking_id);

-- Backfill: create a SEATED section per distinct existing section label (or seat type) per hall,
-- and link the seats to it, so existing seated halls become section-based with zero manual work.
INSERT INTO section (version, created_at, updated_at, hall_id, name, booking_mode, default_price,
                     currency, shape_kind, points)
SELECT 0, NOW(6), NOW(6), s.hall_id, COALESCE(NULLIF(s.section_name, ''), s.seat_type),
       'SEATED', NULL, 'JOD', 'POLYGON', '[]'
FROM seat s
GROUP BY s.hall_id, COALESCE(NULLIF(s.section_name, ''), s.seat_type);

UPDATE seat s
JOIN section sec ON sec.hall_id = s.hall_id
                AND sec.name = COALESCE(NULLIF(s.section_name, ''), s.seat_type)
SET s.section_id = sec.id;

-- Non-seated halls get a single general-admission section carrying the hall's capacity.
INSERT INTO section (version, created_at, updated_at, hall_id, name, booking_mode, default_price,
                     currency, capacity, shape_kind, points)
SELECT 0, NOW(6), NOW(6), h.id, 'General Admission', 'GENERAL_ADMISSION', NULL, 'JOD',
       h.capacity, 'RECTANGLE', '[]'
FROM hall h
WHERE h.is_seated = 0;
