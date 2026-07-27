-- Non-bookable layout objects (tables today, other decoration later).
--
-- These live in their own table, entirely separate from `seat`, so they can never be referenced by
-- a booking_seat and never enter capacity or availability calculations. `object_type` classifies
-- the object (TABLE) and `shape` describes a table's footprint (SQUARE / RECTANGLE / CIRCLE).
-- Position/footprint reuse the seat pixel space (layout_x/layout_y, layout_width/layout_depth) so
-- the same editor arranges both; layout_z and object_height add the extra 3D dimensions.

CREATE TABLE layout_object (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    hall_id          BIGINT      NOT NULL,
    object_type      VARCHAR(24) NOT NULL,
    shape            VARCHAR(16),
    label            VARCHAR(80),
    layout_x         INT,
    layout_y         INT,
    layout_z         INT,
    rotation_degrees INT,
    layout_width     INT,
    layout_depth     INT,
    object_height    INT,
    PRIMARY KEY (id),
    CONSTRAINT fk_layout_object_hall FOREIGN KEY (hall_id) REFERENCES hall (id)
) ENGINE = InnoDB;

CREATE INDEX idx_layout_object_hall ON layout_object (hall_id);
