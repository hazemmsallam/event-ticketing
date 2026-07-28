-- Reusable layout presets: a named, saved arrangement of sections + seats + tables that an admin
-- captured from a selection and can stamp onto any hall later.
--
-- The members are kept as one JSON document rather than foreign keys to seats/sections, because a
-- preset is deliberately hall-agnostic: it is a template of shapes and relative offsets, not a
-- reference to the rows it was copied from (those may be edited or deleted afterwards). Member
-- coordinates are stored relative to the preset's own bounding box, so stamping is a simple
-- translate by the drop point.
CREATE TABLE layout_preset (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    width       INT          NOT NULL,
    height      INT          NOT NULL,
    payload     LONGTEXT     NOT NULL,
    PRIMARY KEY (id),
    -- Names identify a preset in the picker, so they must be unique.
    CONSTRAINT uq_layout_preset_name UNIQUE (name)
) ENGINE = InnoDB;
