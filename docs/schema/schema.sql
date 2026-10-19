--
-- PostgreSQL database dump
--

\restrict vaulletschemadocs

-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: account_balances; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.account_balances (
    account_id uuid NOT NULL,
    posted_balance numeric(20,4) DEFAULT 0 NOT NULL,
    held_total numeric(20,4) DEFAULT 0 NOT NULL,
    debt_total numeric(20,4) DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT account_balances_debt_total_check CHECK ((debt_total >= (0)::numeric)),
    CONSTRAINT account_balances_held_total_check CHECK ((held_total >= (0)::numeric)),
    CONSTRAINT account_not_overdrawn CHECK (((posted_balance - held_total) >= (0)::numeric))
);


ALTER TABLE public.account_balances OWNER TO ledger_migrator;

--
-- Name: balance_buckets; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.balance_buckets (
    bucket_id uuid NOT NULL,
    account_id uuid NOT NULL,
    bucket_type text NOT NULL,
    source_module text,
    grant_id uuid,
    posted_balance numeric(20,4) DEFAULT 0 NOT NULL,
    held_total numeric(20,4) DEFAULT 0 NOT NULL,
    withdrawable boolean NOT NULL,
    wagering_remaining numeric(20,4) DEFAULT 0 NOT NULL,
    spend_priority smallint NOT NULL,
    expires_at timestamp with time zone,
    wallet_id uuid NOT NULL,
    CONSTRAINT balance_buckets_bucket_type_check CHECK ((bucket_type = ANY (ARRAY['CASH'::text, 'BONUS'::text, 'LOYALTY'::text, 'REFERRAL'::text, 'DEBT'::text]))),
    CONSTRAINT balance_buckets_held_total_check CHECK ((held_total >= (0)::numeric)),
    CONSTRAINT bucket_not_overdrawn CHECK (((posted_balance - held_total) >= (0)::numeric)),
    CONSTRAINT debt_never_withdrawable CHECK (((bucket_type <> 'DEBT'::text) OR (withdrawable = false)))
);


ALTER TABLE public.balance_buckets OWNER TO ledger_migrator;

--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


ALTER TABLE public.flyway_schema_history OWNER TO ledger_migrator;

--
-- Name: ledger_config; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.ledger_config (
    singleton boolean DEFAULT true NOT NULL,
    currency character(3) NOT NULL,
    minor_units smallint NOT NULL,
    max_hold_seconds integer DEFAULT 2592000 NOT NULL,
    initialized_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ledger_config_singleton_check CHECK (singleton)
);


ALTER TABLE public.ledger_config OWNER TO ledger_migrator;

--
-- Name: ledger_entries; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.ledger_entries (
    entry_id uuid NOT NULL,
    account_id uuid NOT NULL,
    bucket_id uuid NOT NULL,
    direction text NOT NULL,
    amount numeric(20,4) NOT NULL,
    reservation_id uuid,
    transaction_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ledger_entries_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT ledger_entries_direction_check CHECK ((direction = ANY (ARRAY['DEBIT'::text, 'CREDIT'::text])))
);


ALTER TABLE public.ledger_entries OWNER TO ledger_migrator;

--
-- Name: reservation_allocations; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.reservation_allocations (
    reservation_id uuid NOT NULL,
    bucket_id uuid NOT NULL,
    amount numeric(20,4) NOT NULL,
    CONSTRAINT reservation_allocations_amount_check CHECK ((amount > (0)::numeric))
);


ALTER TABLE public.reservation_allocations OWNER TO ledger_migrator;

--
-- Name: reservations; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.reservations (
    reservation_id uuid NOT NULL,
    account_id uuid NOT NULL,
    amount numeric(20,4) NOT NULL,
    state text NOT NULL,
    idempotency_key text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT reservation_expiry_after_creation CHECK ((expires_at > created_at)),
    CONSTRAINT reservations_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT reservations_state_check CHECK ((state = ANY (ARRAY['HELD'::text, 'SETTLED'::text, 'RELEASED'::text, 'EXPIRED'::text])))
);


