# wallet-ledger-service

The Vaullet ledger: atomic balance reservations, per
[ADR-004](../architecture/docs/adr/004-atomic-balance-reservations.md), and the wallets an account's
money is grouped into.

This is the only place the balance invariant is decided. Everything else in the platform asks this
service whether money is available, and it answers by holding it.

Built on the [`@vaullet-dev` spring-boot-template](../../spring-boot-template) — Spring Boot 4.1.1,
Java 21, three layers (`api` → `service` → `dao`) enforced by ArchUnit — with two deliberate
departures, both documented below.

The cross-cutting plumbing comes from [`@vaullet-dev/backend-common`](../backend-common)
([ADR-013](../architecture/docs/adr/013-backend-common-shared-library.md)): the problem+json error
body, the JWT security chain, CORS, the OpenAPI bearer scheme, the composed `@IntegrationTest` and
the ArchUnit rules. **What is left in this repository is the ledger**, plus the handful of places it
deliberately differs from the platform default.

```
./mvnw spring-boot:run     # PostgreSQL via Compose, Flyway migrates, serves on :8080
./mvnw test                # 82 unit + slice + architecture tests, no Docker  (~5s)
./mvnw verify              # adds 69 integration tests on real PostgreSQL     (~25s)
```

> `dev.vaullet:common-*` 1.0.0 resolves from GitHub Packages, which requires authentication even for
> a public artifact: a `github` server in `~/.m2/settings.xml` with a `read:packages` token, as for
> wallet-auth-service.

