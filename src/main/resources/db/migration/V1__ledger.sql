-- Vaullet ledger — ADR-004 (Atomic Balance Reservations), as revised 2026-09-02.
--
-- NOTE ON ORDERING: ADR-004 presents ledger_entries first for readability, but it carries
-- foreign keys into balance_buckets and reservations. Declared in that order the migration
-- fails, so the physical order here is dependency-first. The ADR is right about the model
-- and wrong about the order; this file is the executable truth.

CREATE TABLE ledger_config (
    singleton      BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    currency       CHAR(3)     NOT NULL,           -- ISO 4217
    minor_units    SMALLINT    NOT NULL,           -- 2 for EUR, 0 for JPY, 3 for KWD
    max_hold_seconds INTEGER   NOT NULL DEFAULT 2592000,  -- 30d ceiling on a caller-supplied TTL
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-account aggregate AND the single concurrency anchor.
-- Every balance-changing operation locks this row first, before touching buckets.
-- Derived: rebuildable by replaying ledger_entries.
CREATE TABLE account_balances (
    account_id     UUID          PRIMARY KEY,
    posted_balance NUMERIC(20,4) NOT NULL DEFAULT 0,  -- sum over FUNDABLE buckets (DEBT excluded)
    held_total     NUMERIC(20,4) NOT NULL DEFAULT 0 CHECK (held_total >= 0),
    debt_total     NUMERIC(20,4) NOT NULL DEFAULT 0 CHECK (debt_total >= 0),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT account_not_overdrawn CHECK (posted_balance - held_total >= 0)
);

-- One row per grant. CASH is a singleton per account.
CREATE TABLE balance_buckets (
    bucket_id          UUID PRIMARY KEY,
    account_id         UUID          NOT NULL REFERENCES account_balances(account_id),
    bucket_type        TEXT          NOT NULL CHECK (bucket_type IN ('CASH','BONUS','LOYALTY','REFERRAL','DEBT')),
    source_module      TEXT          NULL,       -- NULL for CASH
    grant_id           UUID          NULL,       -- idempotency anchor for the granting event
    posted_balance     NUMERIC(20,4) NOT NULL DEFAULT 0,
    held_total         NUMERIC(20,4) NOT NULL DEFAULT 0 CHECK (held_total >= 0),
    withdrawable       BOOLEAN       NOT NULL,
    wagering_remaining NUMERIC(20,4) NOT NULL DEFAULT 0,
    spend_priority     SMALLINT      NOT NULL,   -- lower spends first
    expires_at         TIMESTAMPTZ   NULL,
    CONSTRAINT bucket_not_overdrawn CHECK (posted_balance - held_total >= 0),
    -- A DEBT bucket is an obligation, never a funding source: it must never be
    -- withdrawable, and it is excluded from allocation (see LedgerRepository.reserve).
    CONSTRAINT debt_never_withdrawable CHECK (bucket_type <> 'DEBT' OR withdrawable = FALSE)
);
CREATE UNIQUE INDEX balance_buckets_grant_uk ON balance_buckets (grant_id) WHERE grant_id IS NOT NULL;
CREATE UNIQUE INDEX balance_buckets_one_cash ON balance_buckets (account_id) WHERE bucket_type = 'CASH';
CREATE INDEX balance_buckets_alloc_ix ON balance_buckets (account_id, spend_priority);

-- Authorization holds. Lifetime is chosen by the caller (ADR-004, "Reservation lifetime"):
-- an authorization resolves in seconds, a two-phase wager in days.
CREATE TABLE reservations (
    reservation_id  UUID PRIMARY KEY,
    account_id      UUID          NOT NULL REFERENCES account_balances(account_id),
    amount          NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    state           TEXT          NOT NULL CHECK (state IN ('HELD','SETTLED','RELEASED','EXPIRED')),
    idempotency_key TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT reservation_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT reservations_idempotency_uk UNIQUE (idempotency_key)
);
CREATE INDEX reservations_sweep_ix ON reservations (state, expires_at) WHERE state = 'HELD';

-- Which buckets a hold draws from, and how much from each.
CREATE TABLE reservation_allocations (
    reservation_id UUID          NOT NULL REFERENCES reservations(reservation_id),
    bucket_id      UUID          NOT NULL REFERENCES balance_buckets(bucket_id),
    amount         NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (reservation_id, bucket_id)
);

-- Immutable journal. Source of truth. Append-only, never updated.
-- Single-sided: entries are written against user accounts only. There is no counterparty
-- row and no house account here — see ADR-004, "Scope: a single-sided journal".
CREATE TABLE ledger_entries (
    entry_id       UUID PRIMARY KEY,
    account_id     UUID          NOT NULL REFERENCES account_balances(account_id),
    bucket_id      UUID          NOT NULL REFERENCES balance_buckets(bucket_id),
    direction      TEXT          NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount         NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    reservation_id UUID          NULL REFERENCES reservations(reservation_id),
    transaction_id UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- Settlement idempotency: a redelivered transaction.completed.v1 cannot double-post.
CREATE UNIQUE INDEX ledger_entries_txn_uk ON ledger_entries (transaction_id, bucket_id, direction);

-- The journal is append-only by decision; make the database enforce it rather than trusting
-- every future code path to remember. A correction is a new entry, never an edit.
CREATE RULE ledger_entries_no_update AS ON UPDATE TO ledger_entries DO INSTEAD NOTHING;
CREATE RULE ledger_entries_no_delete AS ON DELETE TO ledger_entries DO INSTEAD NOTHING;