ALTER TABLE public.reservations OWNER TO ledger_migrator;

--
-- Name: wallets; Type: TABLE; Schema: public; Owner: ledger_migrator
--

CREATE TABLE public.wallets (
    wallet_id uuid NOT NULL,
    account_id uuid NOT NULL,
    wallet_type text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    closed_at timestamp with time zone,
    CONSTRAINT wallets_closed_at_iff_closed CHECK (((status = 'CLOSED'::text) = (closed_at IS NOT NULL))),
    CONSTRAINT wallets_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'FROZEN'::text, 'CLOSED'::text]))),
    CONSTRAINT wallets_wallet_type_check CHECK ((wallet_type = ANY (ARRAY['REAL'::text, 'BONUS'::text])))
);


ALTER TABLE public.wallets OWNER TO ledger_migrator;

--
-- Name: account_balances account_balances_pkey; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.account_balances
    ADD CONSTRAINT account_balances_pkey PRIMARY KEY (account_id);


--
-- Name: balance_buckets balance_buckets_pkey; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.balance_buckets
    ADD CONSTRAINT balance_buckets_pkey PRIMARY KEY (bucket_id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: ledger_config ledger_config_pkey; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.ledger_config
    ADD CONSTRAINT ledger_config_pkey PRIMARY KEY (singleton);


--
-- Name: ledger_entries ledger_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.ledger_entries
    ADD CONSTRAINT ledger_entries_pkey PRIMARY KEY (entry_id);


--
-- Name: reservation_allocations reservation_allocations_pkey; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.reservation_allocations
    ADD CONSTRAINT reservation_allocations_pkey PRIMARY KEY (reservation_id, bucket_id);


--
-- Name: reservations reservations_idempotency_uk; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_idempotency_uk UNIQUE (idempotency_key);


--
-- Name: reservations reservations_pkey; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_pkey PRIMARY KEY (reservation_id);


--
-- Name: wallets wallets_of_account; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.wallets
    ADD CONSTRAINT wallets_of_account UNIQUE (wallet_id, account_id);


--
-- Name: wallets wallets_one_per_type; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.wallets
    ADD CONSTRAINT wallets_one_per_type UNIQUE (account_id, wallet_type);


--
-- Name: wallets wallets_pkey; Type: CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.wallets
    ADD CONSTRAINT wallets_pkey PRIMARY KEY (wallet_id);


--
-- Name: balance_buckets_alloc_ix; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE INDEX balance_buckets_alloc_ix ON public.balance_buckets USING btree (account_id, spend_priority);


--
-- Name: balance_buckets_grant_uk; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE UNIQUE INDEX balance_buckets_grant_uk ON public.balance_buckets USING btree (grant_id) WHERE (grant_id IS NOT NULL);


--
-- Name: balance_buckets_one_cash; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE UNIQUE INDEX balance_buckets_one_cash ON public.balance_buckets USING btree (account_id) WHERE (bucket_type = 'CASH'::text);


--
-- Name: balance_buckets_wallet_ix; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE INDEX balance_buckets_wallet_ix ON public.balance_buckets USING btree (wallet_id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: ledger_entries_txn_uk; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE UNIQUE INDEX ledger_entries_txn_uk ON public.ledger_entries USING btree (transaction_id, bucket_id, direction);


--
-- Name: reservations_sweep_ix; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE INDEX reservations_sweep_ix ON public.reservations USING btree (state, expires_at) WHERE (state = 'HELD'::text);


--
-- Name: wallets_keyset_ix; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE INDEX wallets_keyset_ix ON public.wallets USING btree (created_at, wallet_id);


--
-- Name: wallets_status_keyset_ix; Type: INDEX; Schema: public; Owner: ledger_migrator
--

CREATE INDEX wallets_status_keyset_ix ON public.wallets USING btree (status, created_at, wallet_id);


--
-- Name: ledger_entries ledger_entries_no_delete; Type: RULE; Schema: public; Owner: ledger_migrator
--

CREATE RULE ledger_entries_no_delete AS
    ON DELETE TO public.ledger_entries DO INSTEAD NOTHING;


--
-- Name: ledger_entries ledger_entries_no_update; Type: RULE; Schema: public; Owner: ledger_migrator
--

CREATE RULE ledger_entries_no_update AS
    ON UPDATE TO public.ledger_entries DO INSTEAD NOTHING;


--
-- Name: balance_buckets balance_buckets_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.balance_buckets
    ADD CONSTRAINT balance_buckets_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.account_balances(account_id);


--
-- Name: balance_buckets balance_buckets_wallet_fk; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.balance_buckets
    ADD CONSTRAINT balance_buckets_wallet_fk FOREIGN KEY (wallet_id, account_id) REFERENCES public.wallets(wallet_id, account_id);


--
-- Name: ledger_entries ledger_entries_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.ledger_entries
    ADD CONSTRAINT ledger_entries_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.account_balances(account_id);


--
-- Name: ledger_entries ledger_entries_bucket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.ledger_entries
    ADD CONSTRAINT ledger_entries_bucket_id_fkey FOREIGN KEY (bucket_id) REFERENCES public.balance_buckets(bucket_id);


--
-- Name: ledger_entries ledger_entries_reservation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.ledger_entries
    ADD CONSTRAINT ledger_entries_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(reservation_id);


--
-- Name: reservation_allocations reservation_allocations_bucket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.reservation_allocations
    ADD CONSTRAINT reservation_allocations_bucket_id_fkey FOREIGN KEY (bucket_id) REFERENCES public.balance_buckets(bucket_id);


--
-- Name: reservation_allocations reservation_allocations_reservation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.reservation_allocations
    ADD CONSTRAINT reservation_allocations_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES public.reservations(reservation_id);


--
-- Name: reservations reservations_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.account_balances(account_id);


--
-- Name: wallets wallets_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: ledger_migrator
--

ALTER TABLE ONLY public.wallets
    ADD CONSTRAINT wallets_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.account_balances(account_id);


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

GRANT USAGE ON SCHEMA public TO ledger_app;


--
-- Name: TABLE account_balances; Type: ACL; Schema: public; Owner: ledger_migrator
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.account_balances TO ledger_app;


--
-- Name: TABLE balance_buckets; Type: ACL; Schema: public; Owner: ledger_migrator
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.balance_buckets TO ledger_app;


--
-- Name: TABLE ledger_config; Type: ACL; Schema: public; Owner: ledger_migrator
--

GRANT SELECT ON TABLE public.ledger_config TO ledger_app;


--
-- Name: TABLE ledger_entries; Type: ACL; Schema: public; Owner: ledger_migrator
--

GRANT SELECT,INSERT ON TABLE public.ledger_entries TO ledger_app;


--
-- Name: TABLE reservation_allocations; Type: ACL; Schema: public; Owner: ledger_migrator
--

GRANT SELECT,INSERT ON TABLE public.reservation_allocations TO ledger_app;


--
-- Name: TABLE reservations; Type: ACL; Schema: public; Owner: ledger_migrator
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.reservations TO ledger_app;


--
-- Name: TABLE wallets; Type: ACL; Schema: public; Owner: ledger_migrator
--

GRANT SELECT,INSERT,UPDATE ON TABLE public.wallets TO ledger_app;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: ledger_migrator
--

ALTER DEFAULT PRIVILEGES FOR ROLE ledger_migrator IN SCHEMA public GRANT SELECT,USAGE ON SEQUENCES TO ledger_app;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: ledger_migrator
--

ALTER DEFAULT PRIVILEGES FOR ROLE ledger_migrator IN SCHEMA public GRANT SELECT,INSERT ON TABLES TO ledger_app;


--
-- PostgreSQL database dump complete
--

\unrestrict vaulletschemadocs

