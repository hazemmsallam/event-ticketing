-- Persist the visual 3D layout of seated halls.
--
-- The Seat and Hall entities already map these columns (the admin 3D editor writes seat pixel
-- coordinates, size, rotation and section, and the hall's canvas size), but no migration had
-- added them yet. This backfills the schema so the existing chair-layout feature is durable. It
-- does not change any booking behaviour: capacity and availability are unaffected by layout data.

ALTER TABLE hall
    ADD COLUMN layout_width  INT,
    ADD COLUMN layout_height INT;

ALTER TABLE seat
    ADD COLUMN layout_x         INT,
    ADD COLUMN layout_y         INT,
    ADD COLUMN rotation_degrees INT,
    ADD COLUMN layout_width     INT,
    ADD COLUMN layout_height    INT,
    ADD COLUMN section_name     VARCHAR(80);
