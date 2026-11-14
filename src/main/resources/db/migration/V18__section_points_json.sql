-- section.points moves from TEXT to a native JSON column, so MySQL rejects malformed boundaries.
-- MODIFY fails if any existing row is not valid JSON, so empty, missing or malformed values are
-- normalised first to an empty boundary ("[]"): exactly how the application already read them.
-- Valid JSON that is not an array (e.g. "{}") is normalised too, since it can never be a polygon.
UPDATE section
SET points = '[]'
WHERE points IS NULL
   OR TRIM(points) = ''
   OR JSON_VALID(points) = 0
   OR IF(JSON_VALID(points), JSON_TYPE(points), NULL) <> 'ARRAY';

ALTER TABLE section MODIFY COLUMN points JSON NOT NULL;
