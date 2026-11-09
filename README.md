# Royalty Split Ledger

[![CI](https://github.com/JacobBelay/Royalty-Split-Ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/JacobBelay/Royalty-Split-Ledger/actions/workflows/ci.yml)

A Spring Boot REST API that splits music royalties between collaborators and records every cent in a
double-entry ledger that can only be added to (append-only). It is the project behind these résumé bullets:

| Résumé claim | Where it lives | What proves it |
|---|---|---|
| BigDecimal math + remainder rule, every cent accounted for | `money/Money`, `split/SplitCalculator` | `SplitCalculatorTest` (5,000-run property test) |
| Advance recoupment | `recoupment/RecoupmentCalculator`, `statement/StatementImportService` | `RecoupmentCalculatorTest`, `StatementImportIT` |
| Payout thresholds | `payout/PayoutService` | `PayoutIT` |
| Append-only double-entry ledger in PostgreSQL | `V1__ledger_schema.sql`, `ledger/*` | `LedgerDatabaseRulesIT`, `JournalEntryTest` |
| Idempotent statement imports | `StatementImportService` + `UNIQUE(source, external_ref)` | `StatementImportIT` (8 concurrent identical imports post once) |
| Row-level locking prevents duplicate payouts | `CollaboratorRepository.lockForUpdate` | `PayoutIT.simultaneousPayoutRequestsPayExactlyOnce` |
| JWT sign-in and role-based access | `security/*`, `V2__app_user.sql` | `SecurityAccessIT`, `AuthIT`, `AccessPolicyTest`, `TokenServiceTest` |
| 70+ JUnit and Testcontainers tests | `src/test/**` | **134 tests**: `mvn verify` |
| 90% coverage on payout and ledger | JaCoCo gate in `pom.xml` | **100%** on both; the build fails below 90% |
| GitHub Actions CI pipeline | `.github/workflows/ci.yml` | every push runs `mvn verify` on a clean machine |

New to the code? Read **[docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md)** first. It gives a reading order and
explains each concept. Every source file also opens with a comment saying what it does, why, and which files
it connects to.

## Requirements

- JDK 21
- Maven 3.9+ (or the Maven built into IntelliJ / Eclipse)
- **Docker Desktop** running, for the integration tests (Testcontainers starts a throwaway
  `postgres:16-alpine`). No Docker? See the `TEST_DATABASE_URL` alternative below.
- PostgreSQL 16, only if you want to run the app itself (not needed for tests)

## Run the tests

```bash
mvn test      # 74 unit tests (*Test): pure Java, no database, takes seconds
mvn verify    # all 134 tests: adds the 60 integration tests (*IT) on real Postgres via Testcontainers,
              # then writes the coverage report and fails if payout/ledger drops below 90%
```

`mvn verify` also writes **`target/site/jacoco/index.html`** — open it to see coverage per class.

No Docker? Point the integration tests at any empty Postgres database instead:

```bash
# Windows PowerShell
$env:TEST_DATABASE_URL="jdbc:postgresql://localhost:5432/royalties_test"
$env:TEST_DATABASE_USER="postgres"; $env:TEST_DATABASE_PASSWORD="postgres"
mvn verify
```

Warning: the tests `TRUNCATE` every table before each test, so never point them at data you care about.

## Run the app

```bash
createdb royalties                      # or create it in pgAdmin
mvn spring-boot:run                     # Flyway creates the tables on startup
```

Connection settings come from `DB_URL`, `DB_USER`, `DB_PASSWORD`. They default to
`jdbc:postgresql://localhost:5432/royalties` with user and password `royalties`.

On an empty database the app creates one admin account (`admin` / `admin` by default) so you can sign in
immediately — see [Authentication](#authentication) for how to change it, and do change it.

## API

Every endpoint except `POST /api/auth/login` needs a bearer token; see [Authentication](#authentication).

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | — | `{"username":"admin","password":"admin"}` → 200 with a token, or 401 |
| POST | `/api/auth/users` | ADMIN | `{"username":"ana","password":"...","role":"ARTIST","collaboratorId":1}` → 201 |
| POST | `/api/collaborators` | ADMIN | `{"name":"Ana","payoutThreshold":25.00}`, where the threshold defaults to 50.00 → 201 |
| GET | `/api/collaborators/{id}/balances` | ADMIN, or the ARTIST who **is** `{id}` | payable, outstanding advance, threshold |
| POST | `/api/collaborators/{id}/advances` | ADMIN | `{"amount":300.00}` → 201 |
| POST | `/api/collaborators/{id}/payouts` | ADMIN, or the ARTIST who **is** `{id}` | header `Idempotency-Key` is required. 201 = new, 200 = replay, 422 = below threshold |
| POST | `/api/releases` | ADMIN | `{"title":"...","shares":[{"collaboratorId":1,"percent":60},...]}`, shares must total exactly 100 |
| POST | `/api/statements` | ADMIN | `{"source":"DistroKid","externalRef":"2026-08","lines":[{"releaseId":1,"amount":100.00}]}`. 201 = new, 200 = same statement again, 409 = same reference but different numbers |
| GET | `/api/ledger/trial-balance` | ADMIN | total debits, total credits, `balanced` |

Errors use the RFC 7807 `application/problem+json` format: 400 for bad input, **401 when you are not signed
in**, **403 when your role or ownership does not allow it**, 404 when something is not found, 409 for a
conflict, and 422 when a business rule refuses the request. Anything not listed above is denied, so an
unknown path answers 403 rather than confirming what exists.

```bash
# 1. sign in (the first-run admin is created automatically on an empty database)
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

# 2. use it
curl -X POST localhost:8080/api/collaborators -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"name":"Ana"}'

curl -X POST localhost:8080/api/collaborators/1/payouts -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: pay-ana-1"
```

## Authentication

Sign-in is a JSON Web Token: `POST /api/auth/login` returns a signed token, and every other request sends it
as `Authorization: Bearer <token>`. Nothing is stored server-side, so any instance can serve any request —
and a token cannot be revoked before it expires, which is why the lifetime is short.

Two roles:

- **ADMIN** — staff. Creates collaborators and users, imports statements, issues advances, reads the ledger.
- **ARTIST** — a royalty earner, linked to exactly one collaborator. May read their own balances and request
  their own payout. Nothing else, and nobody else's anything.

That last restriction is enforced by comparing the `{id}` in the URL with the `collaboratorId` claim inside
the caller's token (`security/AccessPolicy`), not by the role alone — a role check would let any artist read
every other artist's earnings. `SecurityAccessIT` proves it, including that a 403 leaves the ledger untouched.

### Configuration

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | a dev-only placeholder | **Set this anywhere real.** Must be 32+ characters; the app refuses to start otherwise. Generate with `openssl rand -base64 48` |
| `TOKEN_TTL` | `1h` | How long a token lasts |
| `BOOTSTRAP_ADMIN_USERNAME` | `admin` | First-run admin, created only while `app_user` is empty |
| `BOOTSTRAP_ADMIN_PASSWORD` | `admin` | Change it; the app logs a warning while the default is in use |

The default `JWT_SECRET` is committed to this repository, so anyone who reads it could mint a valid ADMIN
token against a server still using it. It exists so `mvn spring-boot:run` works on a fresh clone, and for
nothing else.

## Status

Done and verified by `mvn verify`: splitting, recoupment, payout thresholds, the append-only double-entry
ledger, idempotent imports, row-level locking, JWT sign-in with roles, and CI.

| | |
|---|---|
| Tests | **134** (74 unit, 60 integration), all passing |
| Coverage | `payout` **100%**, `ledger` **100%** (instructions, lines and branches); project total 98.8% instructions |
| Coverage gate | `mvn verify` fails if `payout` or `ledger` drops below 90% |
| CI | `.github/workflows/ci.yml` runs the whole thing on every push |

Still to do:

1. **Deployment to AWS** (Elastic Beanstalk or ECS + RDS Postgres). This is the one remaining résumé claim
   that is not yet true — nothing here deploys anywhere.
2. Token refresh, and an admin endpoint to list or disable users.
3. A `REVERSAL` entry kind, so a mistaken statement can be undone the append-only way (exercise 1 in the
   learning guide).

### How the claims above were checked

- `mvn verify` runs on JDK 21 against PostgreSQL 16 and passes: 74 unit tests, then 60 integration tests that
  exercise the real triggers, unique constraints and `FOR UPDATE` row locks — balanced and unbalanced
  commits, blocked `UPDATE`/`DELETE`, `ON CONFLICT` idempotency, and 8 concurrent threads racing for one
  payout.
- The coverage numbers come from the JaCoCo report the build writes, merging unit and integration data.
- The 90% gate was tested by temporarily raising it to 99.9% and confirming the build failed naming the
  package — so it is a real gate, not a rule that quietly matches nothing.
- The security rules were checked by forging the tokens a client never sends: expired, signed with a foreign
  key, wrong issuer, payload tampered, and no roles at all. Each is refused (`SecurityAccessIT`).

If `mvn verify` cannot reach Docker on your machine, use the `TEST_DATABASE_URL` route under
[Run the tests](#run-the-tests); that is how the suite was last run end to end.
