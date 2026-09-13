# wallet-ledger-service

The Vaullet ledger: atomic balance reservations, per
[ADR-004](../architecture/docs/adr/004-atomic-balance-reservations.md).

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
./mvnw test                # 20 unit + slice + architecture tests, no Docker  (~3s)
./mvnw verify              # adds 18 integration tests on real PostgreSQL     (~15s)
```

> Until `backend-common` has published anything, `0.1.0-SNAPSHOT` resolves from the local repository
> only, so a fresh clone needs `(cd ../backend-common && ./mvnw install)` once before the first
> build. Its `main` branch is now set up to publish snapshots to GHCR Maven, which removes that step
> as soon as the first run lands.

Then open <http://localhost:8080/swagger-ui.html>.

---

## The API

ADR-004's surface, versioned in the path per
[ADR-011](../architecture/docs/adr/011-api-versioning-and-openapi.md).

```
POST   /v1/reservations           → 201 {reservation_id, amount, state, expires_at, allocations[]}
                                  | 409 INSUFFICIENT_FUNDS | 422 HOLD_TTL_TOO_LONG
                                  | 503 ACCOUNT_BUSY
GET    /v1/reservations/{id}      → 200 (same representation)
DELETE /v1/reservations/{id}      → 204 (release)
GET    /v1/accounts/{id}/balance  → 200 {posted, held, available, withdrawable, debt}
```

`Idempotency-Key` is **required** on `POST /v1/reservations`. A replay returns the original hold and
places no second one — which matters more here than on a typical endpoint, because a duplicated hold
silently overstates what an account owes.

`expires_in_seconds` is optional (default 300, ceiling `ledger_config.max_hold_seconds`). ADR-004
gives the lifetime to the caller because only the caller knows its settlement horizon: a card
authorization resolves in milliseconds, a match-winner market in three days.

Wire conventions, all from ADR-011 §7–8:

| | |
| --- | --- |
| Keys | `snake_case` |
| Money | decimal **string** (`"60.0000"`), never a JSON number |
| Timestamps | RFC 3339 UTC |
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

`GET /v1/accounts/{id}/balance` is advisory and says so in its OpenAPI description. A balance read
outside a transaction is stale the instant it is returned — ADR-001 failed precisely because a
caller treated one as a decision.

---

## Three deviations from the platform default

All deliberate, all argued in the code next to the thing they affect.

**1. JDBC, not Spring Data JPA.** The correctness argument in ADR-004 *is* the SQL: `SELECT … FOR
UPDATE`, the lock ordering, the allocation query that excludes `DEBT`, the `CHECK` constraints. An
ORM would generate and therefore hide exactly the statements a reviewer has to read. `LedgerRepository`
is a hand-written DAO holding every statement the ledger issues and taking no decisions;
`LedgerService` owns the rules and the transaction boundary. The layer names and boundaries are
unchanged, so `LayeringTest` still applies — with the entity rule swapped for its JDBC equivalent
(nothing outside `dao` may touch `JdbcTemplate`).

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
├── package-info.java                 @NullMarked — JSpecify null-safety for the whole tree
│
├── common/error/                     the ledger's share of the error vocabulary
│   ├── LedgerErrorType.java          INSUFFICIENT_FUNDS, HOLD_TTL_TOO_LONG, CURRENCY_MISMATCH,
│   │                                 ACCOUNT_BUSY — money codes, so they live here
│   ├── InsufficientFundsException.java
│   ├── HoldTtlTooLongException.java
│   └── LedgerApiExceptionHandler.java  the shared advice, with ACCOUNT_BUSY kept
│
└── reservation/                      ← the feature slice
    ├── api/                          controllers + request/response records
    ├── service/                      LedgerService — rules, transactions, domain records
    └── dao/                          LedgerRepository — every SQL statement, and nothing else
```

**There is no `config/` package.** It held six classes — `SecurityConfig`, `LocalSecurityConfig`,
`MethodSecurityConfig`, `WebMvcConfig`, `OpenApiConfig`, `ApplicationProperties` — and all six were
near-identical to the template's copies. They are now auto-configured by `common-web` and
`common-security`, and configured through `vaullet.*` keys in `application.yaml`. Likewise
`common/error` no longer holds `ErrorType`, `ApplicationException`, `ProblemDetails`,
`ApiExceptionHandler` or `ResourceNotFoundException`; what remains is the part that is about money.

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
| Ledger invariants | `OverdraftIT` | `@IntegrationTest` + Testcontainers | 12 |
| Persistence | `LedgerRepositoryIT` | Testcontainers | 1 |
| Full stack | `ReservationApiIT` | `@IntegrationTest` + Testcontainers | 5 |

