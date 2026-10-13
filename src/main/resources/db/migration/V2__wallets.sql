-- Wallets: an account's money containers, grouping ADR-004's buckets (issue #10).
--
-- A wallet groups buckets; it does not replace them. Buckets stay per grant, allocation still runs
-- over buckets by spend_priority, and account_balances is still the single lock anchor. What a
-- wallet adds is the unit an operator opens, freezes and closes: the reserve path skips the
-- buckets of a wallet that is not ACTIVE (LedgerRepository.allocatableBuckets).
--
-- Which bucket types a wallet type holds (CASH, DEBT -> REAL; BONUS, LOYALTY, REFERRAL -> BONUS) is
-- enforced in code, by WalletType.holds, not here: a CHECK cannot look at the other table, and a
-- trigger would be the one rule in this schema that a reader of the Java cannot see.

CREATE TABLE wallets (
    wallet_id   UUID PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES account_balances(account_id),
    wallet_type TEXT        NOT NULL CHECK (wallet_type IN ('REAL','BONUS')),
    status      TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at   TIMESTAMPTZ NULL,
    -- One wallet per type per account. Also the conflict target that turns a repeated or concurrent
    -- open into "here is the existing wallet" rather than a second one.
    CONSTRAINT wallets_one_per_type UNIQUE (account_id, wallet_type),
    -- wallet_id alone is already unique. The pair is what balance_buckets refers to, so that a
    -- bucket can only sit in a wallet of its own account.
    CONSTRAINT wallets_of_account UNIQUE (wallet_id, account_id),
    -- CLOSED is terminal, and it is the only status that carries a closing date.
    CONSTRAINT wallets_closed_at_iff_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- The keyset GET /v1/wallets pages through (ADR-011 §8): oldest first, wallet_id breaking the tie
-- between wallets created in the same transaction, which share now().
CREATE INDEX wallets_keyset_ix ON wallets (created_at, wallet_id);

-- The same keyset within one status, for an operator listing the FROZEN or the CLOSED wallets. Those
-- are the rare ones, so finding a page of them along wallets_keyset_ix would read most of the table.
CREATE INDEX wallets_status_keyset_ix ON wallets (status, created_at, wallet_id);

-- Nullable only until the backfill below has run: every bucket lives in exactly one wallet.
ALTER TABLE balance_buckets ADD COLUMN wallet_id UUID;

-- Backfill. Nothing is deployed yet, so this only ever meets local and test databases; it is here
-- so that they migrate instead of having to be dropped. One wallet per (account, wallet type) that
-- has buckets, then every bucket linked to it. The CASE is WalletType.forBucketType, in SQL.
INSERT INTO wallets (wallet_id, account_id, wallet_type)
SELECT gen_random_uuid(), account_id, wallet_type
  FROM (SELECT DISTINCT account_id,
               CASE WHEN bucket_type IN ('CASH','DEBT') THEN 'REAL' ELSE 'BONUS' END AS wallet_type
          FROM balance_buckets) AS needed;

UPDATE balance_buckets b
   SET wallet_id = w.wallet_id
  FROM wallets w
 WHERE w.account_id = b.account_id
   AND w.wallet_type = CASE WHEN b.bucket_type IN ('CASH','DEBT') THEN 'REAL' ELSE 'BONUS' END;

ALTER TABLE balance_buckets ALTER COLUMN wallet_id SET NOT NULL;

-- A bucket lives in a wallet of its own account. A reference to wallet_id alone would accept another
-- account's wallet, whose status would then decide whether this account's money can be spent, and
-- whose balance and emptiness check would count it.
ALTER TABLE balance_buckets
    ADD CONSTRAINT balance_buckets_wallet_fk
    FOREIGN KEY (wallet_id, account_id) REFERENCES wallets (wallet_id, account_id);

-- A wallet's balance is a sum over its buckets.
CREATE INDEX balance_buckets_wallet_ix ON balance_buckets (wallet_id);
