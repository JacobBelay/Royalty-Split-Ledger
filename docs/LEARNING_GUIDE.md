# Learning Guide

This guide walks through the codebase in dependency order, so every file only uses ideas you have already
seen. Each step names the files to read and the one idea to take away. File paths are under
`src/main/java/com/jacobbelay/royalty/` unless noted.

## The story in one paragraph

Ana and Ben record a song and split it 60/40. The label gave Ben a $30 **advance**, which is money paid up
front that he must earn back. A distributor sends a **statement**: the song made $100. Ana is owed $60. Ben's
$40 first repays his advance (**recoupment**: $30), and the remaining $10 is his to keep. Ana asks to be paid.
Her $60 is above her **payout threshold**, so she gets paid. Ben's $10 is below his, so it waits until more
statements come in. Every one of these movements is written into a **double-entry ledger** that can never be
edited. The system has to stay correct when the same statement arrives twice, or when two payout requests land
at the same millisecond.

`ApiFlowIT` (in `src/test/...`) runs exactly this story over HTTP. It is a good place to start and to come back to at the end.

## Reading order

### 1. Money: `money/Money.java`
**Idea: never use `double` for money, and never round silently.**
`0.1 + 0.2` is `0.30000000000000004` in a `double`. `BigDecimal` is exact. `Money` keeps two decimal places
with `RoundingMode.UNNECESSARY`, which means that if any code tries to create $1.005 it throws an error
instead of rounding quietly. Splitting is the one place that divides, and it does its work in whole cents so
it never needs to round. Test: `MoneyTest`.

### 2. Splitting: `split/Share`, `split/Allocation`, `split/SplitCalculator`
**Idea: the largest-remainder rule makes the pieces add up to the whole.**
$100 split three ways is 33.333… each. Round each share and you get $99.99, and a cent disappears. The
calculator:
1. gives everyone the rounded-down amount in cents,
2. counts the leftover cents,
3. hands them out one at a time to whoever lost the biggest fraction (ties go to the lowest id, so the
   result is the same every run).

The rule it guarantees is **sum of allocations = gross**, always. `SplitCalculatorTest` checks this on
5,000 random splits. That kind of test is called a *property test*: you check a rule that must hold for any
input, instead of checking a few examples.

### 3. Recoupment: `recoupment/RecoupmentCalculator.java`
**Idea: pure functions are easy to test.**
`recouped = min(earned, outstanding)` and `payable = earned - recouped`. There is no database and no Spring
here, which is why `RecoupmentCalculatorTest` can use a simple table of inputs and outputs
(`@ParameterizedTest`).

### 4. The ledger model: `ledger/Side`, `AccountCode`, `Account`, `EntryLine`, `JournalEntry`, `Postings`
**Idea: in double-entry, every movement is written twice, and debits must equal credits.**

| Account | What it means | Normal side |
|---|---|---|
| `CASH` | money the business holds | Debit |
| `ROYALTY_PAYABLE` (per collaborator) | what we owe them | Credit |
| `ADVANCE_RECEIVABLE` (per collaborator) | what they still owe us | Debit |

In Ben's example, the $100 statement becomes one journal entry: debit Cash 100, credit Ana-payable 60,
credit Ben-advance 30, credit Ben-payable 10. Debits are 100 and credits are 100, so the entry balances.
`JournalEntry` refuses to be built if it doesn't balance. `Postings` is the only place that decides which
lines each business event produces. Tests: `JournalEntryTest`, `PostingsTest`.

### 5. The schema: `src/main/resources/db/migration/V1__ledger_schema.sql`
**Idea: let the database enforce rules too, so a bug or a hand-typed SQL command can't break them.**
- `CHECK` constraints: amounts must be positive, percentages must be between 0 and 100.
- **Append-only:** triggers reject every `UPDATE` and `DELETE` on the ledger tables. To fix a mistake you post
  a new, opposite entry, the same way accountants do. The history stays intact.
- **Balanced at COMMIT:** a *deferred constraint trigger* waits until the transaction commits, then checks
  that each entry's debits equal its credits. It has to wait because the lines are inserted one at a time,
  so the entry is unbalanced partway through.
- **Balances are never stored.** Each balance is a `SUM` over the ledger lines, so a stored balance can
  never drift out of sync with them.

Test: `LedgerDatabaseRulesIT` goes around the Java code on purpose and writes raw SQL, to prove the database
holds the line on its own.

### 6. Repositories: `ledger/LedgerRepository`, `collaborator/CollaboratorRepository`, `release/ReleaseRepository`
**Idea: plain SQL through `JdbcClient` (no JPA), so every query is visible.**
Look for:
- `balance()`: `SUM(CASE WHEN side = normal side THEN amount ELSE -amount END)`.
- `lockForUpdate()`: `SELECT ... FOR UPDATE` locks the collaborator row until the transaction ends.
- `lockAllForUpdate()`: `ORDER BY id FOR UPDATE`. Always locking rows in the same order means two
  transactions can never each hold one lock while waiting for the other's (a *deadlock*).

