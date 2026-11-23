\set ON_ERROR_STOP on
SELECT format('CREATE DATABASE %I', name)
FROM (VALUES ('approach_a'), ('approach_b')) AS names(name)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = name)
\gexec

\connect approach_a
BEGIN;
\ir common.sql.inc
\ir approach_a.sql.inc
\ir operations.sql.inc
COMMIT;

\connect approach_b
BEGIN;
\ir common.sql.inc
\ir approach_b.sql.inc
\ir operations.sql.inc
COMMIT;