Then open <http://localhost:8080/swagger-ui.html>, or the collection in [`bruno/`](bruno) — see
[Calling it by hand](#calling-it-by-hand). The ledger in the cluster is reachable from inside it only;
[Deploying](#deploying) says how it runs there and how to reach it.

---

## The API

ADR-004's surface and the wallets, versioned in the path per
[ADR-011](../architecture/docs/adr/011-api-versioning-and-openapi.md).

```
POST   /v1/reservations           → 201 {reservation_id, amount, state, expires_at, allocations[]}
                                  | 409 INSUFFICIENT_FUNDS | 422 HOLD_TTL_TOO_LONG
                                  | 503 ACCOUNT_BUSY
GET    /v1/reservations/{id}      → 200 (same representation)
DELETE /v1/reservations/{id}      → 204 (release)
GET    /v1/accounts/{id}/balance  → 200 {currency, posted, held, available, withdrawable, debt, wallets[]}

POST   /v1/wallets                → 201 {wallet_id, account_id, type, status, created_at, updated_at, closed_at?}
                                  | 409 WALLET_CLOSED
GET    /v1/wallets                → 200 {data[], next_cursor?}
                                    filtered by ?account_id ?type ?status, paged by ?limit ?cursor
GET    /v1/wallets/{id}           → 200 (same representation)
PATCH  /v1/wallets/{id}           → 200 (freeze or unfreeze: {"status": "FROZEN" | "ACTIVE"})
                                  | 409 WALLET_CLOSED
DELETE /v1/wallets/{id}           → 204 (close) | 409 WALLET_NOT_EMPTY
GET    /v1/wallets/{id}/balance   → 200 {wallet_id, …, currency, posted, held, available, withdrawable,
                                         debt, buckets[]}
```

An unknown id is `404 RESOURCE_NOT_FOUND` on every route that takes one, and a body or query
parameter that fails validation is `400 VALIDATION_FAILED`, with an `errors` array naming the
fields, before any rule runs. A path id or a body that does not parse at all —
`/v1/wallets/not-a-uuid`, a body whose `account_id` is not a UUID, broken JSON — is still Spring's
plain `400` with no `code`, on every route; that gap is the platform's, so it is `backend-common`'s
to close.

`Idempotency-Key` is **required** on `POST /v1/reservations`. A replay returns the original hold and
places no second one — which matters more here than on a typical endpoint, because a duplicated hold
silently overstates what an account owes.

`POST /v1/wallets` takes no `Idempotency-Key`, because it already carries a natural key: an account
has at most one wallet of each type, so a repeat returns the wallet the first call opened, with the
same `201`. That is the argument auth-service makes for `POST /v1/accounts` and its `external_ref`.

`expires_in_seconds` is optional (default 300, ceiling `ledger_config.max_hold_seconds`). ADR-004
gives the lifetime to the caller because only the caller knows its settlement horizon: a card
authorization resolves in milliseconds, a match-winner market in three days.

Wire conventions, all from ADR-011 §7–8:

| | |
| --- | --- |
| Keys | `snake_case` |
| Money | decimal **string** (`"60.0000"`), never a JSON number |
| Timestamps | RFC 3339 UTC |
| Lists | `{"data": [...], "next_cursor": "..."}`, a cursor rather than an offset; `next_cursor` is absent on the last page |
| Errors | RFC 9457 `problem+json` with a stable `code` and a `trace_id` |

```json
{
  "type": "https://docs.vaullet.dev/errors/insufficient-funds",
  "title": "Insufficient funds",
  "status": 409,
  "detail": "available 40.0000 < requested 60.0000",
  "instance": "/v1/reservations",
  "code": "INSUFFICIENT_FUNDS",
  "trace_id": "68f0a1..."
}
```

**`code` is the contract; `detail` is for humans.** Branch on `code`, never on the prose.

The two balance reads, `GET /v1/accounts/{id}/balance` and `GET /v1/wallets/{id}/balance`, are
advisory and say so in their OpenAPI descriptions. A balance read outside a transaction is stale the
instant it is returned — ADR-001 failed precisely because a caller treated one as a decision.

---

## Wallets

A wallet groups ADR-004's balance buckets; it does not replace them. An account has at most one
wallet of each type:

| Type | Holds | |
| --- | --- | --- |
| `REAL` | the `CASH` bucket, and the `DEBT` bucket once ADR-009 creates one | Real money: deposited, spendable, withdrawable |
| `BONUS` | the promotional buckets — `BONUS`, `LOYALTY`, `REFERRAL` — one per grant | Spendable, not withdrawable while wagering is outstanding |

Everything else is as ADR-004 has it: buckets per grant, allocation by spend priority,
`account_balances` as the single lock anchor, the append-only journal. A reservation still allocates
across buckets. What a wallet adds is whether its buckets may be spent from at all. More types are
expected, and each one is a migration (the `wallets.wallet_type` CHECK) plus a line in `WalletType`.

Opening a wallet opens the account in the ledger too, if it is the account's first: `account_balances`
gets its row, and a `REAL` wallet gets its `CASH` bucket at once. A `BONUS` wallet starts with no
buckets; grants add them.

A wallet is in one of three states:

- **`ACTIVE`**: its money can be spent.
- **`FROZEN`**: its money stays where it is and cannot be spent. Reservations allocate around the
  wallet, and if the account's other buckets cannot cover a hold, the answer is
  `409 INSUFFICIENT_FUNDS`, exactly as if the money were not there. `PATCH` back to `ACTIVE` lifts it.
- **`CLOSED`**: terminal. `DELETE` closes a wallet only when it is empty — nothing posted, nothing
  held, no debt — and answers `409 WALLET_NOT_EMPTY` otherwise, because money in a closed wallet could
  be neither spent nor withdrawn. The row and its buckets stay, since the journal points at them, and
  the account cannot open another wallet of that type (`409 WALLET_CLOSED`).

Freezing, unfreezing and closing are all safe to retry: asking for the status a wallet already has
changes nothing. `CLOSED` is not something `PATCH` accepts; closing has a precondition and no way
back, so it is its own verb.

A frozen wallet's money is still the user's. It stays in the figures that say what is there, and
leaves the ones that say what can be spent:

| | Wallet balance | Account balance |
| --- | --- | --- |
| `posted`, `held` | over the wallet's buckets, `DEBT` excluded, whatever its status | the account's totals |
| `debt` | over the wallet's `DEBT` buckets | the account's total |
| `available`, `withdrawable` | zero unless the wallet is `ACTIVE` | `ACTIVE` wallets only, so the sum of the wallets' figures |

`available` means one thing everywhere: what a hold could draw on now. So besides a frozen wallet it
leaves out `DEBT`, which is owed rather than held, and a grant past its expiry, which stays posted
until the sweeper forfeits it but which `reserve` will not spend. A bucket's `available` follows the
same rule, and a wallet's is its buckets' added up.

The wallet balance lists its buckets, `CASH` first and then by spend priority, each with posted,
held, available, whether it is withdrawable, wagering remaining and expiry. The account balance lists
every wallet's totals in `wallets[]`, `REAL` first and closed ones included, without their buckets.
The account's totals and the per-wallet figures are separate reads, so under concurrent holds they
can disagree by whatever moved in between; neither is a decision anyway.

Every balance carries the deployment's `currency`, from `ledger_config`, which the ledger now writes
at startup from `ledger.currency` — see [Configuration and profiles](#configuration-and-profiles).

---

## Calling it by hand

[`bruno/`](bruno) is a [Bruno](https://www.usebruno.com) collection for the wallet endpoints and the
account balance, kept in this repository so a request changes in the same pull request as its
endpoint. It is in Bruno's YAML format, which needs Bruno 3.1 or later: *Open Collection* on the
folder.

| Environment | Base URL | Sign-in |
| --- | --- | --- |
| `local` | `http://localhost:8080` | None. The `local` profile runs every request as the anonymous principal with `ledger:read` and `ledger:write`, and the environment's empty `oauth2_token_url` keeps Bruno from asking for a token. |
| `prod` | `http://localhost:18081`, a port-forward to the cluster | OAuth2 client credentials as Keycloak client `ledger-ops`, which holds both scopes. Set the environment's secret `oauth2_client_secret` first: Bruno keeps secret values on the machine and out of the files. See [Deploying](#deploying). |

OAuth2 is set once, on the collection, and sends the client credentials in the form body rather than
a Basic header: Bruno does not form-encode a Basic header's id and secret first, Keycloak decodes them
as if it had, and a generated secret with a `+` in it would never match.

The *Wallets* folder, run top to bottom, opens a `REAL` and a `BONUS` wallet for the environment's
`account_id`, reads them and the account balance, freezes and unfreezes the `REAL` one, and closes
both. *Open REAL wallet* keeps `real_wallet_id` and *Open BONUS wallet* keeps `bonus_wallet_id` for
the requests under them. Closing is terminal, so set `account_id` to a fresh UUID before the next run;
against the same one, *Open REAL wallet* answers `409 WALLET_CLOSED`. Every request asserts its
status, so `bru run --env local` in `bruno/` (the CLI, `@usebruno/cli`) checks a local run end to end.

In `prod` nothing removes what a run leaves behind: `ledger_app` deletes nothing, and a closed wallet
stays closed. So `prod`'s `account_id` starts empty, and a folder run there belongs on an account made
for it. Client credentials need no browser, so the CLI runs `prod` too, given the secret:
`bru run --env prod --env-var oauth2_client_secret="$LEDGER_OPS_SECRET" --env-var account_id=…`.

Reservations are not in it yet. Until credits land, nothing puts money into an account through the
API, so a hold could only ever answer `409 INSUFFICIENT_FUNDS`.

---

## Three deviations from the platform default

All deliberate, all argued in the code next to the thing they affect.

**1. JDBC, not Spring Data JPA.** The correctness argument in ADR-004 *is* the SQL: `SELECT … FOR
UPDATE`, the lock ordering, the allocation query that excludes `DEBT`, the `CHECK` constraints. An
ORM would generate and therefore hide exactly the statements a reviewer has to read. `LedgerRepository`,
`WalletRepository` and `LedgerConfigRepository` are hand-written DAOs holding every statement the
ledger issues and taking no decisions; `LedgerService` and `WalletService` own the rules and the transaction boundary. The layer
names and boundaries are unchanged, so `LayeringTest` still applies — with the entity rule swapped
for its JDBC equivalent (nothing outside `dao` may touch `JdbcTemplate`).

**2. Path versioning, not the `X-API-Version` header.** The template resolves the API version from a
header. ADR-011 §2 chose the major version in the URI (`/v1/reservations`) and rejected header
versioning outright — a path is greppable in logs and dashboards, and a missing header is an
implicit version nobody notices. The `spring.mvc.apiversion.*` block is therefore absent from
`application.yaml` and from the platform defaults, with a comment saying why.

**3. `ACCOUNT_BUSY`, not `RESOURCE_BUSY`.** `backend-common` answers a contended row with
`RESOURCE_BUSY`. Here the contended row is always an account, `ACCOUNT_BUSY` is already published in
this service's contract, and renaming a published code is a breaking change under ADR-011 §4. So
`LedgerApiExceptionHandler` overrides one method and inherits everything else. It is ten lines, and
`ReservationControllerTest` fails if anyone drops them.

---

## Package structure

```
dev.vaullet.ledger
├── LedgerApplication.java            entry point: capabilities only, no beans
├── package-info.java                 @NullMarked — JSpecify null-safety, for this package only
│
├── common/api/                       wire shapes every feature's API shares
│   └── ListResponse.java             {data, next_cursor} — ADR-011 §8
│
├── common/error/                     the ledger's share of the error vocabulary
│   ├── LedgerErrorType.java          INSUFFICIENT_FUNDS, HOLD_TTL_TOO_LONG, CURRENCY_MISMATCH,
│   │                                 ACCOUNT_BUSY, WALLET_CLOSED, WALLET_NOT_EMPTY — money
│   │                                 codes, so they live here
│   ├── LedgerApiExceptionHandler.java  the shared advice, with ACCOUNT_BUSY kept
│   └── exception/                    the concrete throwables
│       ├── InsufficientFundsException.java
│       ├── HoldTtlTooLongException.java
│       ├── WalletClosedException.java
│       ├── WalletNotEmptyException.java
│       └── InvalidWalletSearchException.java  the platform's VALIDATION_FAILED
│
├── reservation/                      ← a feature slice: holds, and the account balance
│   ├── api/                          controllers + request/response records
│   ├── service/                      LedgerService — rules, transactions, domain records
│   └── dao/                          LedgerRepository — every SQL statement, and nothing else
│
├── wallet/                           ← a feature slice: wallets and their balances
│   ├── api/                          WalletController + request/response records
│   ├── service/                      WalletService — rules, transactions, domain records;
│   │                                 WalletCursor — the opaque keyset cursor
│   └── dao/                          WalletRepository — every SQL statement, and nothing else
│
└── config/                           ← a feature slice: the ledger_config row (no HTTP)
    ├── service/                      LedgerProperties (ledger.*), LedgerConfigProvisioner —
    │                                 writes the currency once, checks it on every start
    └── dao/                          LedgerConfigRepository
```

The account balance stayed in `reservation/api`, where it was, and reads the per-wallet figures
through `WalletService`. One slice's controller calling another slice's service is an API → service
dependency, which is what the layers allow.

**`config/` is the `ledger_config` table, not Spring configuration.** The template's Spring
`config/` package is gone. It held six classes — `SecurityConfig`, `LocalSecurityConfig`,
`MethodSecurityConfig`, `WebMvcConfig`, `OpenApiConfig`, `ApplicationProperties` — and all six were
near-identical to the template's copies. They are now auto-configured by `common-web` and
`common-security`, and configured through `vaullet.*` keys in `application.yaml`. Likewise
`common/error` no longer holds `ErrorType`, `ApplicationException`, `ProblemDetails`,
`ApiExceptionHandler` or `ResourceNotFoundException`; what remains is the part that is about money.

**The catalogue stays up, the throwables go down.** `error/` holds what the API may *say* —
the codes and the advice that renders them — and `error/exception/` holds what the code *throws*.
The split is a seam rather than a folder: `exception` depends up on `error`, because each throwable
names its error type, and nothing depends the other way. Applied across every Vaullet
repository on 2026-09-17, `backend-common` included.

Each package under here carries its own `package-info.java` with `@NullMarked`. That is not tidiness:
**Java packages do not nest for annotation purposes**, so the declaration on `dev.vaullet.ledger`
reaches only the classes directly in it. `spring-core` ships fifty of these files for the same
reason. The `reservation/` packages and `common/error` itself still need theirs.

`ErrorType` is an interface in `common-core`, which is what lets `LedgerErrorType` exist at
all. The platform catalogue holds only what a service that knows nothing about money would raise —
see [ADR-013 §3](../architecture/docs/adr/013-backend-common-shared-library.md).

**Package by feature, layered inside.** `LayeringTest` (ArchUnit) fails the build if the API reaches
into the DAO, if a controller injects a repository, if `JdbcTemplate` appears outside `dao`, if the
service layer touches a servlet type, or if anyone uses field injection. Documented-only
architecture decays; the first PR that skips a layer "just this once" gets approved by someone in a
hurry.

The rules themselves come from `ArchitectureRules` in `common-test`, so `LayeringTest` is
the lines that point them at this codebase plus the one rule that is genuinely local (the JDBC one
below). A rule that is copied is a rule that gets edited locally, and a boundary each service defines
slightly differently is not a platform boundary.

The `service`-knows-nothing-about-HTTP rule is not decorative here: ADR-004's revised flow settles
and releases from a Kafka listener, which has no request to bind.

---

## Testing

| Level | Class | Context | Count |
| --- | --- | --- | --- |
| Architecture | `LayeringTest` | ArchUnit, no Spring | 8 rules |
| Web slice | `ReservationControllerTest` | `@WebMvcTest` + the real security chain | 12 |
| Web slice | `WalletControllerTest` | `@WebMvcTest` + the real security chain | 33 |
| Web slice | `AccountBalanceControllerTest` | `@WebMvcTest` + the real security chain | 4 |
| Unit | `WalletCursorTest` | none | 13 |
| Unit | `LedgerConfigProvisionerTest` | none | 12 |
| Ledger invariants | `OverdraftIT` | `@IntegrationTest` + Testcontainers | 12 |
| Persistence | `LedgerRepositoryIT` | Testcontainers | 1 |
| Full stack | `ReservationApiIT` | `@IntegrationTest` + Testcontainers | 5 |
| Wallets | `WalletServiceIT` | `@IntegrationTest` + Testcontainers | 27 |
| Migration | `WalletsMigrationIT` | Flyway's API on its own schema | 3 |
| Startup | `LedgerConfigProvisionerIT` | `@IntegrationTest` + Testcontainers | 4 |
| Grants | `AppRoleGrantsIT` | `@IntegrationTest`, connected as `ledger_app` | 17 |

The slice tests name the security chain with
`@ImportAutoConfiguration(PlatformSecurityAutoConfiguration.class)` rather than `@Import`: a
`@WebMvcTest` slice loads only the auto-configurations Boot itself lists for that slice, never a
third-party one. Without that line the 401 and 403 assertions would pass against no security at all,
which is the failure mode worth knowing about before you write your second service.

`WalletControllerTest` counts each case of its parameterised tests, as Surefire does. Besides the
scopes, it pins the three failures wallets add — `WALLET_CLOSED`, `WALLET_NOT_EMPTY` and
`RESOURCE_NOT_FOUND` — as their own codes rather than 500s, and that a `PATCH` to `CLOSED` is refused
before the service is called.

`*Test` runs under Surefire in `mvn test`; `*IT` under Failsafe in `mvn verify`. Keeping `mvn test`
fast is what makes it a thing people actually run.

`OverdraftIT` is the one that matters. It replays the exact ADR-001 trace — two withdrawals 50ms
apart, not even concurrent — and proves the invariant holds, that eight concurrent withdrawals
against a balance funding one leave exactly one winner, that the schema refuses an overdraft even
when the application logic is wrong, that the journal cannot be rewritten, and that `DEBT` cannot be
spent.

`AppRoleGrantsIT` is the deployed role's. Every other test connects as the database's owner, which
may do anything, so a grant missing from V3 would show only in production. It builds the
repositories and services by hand on a connection that is `ledger_app`'s, with a
`TransactionTemplate` standing in for `@Transactional`, and runs the real paths through it: open,
freeze, unfreeze and close; reserve, settle and release; every balance read. Then it asserts the
refusals, by SQLSTATE `42501` rather than by message: no `DELETE` or `TRUNCATE` anywhere, no `UPDATE`
of what is written once, no `INSERT` into `ledger_config`, nothing on `flyway_schema_history`, no DDL.
V3 leaves the role `NOLOGIN`, so each test lends it a login and takes it back.

**Testcontainers, not H2.** Every load-bearing part of this schema is PostgreSQL-specific:
`SELECT … FOR UPDATE`, partial and functional unique indexes, `NUMERIC(20,4)`, `TIMESTAMPTZ`,
`make_interval`, and the `DO INSTEAD NOTHING` rules that make `ledger_entries` append-only. An
embedded database would either reject the migration or — far worse — accept it and silently not
enforce it.

Every integration test uses the composed `@IntegrationTest` annotation. Spring caches contexts by
their configuration, so one test class declaring its own `@Container` starts a second PostgreSQL and
a second context for the same suite.

---

## Configuration and profiles

| Profile | Activated by | Posture |
| --- | --- | --- |
| *(none)* | `java -jar` | Secure by default. Requires `OAUTH2_ISSUER_URI`, and `LEDGER_CURRENCY` against a database that has no currency yet; refuses to start without them. |
| `local` | `./mvnw spring-boot:run` | Compose-managed database, permissive HTTP security, method security still on, SQL logging, all Actuator endpoints, 100% trace sampling. |
| `test` | test classes | Testcontainers, 100% sampling. |
| `prod` | the Deployment, `SPRING_PROFILES_ACTIVE=prod` | ECS JSON logs, Compose off, tuned pools, and Flyway **off**: the service holds `ledger_app` and never migrates. |
| `migrate` | the migration Job, `SPRING_PROFILES_ACTIVE=prod,migrate` | Flyway on, no web server, `statement_timeout` lifted, a pool of two. Migrates, provisions `ledger_config` and exits. |

`application-local.yaml` is committed, unlike in the template, which gitignores it. A clean checkout
that lacks the profile `spring-boot:run` activates starts with a different security posture than
this README describes; the file holds no secrets, and the local database credentials are already in
`compose.yaml` for the same reason. **This is worth fixing in the template itself, not just here.**

### Where each key comes from

| Prefix | Owner | Examples |
| --- | --- | --- |
| `spring.*`, `server.*`, `management.*`, `logging.*` | mostly `vaullet/platform-defaults.yaml`, imported | virtual threads, snake_case JSON, Actuator exposure, tracing sampling |
| `vaullet.*` | `backend-common` | `vaullet.api.allowed-origins`, `vaullet.security.local.anonymous-authorities`, `vaullet.openapi.title` |
| `app.*` | this service | `app.environment`, and nothing else |
| `ledger.*` | this service | `ledger.currency`, `ledger.minor-units` — written to `ledger_config` once, below |

The platform defaults are imported with an explicit `spring.config.import`, not applied behind the
service's back by an auto-configuration. An imported document ranks *below* the file that imports it,
so every key above can still be overridden here — and a reader of `application.yaml` can follow one
line to everything that is set, rather than concluding from silence that nothing is.

`app.*` holding exactly one label is the point. The two settings most services would expect to find
there — the default hold lifetime and its ceiling — are absent deliberately: ADR-004 puts the ceiling
in `ledger_config.max_hold_seconds`, in the database, read inside the same transaction that enforces
it. Mirroring it into YAML would give the ledger two sources of truth for one rule.

Hold policy is deliberately absent from `application.yaml`: ADR-004 puts the ceiling in
`ledger_config.max_hold_seconds`, in the database, read inside the same transaction that enforces
it. Mirroring it into YAML would give the ledger two sources of truth for one rule.

`ledger.currency` looks like the same mistake and is not: it does not mirror
`ledger_config.currency`, it provisions it. Nothing else writes that row. At startup, if the table
is empty, the ledger inserts the configured currency, and refuses to start when none is configured;
once the row exists it is the truth, and a configured currency that differs from it stops startup
rather than letting every balance be reported in the wrong one. ADR-004 writes the currency once and
never updates it, so changing it is a migration, not a configuration change. `LEDGER_CURRENCY` sets
it (ISO 4217, upper case), `ledger.minor-units` (`LEDGER_MINOR_UNITS`) defaults to that currency's
own fraction digits and is checked against the row the same way, and the `local` profile and the
tests use EUR. Every deployment must set `LEDGER_CURRENCY`. In the cluster the first start is the
migration Job, and the service only checks the row; see [Deploying](#deploying).

`statement_timeout` is set to 1s on every connection (`connection-init-sql`). A reserve that cannot
take the account row lock in that time gives up, and the caller gets `503 ACCOUNT_BUSY` with a
`Retry-After` rather than a thread parked indefinitely. It applies in tests too, so a deadlock fails
the build in a second instead of hanging it. The `migrate` profile lifts it for the migration Job:
an `ALTER` that rewrites the journal is not a stuck reserve, and killing it at 1s would leave the
schema half-moved.

---

## Deploying

The ledger runs in the prod cluster the way wallet-auth-service does: Argo CD reconciles namespace
`wallet-ledger` to [`kustomization.yaml`](kustomization.yaml) at the root of `master`, and the
Application itself lives in gitops. **It has no HTTPRoute.** ADR-008 has the ledger called by services
holding client-credentials tokens, never with a user's token, and every such caller is inside the
cluster. A route on vaullet.dev would put the endpoint that commits money in front of everyone with
a token from the realm, and serve no caller at all.

| File | What it is |
| --- | --- |
| [`k8s/migrate-job.yaml`](k8s/migrate-job.yaml) | Job `ledger-migrate`, an Argo Sync hook in wave 0: Flyway as `ledger_migrator`, profiles `prod,migrate` |
| [`k8s/deployment.yaml`](k8s/deployment.yaml) | Deployment `ledger-service`, wave 1: the service as `ledger_app`, profile `prod` |
| [`k8s/service.yaml`](k8s/service.yaml) | Service `ledger-service`: ClusterIP, port 80 to the container's 8080 |
| [`k8s/externalsecret-db-migrator.yaml`](k8s/externalsecret-db-migrator.yaml), [`…-db-app.yaml`](k8s/externalsecret-db-app.yaml) | The two credentials, from OpenBao's `kv/wallet-ledger/db-migrator` and `kv/wallet-ledger/db-app`, through ClusterSecretStore `openbao-wallet-ledger` |
| [`k8s/serviceaccount.yaml`](k8s/serviceaccount.yaml) | `secrets-reader`, which External Secrets logs in to OpenBao as |
| [`kustomization.yaml`](kustomization.yaml) | The namespace, the generated ConfigMap `ledger-config` (`LEDGER_CURRENCY=EUR`), and the image tag |

**Two roles, and neither process can do the other's job.** `vaullet` owns `vaullet_db` in the
CloudNativePG cluster `money` (ADR-003). `ledger_migrator`, a member of `vaullet`, runs Flyway and
owns every table; `ledger_app`, which the service connects as, holds only what
[`V3__grant_app_role.sql`](src/main/resources/db/migration/V3__grant_app_role.sql) gives it:

| Table | `ledger_app` may | Because |
| --- | --- | --- |
| `ledger_entries`, `reservation_allocations` | `SELECT`, `INSERT` | written once, never changed |
| `account_balances`, `balance_buckets`, `reservations` | `SELECT`, `INSERT`, `UPDATE` | balances and holds move; `SELECT … FOR UPDATE` on the account row needs `UPDATE` too |
| `wallets` | `SELECT`, `INSERT`, `UPDATE` | opened, then frozen, unfrozen and closed in place |
| `ledger_config` | `SELECT` | written once, by the migration Job |
| `flyway_schema_history` | nothing | so that `validate-on-migrate` can trust it |

No `DELETE` and no `TRUNCATE` anywhere, and no DDL. A table a later migration creates gets `SELECT`
and `INSERT` by default: append-only, until its own migration grants more next to its `CREATE TABLE`.
One thing a missing privilege cannot do: on `ledger_entries`, V1's RULEs rewrite an `UPDATE` or a
`DELETE` into nothing before PostgreSQL checks privileges, so `ledger_app` gets the same silent
`UPDATE 0` the owner gets, not an error. What the withheld privileges add there is `TRUNCATE`, which
no rule sees.

**The Job runs first, and the Deployment waits for it.** Both are applied by the same sync, the Job in
wave 0 and the Deployment in wave 1, so Argo rolls the Deployment only once the Job has succeeded. A
new version therefore never starts in front of a schema it was not built for, and a failed migration
fails the sync and leaves the running version as it was. The first deploy depends on the order too:
the Job starts the whole application context without a web server, so `LedgerConfigProvisioner` runs
there and records `LEDGER_CURRENCY` in `ledger_config`. The service, which may only read that row,
would refuse to start without it.

**The currency is set once, in `kustomization.yaml`.** The ConfigMap is generated, so kustomize puts
a hash of its content in the name, and changing the value changes both pod specs. The next sync's Job
then fails on the mismatch (`ledger.currency is USD, but this ledger was provisioned in EUR`) before a
single pod rolls. With a plain ConfigMap the same failure would wait for the next restart of a pod,
whenever that happened to be.

**The image tag in `kustomization.yaml` is the deployment.** The release job builds, pushes and tags
an image and deploys nothing; a version reaches the cluster when `newTag` is bumped, by hand, in a
`chore(deploy): …` commit. `chore` releases nothing, while a releasing type would cut a new version
of an image identical to the one it points at. 0.2.0 is the floor: older images have neither the
`migrate` profile nor V3.

**Calling it.** From inside the cluster, through a port-forward, with a token from Keycloak's client
`ledger-ops`: client credentials only, holding `ledger:read` and `ledger:write`. The Kubernetes API is
reachable over SSH only (gitops README, "Reaching things"), so the port-forward runs on the server and
an SSH tunnel brings it here. 18081 rather than 8080, so a local `spring-boot:run` and the tunnel can
be up at the same time.

```
ssh -L 18081:localhost:18081 root@<IP> \
  'kubectl -n wallet-ledger port-forward svc/ledger-service 18081:80'

TOKEN=$(curl -s https://vaullet.dev/auth/realms/vaullet/protocol/openid-connect/token \
          -d grant_type=client_credentials -d client_id=ledger-ops \
          --data-urlencode client_secret="$LEDGER_OPS_SECRET" \
          --data-urlencode 'scope=ledger:read ledger:write' | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" "http://localhost:18081/v1/wallets?account_id=…"
```

`/actuator/info` and `/actuator/health` need no token, so `curl localhost:18081/actuator/info` says
which build is running. The collection's `prod` environment does the same as the commands above; see
[Calling it by hand](#calling-it-by-hand).

**What has to exist first**, all of it gitops' (#29): the Argo Application (namespace
`wallet-ledger`, this repository, `master`, path `.`); `ledger_migrator`, a member of `vaullet`, and
`ledger_app` as managed roles of the `money` cluster, with their passwords read into `postgres` from
the same OpenBao paths this repository reads; `03-openbao.sh onboard wallet-ledger` and the
ClusterSecretStore `openbao-wallet-ledger`; Keycloak's `ledger:read` and `ledger:write` client scopes
and the client `ledger-ops`. The two passwords and the client's secret are put into OpenBao once,
with values you generate:

```
bao kv put kv/wallet-ledger/db-migrator     password="$(openssl rand -base64 24)"
bao kv put kv/wallet-ledger/db-app          password="$(openssl rand -base64 24)"
bao kv put kv/wallet-ledger/keycloak-client secret="$(openssl rand -base64 32)"
```

---

## Security

The chain itself is `common-security`, which is ADR-006's posture written once for the whole
platform. What this service contributes is the scopes.

- **Deny by default** — the chain ends in `anyRequest().authenticated()`.
- **Stateless JWT resource server.** No sessions; CSRF disabled *because* there is no session to ride.
- Scopes: `ledger:read` for every read — balances, reservations, wallets — and `ledger:write` for
  every change: placing or releasing a hold, opening, freezing, unfreezing or closing a wallet.
  Method security is on in every profile, including `local`, so a developer exercises the real
  authorisation rules with a principal that satisfies them.
- **Actuator beyond health/info requires `ROLE_OPERATOR`.**

**One behaviour changed in the move, and it was a bug.** This service's own copy of the authority
mapping read realm roles from a top-level `roles` claim. ADR-006 has Keycloak emit them under
`realm_access.roles`, so `hasRole(...)` would never have matched a real token — silently, with no
exception and no log line. The shared mapper reads the nested path, and
`JwtAuthorityMapperTest` in `common-security` pins it. Nothing in this service's suite
covered it, which is exactly why it survived: a test suite does not examine code the service acquired
by copy-paste.

> Note also that Spring Security 7 adds a `FACTOR_BEARER` authority of its own to every bearer-token
> principal. It shows up in an exact-match authority assertion.

Two scope names remain unreconciled: ADR-008 grants Transaction Service `ledger:reserve` on the money
path, while this service checks `ledger:write`. That is an ADR-008-versus-code question, not a
consequence of the extraction, and it is not resolved here.

---

## Known gaps

Tracked here rather than in a comment nobody finds. The first three are ADR-004 surface this service
does not yet implement, the fourth is cleanup, and the last two are where the wallets stop for now.

1. **`currency` on `POST /v1/reservations`.** ADR-004 takes an explicit currency and returns
   `422 CURRENCY_MISMATCH` against `ledger_config.currency`. `CURRENCY_MISMATCH` is already in the
   `ErrorType` catalogue (ADR-011 names it as an existing platform code) but nothing raises it yet.
   **Adding a required request field is breaking**, so this should land before any client integrates.
   The other half of ADR-004's rule, the currency alongside every amount a balance returns, is done.
2. **`available_after` in the reserve response.** ADR-004 lists it; additive, so it can follow.
3. **`buckets[]` on the account balance.** ADR-004 returns the per-bucket breakdown with the account
   balance. Here it is per wallet: `GET /v1/wallets/{id}/balance` lists each bucket's type, posted,
   held, available, withdrawability, wagering remaining and expiry, and the account balance carries
   each wallet's totals in `wallets[]` but not their buckets. A caller wanting every bucket makes one
   call per wallet, which is at most two today. `source_module` is in no representation yet.
   Additive.
4. **`LedgerService` still throws its own nested exceptions** (`InsufficientFunds`,
   `HoldTtlTooLong`, `AccountNotFound`) and surfaces unknown ids as
   `EmptyResultDataAccessException`. `LedgerErrorBridge` translates all four into the right problem
   documents. When the service throws `InsufficientFundsException`, `HoldTtlTooLongException` and
   `ResourceNotFoundException` instead, `backend-common`'s single `ApplicationException` handler
   covers them, the `@PreAuthorize` rules move from the controllers down onto the service methods
   they protect, and that bridge is deleted. It is written to be a deletion rather than a rewrite —
   and the target exceptions already exist and already carry the right `ErrorType`. `WalletService`
   throws only `ApplicationException`s (`ResourceNotFoundException`, `WalletClosedException`,
   `WalletNotEmptyException`) and needed nothing from the bridge; its `@PreAuthorize` rules sit on
   `WalletController` only to keep one convention until all of them move.
5. **Who may freeze or close a wallet, and the scopes in Keycloak.** The wallet endpoints are checked
   by scope only, like the rest of the ledger, so any caller holding `ledger:write` can freeze or
   close a wallet as well as place a hold. Which staff roles may freeze or close one is not decided.
   The `ledger:*` scopes reach the realm with the deployment (gitops#29), and for now only one client
   holds them, `ledger-ops`, for calls by hand (see [Deploying](#deploying)). Reconciling ADR-008's
   `ledger:reserve` for the money path (see [Security](#security)) would also separate the two.
6. **Nothing opens a wallet on its own.** ADR-014 §7 has the ledger consume
   `identity.account-created.v1` and create the account's row and `CASH` bucket. With wallets, that
   consumer would open the account's `REAL` and `BONUS` wallets, which today are two
   `POST /v1/wallets` calls someone makes by hand. End users reading their own wallets is not built either: every route is for callers
   holding the ledger scopes.

### Naming, still undecided

Four terms in this codebase mean one thing under two names, or two things under one. They were
surfaced on 2026-09-04 and are not resolved:

- **hold vs. reservation** — `reservations` table and `reserve()` against `DEFAULT_HOLD`,
  `HoldTtlTooLongException` and `max_hold_seconds`
- **settle vs. capture** — the `SETTLED` state and `settle()` against `captureBucket` /
  `captureAccount` in the DAO
- **"transaction"** — `ledger_entries.transaction_id` is the caller's business event;
  `@Transactional` is a database transaction. On a service whose correctness argument is entirely
  about database transactions, this is the riskiest overload here
- **"Account"** — `account_id` is on every table and there is no accounts table. Wallets settle one
  part of it, since an account is not a wallet but has up to one of each type; nothing yet says
  whether an account is a user or a customer

There is no `CONTEXT.md` yet. Renaming any of these touches published API codes, so it is an ADR-011
§4 question rather than a refactor.

---

The comments in the code are the documentation. If you change a decision, change the comment that
explains it; a service whose rationale has gone stale is worse than one with none.
