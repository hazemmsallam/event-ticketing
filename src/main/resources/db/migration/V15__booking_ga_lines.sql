-- One order may hold tickets in several general-admission sections, so the GA line moves from
-- columns on booking (V14, single section) to its own table: one row per (booking, GA section).

CREATE TABLE booking_ga_line (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   DATETIME(6)    NOT NULL,
    updated_at   DATETIME(6)    NOT NULL,
    booking_id   BIGINT         NOT NULL,
    event_id     BIGINT         NOT NULL,
    -- NULL only for legacy orders whose section could not be resolved (see V14); capacity
    -- queries count those conservatively against every GA section of their event.
    section_id   BIGINT,
    section_name VARCHAR(120),
    quantity     INT            NOT NULL,
    unit_price   DECIMAL(12, 2) NOT NULL,
    currency     VARCHAR(8),
    PRIMARY KEY (id),
    CONSTRAINT uq_booking_ga_line_section UNIQUE (booking_id, section_id),
    CONSTRAINT chk_booking_ga_line_quantity CHECK (quantity > 0),
    CONSTRAINT fk_booking_ga_line_booking FOREIGN KEY (booking_id) REFERENCES booking (id),
    CONSTRAINT fk_booking_ga_line_event FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT fk_booking_ga_line_section FOREIGN KEY (section_id) REFERENCES section (id)
) ENGINE = InnoDB;

-- The per-(event, section) capacity aggregates.
CREATE INDEX idx_booking_ga_line_event_section ON booking_ga_line (event_id, section_id);

INSERT INTO booking_ga_line (version, created_at, updated_at, booking_id, event_id, section_id,
                             section_name, quantity, unit_price, currency)
SELECT 0, b.created_at, b.updated_at, b.id, b.event_id, b.section_id,
       b.general_admission_section_name, b.general_admission_quantity,
       COALESCE(b.general_admission_unit_price, b.total_amount / b.general_admission_quantity),
       b.general_admission_currency
FROM booking b
WHERE b.general_admission_quantity > 0;

-- Retire the single-section GA columns. Constraints and indexes that reference them go first.
ALTER TABLE booking DROP CHECK chk_booking_ga_quantity;
ALTER TABLE booking DROP FOREIGN KEY fk_booking_section;
DROP INDEX idx_booking_event_section_status_expiry ON booking;
DROP INDEX idx_booking_section_status_expiry ON booking;
ALTER TABLE booking
    DROP COLUMN section_id,
    DROP COLUMN general_admission_quantity,
    DROP COLUMN general_admission_unit_price,
    DROP COLUMN general_admission_section_name,
    DROP COLUMN general_admission_currency;

-- Event capacity is now derived from the hall: every seat plus each GA section's capacity.
-- Halls saved before mixed sections counted only seats, so recompute them once here.
UPDATE hall h
JOIN (
    SELECT h2.id,
           (SELECT COUNT(*) FROM seat s WHERE s.hall_id = h2.id)
         + (SELECT COALESCE(SUM(sec.capacity), 0) FROM section sec
            WHERE sec.hall_id = h2.id AND sec.booking_mode = 'GENERAL_ADMISSION') AS derived
    FROM hall h2
) d ON d.id = h.id
SET h.capacity = d.derived
WHERE d.derived > 0;

UPDATE event e
JOIN hall h ON h.id = e.hall_id
SET e.max_capacity = h.capacity;
