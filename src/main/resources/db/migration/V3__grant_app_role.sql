-- Privileges for ledger_app, the role the RUNNING SERVICE holds (issue #12).
--
-- In a cluster every migration runs as ledger_migrator, so ledger_migrator owns every table here.
-- Without this migration the service connects as ledger_app and cannot even read ledger_config,
-- so it refuses to start. The grants below are everything it may do.
--
-- This lives in a migration rather than in gitops because ALTER DEFAULT PRIVILEGES is scoped to the
-- role that CREATES the objects. Only ledger_migrator can set it for the tables ledger_migrator will
-- create, and only a migration runs as ledger_migrator. The ROLE itself still belongs to gitops
-- (CloudNativePG managed.roles in the `money` cluster): the database and the roles are the
-- platform's, the tables and everything in them are the ledger's.
--
-- The role must EXIST for these grants to parse, and it does not exist everywhere. In a cluster
-- CloudNativePG creates it, with a password from OpenBao; a Testcontainers PostgreSQL has no
-- platform at all. So: ensure it, without owning it. NOLOGIN and no password, because the credential
-- is the platform's business -- a migration that could set a login password would be a migration
-- that could grant itself access. Where the platform already made the role, this is a no-op and its
-- LOGIN and password are left exactly as they are.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
        CREATE ROLE ledger_app NOLOGIN;
    END IF;
END
$$;

-- public, because V1 and V2 put every table there: vaullet_db is the ledger's own database
-- (ADR-003), so there is no neighbour to fence off with a schema. PostgreSQL 15 and later let PUBLIC
-- use it anyway; granted by name so the service does not depend on a default that a hardened
-- database revokes.
GRANT USAGE ON SCHEMA public TO ledger_app;

-- Named per table, NOT "ON ALL TABLES IN SCHEMA public". That form would also grant on
-- flyway_schema_history, and a service able to rewrite the migration history can defeat
-- validate-on-migrate, leaving a ledger schema whose constraints nobody can vouch for.
--
-- No DELETE on any table, and no TRUNCATE. Nothing in a ledger is removed: a hold ends SETTLED,
-- RELEASED or EXPIRED, a wallet ends CLOSED, and a correction is a new journal entry. Withholding
-- the privilege makes that structural rather than a convention a future contributor can forget.
--
-- Each table gets what LedgerRepository, WalletRepository and LedgerConfigRepository issue against
-- it, and nothing more. AppRoleGrantsIT runs those paths as ledger_app, so a statement that needs
-- more fails the build rather than the first request in production.

-- Append-only: the journal, and which buckets a hold draws on. Written once, read back, never
-- changed. On ledger_entries the RULEs in V1 already turn an UPDATE or a DELETE into nothing, for
-- the owner as much as for this role, and they do it before any privilege is checked. What the
-- withheld privileges add there is TRUNCATE, which no rule sees.
GRANT SELECT, INSERT ON ledger_entries, reservation_allocations TO ledger_app;

-- The balances and the holds. The aggregates move with every hold, capture and release, and a
-- hold's state moves with them. UPDATE on account_balances is also what SELECT ... FOR UPDATE needs,
-- and that row lock is the first thing every money path takes (ADR-004).
GRANT SELECT, INSERT, UPDATE ON account_balances, balance_buckets, reservations TO ledger_app;

-- Opened by an INSERT; frozen, unfrozen and closed by UPDATEs of the same row.
GRANT SELECT, INSERT, UPDATE ON wallets TO ledger_app;

-- Read, never written. The row records LEDGER_CURRENCY once, at the first start, and in a cluster
-- that start is the migration Job: it builds the same application context as ledger_migrator, so
-- LedgerConfigProvisioner provisions the row there. The service only checks its configuration
-- against the row. If the row were missing, its attempt to write one would be refused and it would
-- not start; a replica of the running service never gets to choose the currency.
GRANT SELECT ON ledger_config TO ledger_app;

-- Tables a LATER migration creates get SELECT and INSERT without anyone remembering to grant them:
-- append-only, which is what a ledger table is until shown otherwise. A table the service must
-- UPDATE gets that grant in its own migration, next to its CREATE TABLE, where a reviewer sees it.
--
-- No FOR ROLE clause, deliberately. Default privileges attach to the role that CREATES the object,
-- and that is whoever runs this migration: ledger_migrator in a cluster, the container's own user
-- under Testcontainers. Naming ledger_migrator would fail with
--     ERROR: role "ledger_migrator" does not exist
-- everywhere the platform did not build; auth-service's first draft of its V2 did exactly that.
-- Omitting it means CURRENT_USER, which is the right answer in both.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT ON TABLES TO ledger_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ledger_app;
