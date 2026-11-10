-- Home's "Ideas for your next trip" (4.7 speed): the current list of places and its translations, one row per
-- language. Kept here so a new backend instance serves them at once instead of asking the AI while someone waits;
-- the daily job replaces them (POST /api/discover/refresh-ideas).
CREATE TABLE idea_pools (
    language  varchar(5)   PRIMARY KEY,
    made_at   timestamptz  NOT NULL,
    ideas     jsonb        NOT NULL
);
