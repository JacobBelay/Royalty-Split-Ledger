# Royalty Split Ledger

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

New to the code? Read **[docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md)** first. It gives a reading order and
explains each concept. Every source file also opens with a comment saying what it does, why, and which files
it connects to.

## Requirements

- JDK 21
- Maven 3.9+ (or the Maven built into IntelliJ / Eclipse)
- **Docker Desktop** running, for the integration tests (Testcontainers starts a throwaway `postgres:16-alpine`)
- PostgreSQL 16, only if you want to run the app itself (not needed for tests)

## Run the tests

```bash
mvn test      # unit tests only (*Test): pure Java, no database, takes seconds
mvn verify    # unit + integration tests (*IT): real Postgres via Testcontainers
```

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

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/collaborators` | `{"name":"Ana","payoutThreshold":25.00}`, where the threshold defaults to 50.00 → 201 |
| GET | `/api/collaborators/{id}/balances` | payable, outstanding advance, threshold |
| POST | `/api/collaborators/{id}/advances` | `{"amount":300.00}` → 201 |
| POST | `/api/collaborators/{id}/payouts` | header `Idempotency-Key` is required. 201 = new, 200 = replay, 422 = below threshold |
| POST | `/api/releases` | `{"title":"...","shares":[{"collaboratorId":1,"percent":60},...]}`, shares must total exactly 100 |
| POST | `/api/statements` | `{"source":"DistroKid","externalRef":"2026-08","lines":[{"releaseId":1,"amount":100.00}]}`. 201 = new, 200 = same statement again, 409 = same reference but different numbers |
| GET | `/api/ledger/trial-balance` | total debits, total credits, `balanced` |

Errors use the RFC 7807 `application/problem+json` format: 400 for bad input, 404 when something is not
found, 409 for a conflict, and 422 when a business rule refuses the request.

```bash
curl -X POST localhost:8080/api/collaborators -H "Content-Type: application/json" -d '{"name":"Ana"}'
curl -X POST localhost:8080/api/collaborators/1/payouts -H "Idempotency-Key: pay-ana-1"
```

## Status

The core is done: splitting, recoupment, thresholds, the ledger, idempotency, locking, and about 66 test
cases. Still to do before the rest of the résumé bullet is true:

1. JWT sign-in and roles (Spring Security), e.g. ADMIN imports statements, ARTIST reads only their own balances
2. More tests to reach 70+, and a JaCoCo coverage report to confirm 90% on `payout` + `ledger`
3. A GitHub Actions workflow that runs `mvn verify` on every push
4. Deployment to AWS (Elastic Beanstalk or ECS + RDS Postgres)

How this was checked: the SQL schema and its triggers were tested by hand against a real PostgreSQL 16. That
covered balanced and unbalanced commits, blocked UPDATE/DELETE, `ON CONFLICT` idempotency, and a two-session
`FOR UPDATE` demo. The split math also passed a 20,000-case random check. The Maven build itself has not been
run yet, because Maven Central was unreachable from the machine that wrote this. **Run `mvn verify` first**
and fix any small compile slips it reports.