### 7. Services, where correctness under concurrency lives
**`statement/StatementImportService`: idempotency.**
- `INSERT ... ON CONFLICT (source, external_ref) DO NOTHING RETURNING id` "claims" the statement.
  - If it returns no row, the statement was already imported.
  - Then `StatementHasher`'s SHA-256 fingerprint decides the outcome: same content returns the original
    result, different content is a 409 Conflict.
- If two identical imports race, Postgres makes the second one wait on the unique index and then
  reports the conflict.

**`payout/PayoutService`: locking plus a second idempotency mechanism.**
1. Lock the collaborator row.
2. Check the `Idempotency-Key`. A replay returns the original payout.
3. Read the balance. The read happens *after* the lock, which matters.
4. Enforce the threshold.
5. Post the payout entry.

Without the lock, two requests could both read "$120 available" and both pay it: this is a *lost update*.
With the lock, the second request waits, then reads $0 and is refused.

**`advance/AdvanceService` and `release/ReleaseService`** follow the same pattern. They are smaller, so they
are good for a quick review.

Tests: `StatementImportIT`, `PayoutIT`. `support/Concurrently` fires N threads at the same instant using a
`CountDownLatch` "starting gun". Try deleting `FOR UPDATE` from `CollaboratorRepository` and run
`PayoutIT` to watch it fail.

### 8. The HTTP layer: `api/*`
**Idea: controllers are thin. They translate HTTP to service calls and exceptions to status codes.**
- `ApiDtos`: request and response records with `jakarta.validation` annotations, checked by `@Valid`.
- `GlobalExceptionHandler`: one place that turns exceptions into RFC 7807 problem responses.

| Exception | Status |
|---|---|
| `IllegalArgumentException` | 400 |
| `NotFoundException` | 404 |
| `ConflictException` | 409 |
| `BusinessRuleException` | 422 |
| `BadCredentialsException` | 401 (a failed sign-in) |

- Status code convention: 201 when something was created, 200 when a replay returned the existing result.
- Rejections decided *before* a controller runs — no token, expired token, wrong role — never reach this
  class, because they happen in the servlet filter chain. `security/ProblemSecurityHandler` writes those in
  the same shape; see §9.

Test: `ApiFlowIT`.

### 9. Security: `security/*`
**Idea: authentication answers "who are you"; authorization answers "may you do this". They are different
questions, and the second one needs more than a role.**

Read in this order:

1. `V2__app_user.sql` — the accounts table. The interesting column is `collaborator_id`: it links an ARTIST
   user to the collaborator they *are*. A `CHECK` makes sure an ARTIST always has one and an ADMIN never
   does, the same trick V1 uses for `('CASH', NULL)`.
2. `TokenService` — mints the JWT. A JWT is three base64 chunks: header, claims, signature. **The claims are
   readable by anyone** — it is signed, not encrypted — so never put a secret in one. The signature is what
   makes a claim trustworthy.
3. `SecurityConfig` — the request path and the rules, in one file. Verifies the signature, maps the `roles`
   claim to Spring authorities, then allows or refuses each URL.
4. `AccessPolicy` — the ownership check, and the most important idea here (below).
5. `ProblemSecurityHandler` — writes 401s and 403s as the same RFC 7807 problem JSON the rest of the API
   uses, because those responses are produced in the filter chain and never reach `GlobalExceptionHandler`.

**Why a role is not enough.** `hasRole("ARTIST")` is true for *every* artist, so a rule written only in
roles would let Ana read Bob's earnings by changing the number in the URL. That bug has a name — broken
object level authorization — and it is the most common serious flaw in REST APIs. The fix is to compare the
`{id}` in the URL against the `collaboratorId` claim in the caller's token, which they cannot forge without
the signing key:

```java
@PreAuthorize("@access.canActForCollaborator(authentication, #id)")
@GetMapping("/{id}/balances")
```

`@PreAuthorize` only works because of `@EnableMethodSecurity` on `SecurityConfig`. Without that annotation
the checks are silently ignored — present in the source, doing nothing. That is why `SecurityAccessIT`
exists as well as `AccessPolicyTest`: the unit test would still pass with `@PreAuthorize` deleted.

| Endpoint | ADMIN | ARTIST |
|---|---|---|
| `POST /api/auth/login` | open to everyone | open to everyone |
| `POST /api/auth/users` | yes | no — else an artist could mint an ADMIN account |
| `POST /api/collaborators`, `/releases`, `/statements`, `/{id}/advances` | yes | no |
| `GET /api/ledger/trial-balance` | yes | no — it is everyone's money summed |
| `GET /api/collaborators/{id}/balances` | any id | **their own id only** |
| `POST /api/collaborators/{id}/payouts` | any id | **their own id only** |
| anything else | denied | denied |

