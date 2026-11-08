-- Content fields the customer app needs on event cards and detail pages: a poster image,
-- a short tagline, a featured flag for the "Featured" badge, and a tag set the list can filter on.

ALTER TABLE event
    ADD COLUMN image_url VARCHAR(500),
    ADD COLUMN tagline   VARCHAR(200),
    ADD COLUMN featured  TINYINT(1) NOT NULL DEFAULT 0;

CREATE INDEX idx_event_featured ON event (featured);

CREATE TABLE event_tag (
    event_id BIGINT      NOT NULL,
    tag      VARCHAR(60) NOT NULL,
    PRIMARY KEY (event_id, tag),
    CONSTRAINT fk_event_tag_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE INDEX idx_event_tag_tag ON event_tag (tag);
