-- Global readiness (4.7): the language each user reads GoAwayGo in (en, hi, es, fr, de, ar, ja), for the pushes and
-- emails sent outside a request; and the language a trip plan was written in, so changed days match it.

ALTER TABLE users ADD COLUMN language varchar(5) NOT NULL DEFAULT 'en';

ALTER TABLE trip_plans ADD COLUMN language varchar(5) NOT NULL DEFAULT 'en';