Three habits worth copying from this package:

- **401 vs 403.** 401 means "I do not know who you are" (missing, expired or badly signed token); 403 means
  "I know, and you may not". The difference tells a client whether signing in again would help.
- **Fail closed.** The last rule is `anyRequest().denyAll()`, not `authenticated()`. A new endpoint is locked
  until someone writes a rule for it on purpose, so forgetting to think about access is safe.
- **Say as little as possible when refusing.** A wrong password and an unknown username produce byte-for-byte
  identical 401s, and `AuthService` deliberately runs BCrypt either way (`DUMMY_HASH`) so they take the same
  time too. Otherwise the response — or just the delay — reveals which usernames exist.

Tests: `AccessPolicyTest` and `TokenServiceTest` (pure logic and crypto), `SecurityAccessIT` (the access
matrix over real HTTP), `AuthIT` (sign-in, hashing, account rules), `AdminBootstrapIT` (first-run seeding).
`support/TestTokens` forges the tokens a real client never sends — expired, foreign-signed, wrong issuer —
because `TokenService` can only produce valid ones, and proving a door is locked needs a key that should not
fit.

### 10. Test support: `src/test/.../support/AbstractPostgresIT`
**Idea: integration tests need a real database, and each test starts from an empty one.**
- Testcontainers starts Postgres in Docker, or the tests use `TEST_DATABASE_URL` if you set it.
- Each test begins with `TRUNCATE` and ends by checking that the trial balance is balanced. That makes
  "the books balance" an invariant checked after every single test, not just in one test.
- `adminToken()` / `artistToken(id)` mint **real** signed tokens through the real `AuthService`, rather than
  faking a login with `@WithMockUser`. Two reasons: the signing and verification code gets covered by the
  same tests, and only a real token carries the `collaboratorId` claim that `AccessPolicy` reads — with a
  mocked principal the ownership rules would never actually be exercised.

## Concepts cheat-sheet

| Term | One-line meaning | Where |
|---|---|---|
| Idempotent | Doing it twice has the same effect as doing it once | StatementImportService, PayoutService |
| Pessimistic lock | Lock first, then read and write; others wait | CollaboratorRepository |
| Lost update | Two transactions read the same value and both act on it | PayoutIT, StatementImportIT |
| Deadlock | Two transactions each waiting on the other's lock | lockAllForUpdate (ORDER BY) |
| READ COMMITTED | Postgres default: each statement sees data committed before it began | why we read *after* locking |
| Deferred constraint | A check that runs at COMMIT instead of per row | V1 schema, journal_line_balanced |
| Invariant | A rule that must always be true (debits = credits, parts = whole) | tests |
| Property test | Check an invariant on many random inputs | SplitCalculatorTest |
| Authentication | Proving who you are | AuthService, TokenService |
| Authorization | Deciding what you may do | SecurityConfig, AccessPolicy |
| JWT | Signed, *readable* token carrying claims; trust comes from the signature | TokenService |
| Stateless auth | Server stores no session; every request re-proves itself | SecurityConfig |
| BCrypt | Deliberately slow, salted password hash | SecurityConfig#passwordEncoder |
| Broken object level authorization | Right role, wrong row — the bug `@PreAuthorize` prevents | AccessPolicy |
| User enumeration | Learning which usernames exist from error or timing differences | AuthService#DUMMY_HASH |
| Fail closed | Unknown requests are denied, not allowed | `anyRequest().denyAll()` |
| Coverage gate | Build fails if tested code drops below a threshold | pom.xml, jacoco `check` |

## Exercises

1. Add a `REVERSAL` entry kind that posts the exact opposite of an existing entry. This is the append-only
   way to undo a mistake.
2. Add `GET /api/collaborators/{id}/statement-history` that lists each journal line with a running balance.
   Hint: `SUM(...) OVER (ORDER BY id)`.
3. Remove `ORDER BY id` from `lockAllForUpdate`, then write a test with two statements that touch the same
   two collaborators in opposite order, and watch Postgres report a deadlock.
4. Delete `@EnableMethodSecurity` from `SecurityConfig` and run the tests. `AccessPolicyTest` still passes;
   `SecurityAccessIT` fails. That is the difference between testing a rule and testing that it is wired up.
5. Add a refresh-token endpoint, then work out what it costs you: you now need server-side state to revoke
   one, which is exactly the trade-off `TokenService` avoids by keeping the TTL short.
6. Add a `VIEWER` role that may read the trial balance but change nothing, and see how much of
   `SecurityConfig` has to change. (Answer: one line — which is the point of keeping the rules in one file.)