The slice test names the security chain with
`@ImportAutoConfiguration(PlatformSecurityAutoConfiguration.class)` rather than `@Import`: a
`@WebMvcTest` slice loads only the auto-configurations Boot itself lists for that slice, never a
third-party one. Without that line the 401 and 403 assertions would pass against no security at all,
which is the failure mode worth knowing about before you write your second service.

`*Test` runs under Surefire in `mvn test`; `*IT` under Failsafe in `mvn verify`. Keeping `mvn test`
fast is what makes it a thing people actually run.

`OverdraftIT` is the one that matters. It replays the exact ADR-001 trace — two withdrawals 50ms
apart, not even concurrent — and proves the invariant holds, that eight concurrent withdrawals
against a balance funding one leave exactly one winner, that the schema refuses an overdraft even
when the application logic is wrong, that the journal cannot be rewritten, and that `DEBT` cannot be
spent.

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
| *(none)* | `java -jar` | Secure by default. Requires `OAUTH2_ISSUER_URI`; refuses to start without it. |
| `local` | `./mvnw spring-boot:run` | Compose-managed database, permissive HTTP security, method security still on, SQL logging, all Actuator endpoints, 100% trace sampling. |
| `test` | test classes | Testcontainers, 100% sampling. |
| `prod` | `SPRING_PROFILES_ACTIVE=prod` | ECS JSON logs, Compose off, tuned pools. |

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

`statement_timeout` is set to 1s on every connection (`connection-init-sql`). A reserve that cannot
take the account row lock in that time gives up, and the caller gets `503 ACCOUNT_BUSY` with a
`Retry-After` rather than a thread parked indefinitely. It applies in tests too, so a deadlock fails
the build in a second instead of hanging it.

---

## Security

The chain itself is `common-security`, which is ADR-006's posture written once for the whole
platform. What this service contributes is the scopes.

- **Deny by default** — the chain ends in `anyRequest().authenticated()`.
- **Stateless JWT resource server.** No sessions; CSRF disabled *because* there is no session to ride.
- Scopes: `ledger:read` for the balance and reservation reads, `ledger:write` to place or release a
  hold. Method security is on in every profile, including `local`, so a developer exercises the real
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
does not yet implement; the fourth is cleanup.

1. **`currency` on `POST /v1/reservations`.** ADR-004 takes an explicit currency and returns
   `422 CURRENCY_MISMATCH` against `ledger_config.currency`. `CURRENCY_MISMATCH` is already in the
   `ErrorType` catalogue (ADR-011 names it as an existing platform code) but nothing raises it yet.
   **Adding a required request field is breaking**, so this should land before any client integrates.
2. **`available_after` in the reserve response.** ADR-004 lists it; additive, so it can follow.
3. **`buckets[]` in the balance response.** ADR-004 returns the per-bucket breakdown — type, source
   module, available, withdrawable, wagering remaining, expiry. `LedgerService.Balance` currently
   exposes only the five totals. Additive.
4. **`LedgerService` still throws its own nested exceptions** (`InsufficientFunds`,
   `HoldTtlTooLong`, `AccountNotFound`) and surfaces unknown ids as
   `EmptyResultDataAccessException`. `LedgerErrorBridge` translates all four into the right problem
   documents. When the service throws `InsufficientFundsException`, `HoldTtlTooLongException` and
   `ResourceNotFoundException` instead, `backend-common`'s single `ApplicationException` handler
   covers them, the `@PreAuthorize` rules move from the controllers down onto the service methods
   they protect, and that bridge is deleted. It is written to be a deletion rather than a rewrite —
   and the target exceptions already exist and already carry the right `ErrorType`.

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
- **"Account"** — `account_id` is on every table, there is no accounts table, and nothing says
  whether an account is a user, a wallet or a customer

There is no `CONTEXT.md` yet. Renaming any of these touches published API codes, so it is an ADR-011
§4 question rather than a refactor.

---

The comments in the code are the documentation. If you change a decision, change the comment that
explains it; a service whose rationale has gone stale is worse than one with none.
