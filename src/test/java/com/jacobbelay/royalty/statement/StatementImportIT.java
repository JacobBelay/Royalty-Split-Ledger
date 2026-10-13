package com.jacobbelay.royalty.statement;

import com.jacobbelay.royalty.advance.AdvanceService;
import com.jacobbelay.royalty.common.ConflictException;
import com.jacobbelay.royalty.common.NotFoundException;
import com.jacobbelay.royalty.ledger.Postings;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.split.Share;
import com.jacobbelay.royalty.support.AbstractPostgresIT;
import com.jacobbelay.royalty.support.Concurrently;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end behaviour of {@link StatementImportService} against Postgres: split + recoupment + posting,
 * idempotency (including under concurrency), and the locking that keeps recoupment correct.
 */
class StatementImportIT extends AbstractPostgresIT {

    @Autowired StatementImportService imports;
    @Autowired AdvanceService advances;

    private StatementImportCommand statement(String ref, long releaseId, String amount) {
        return new StatementImportCommand("DistroKid", ref, List.of(new StatementLine(releaseId, Money.of(amount))));
    }

    @Test
    void splitsAStatementIntoPayableBalancesWithEveryCentAccountedFor() {
        long a = collaborator("A", "50.00").id();
        long b = collaborator("B", "50.00").id();
        long c = collaborator("C", "50.00").id();
        long song = release("Song", Share.of(a, "33.3333"), Share.of(b, "33.3333"), Share.of(c, "33.3334"));

        StatementImportResult result = imports.importStatement(statement("2026-08", song, "100.00"));

        assertThat(result.duplicate()).isFalse();
        assertThat(payable(a)).isEqualTo(Money.of("33.33"));
        assertThat(payable(b)).isEqualTo(Money.of("33.33"));
        assertThat(payable(c)).isEqualTo(Money.of("33.34"));   // the leftover cent, by the remainder rule
        assertThat(cash()).isEqualTo(Money.of("100.00"));
    }

    @Test
    void combinesEarningsAcrossMultipleReleasesPerCollaborator() {
        long a = collaborator("A", "50.00").id();
        long b = collaborator("B", "50.00").id();
        long single = release("Single", Share.of(a, "100"));
        long duet = release("Duet", Share.of(a, "50"), Share.of(b, "50"));

        StatementImportResult result = imports.importStatement(new StatementImportCommand("DistroKid", "2026-08", List.of(
                new StatementLine(single, Money.of("10.00")),
                new StatementLine(duet, Money.of("20.00")))));

        assertThat(result.earnings()).containsExactly(
                new Postings.Earning(a, Money.ZERO, Money.of("20.00")),
                new Postings.Earning(b, Money.ZERO, Money.of("10.00")));
    }

    @Test
    void recoupsAdvancesBeforeAnythingBecomesPayable() {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));
        advances.issue(a, Money.of("300.00"));

        imports.importStatement(statement("2026-07", song, "200.00"));   // all 200 recoups
        assertThat(advanceOutstanding(a)).isEqualTo(Money.of("100.00"));
        assertThat(payable(a)).isEqualTo(Money.ZERO);

        imports.importStatement(statement("2026-08", song, "250.00"));   // 100 finishes recoupment, 150 payable
        assertThat(advanceOutstanding(a)).isEqualTo(Money.ZERO);
        assertThat(payable(a)).isEqualTo(Money.of("150.00"));
    }

    @Test
    void reImportingTheSameStatementIsANoOpThatReturnsTheOriginalResult() {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));

        StatementImportResult first = imports.importStatement(statement("2026-08", song, "80.00"));
        StatementImportResult again = imports.importStatement(statement("2026-08", song, "80.00"));

        assertThat(again.duplicate()).isTrue();
        assertThat(again.statementImportId()).isEqualTo(first.statementImportId());
        assertThat(again.earnings()).isEqualTo(first.earnings());
        assertThat(payable(a)).isEqualTo(Money.of("80.00"));   // not 160
    }

    @Test
    void sameReferenceWithDifferentNumbersIsAConflict() {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));
        imports.importStatement(statement("2026-08", song, "80.00"));

        assertThatThrownBy(() -> imports.importStatement(statement("2026-08", song, "81.00")))
                .isInstanceOf(ConflictException.class);
        assertThat(payable(a)).isEqualTo(Money.of("80.00"));
    }

    @Test
    void unknownReleaseRollsBackSoTheReferenceCanBeRetried() {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));

        assertThatThrownBy(() -> imports.importStatement(new StatementImportCommand("DistroKid", "2026-08", List.of(
                new StatementLine(song, Money.of("10.00")), new StatementLine(999, Money.of("5.00"))))))
                .isInstanceOf(NotFoundException.class);

        // The failed attempt's statement_import row was rolled back, so a corrected import under the same ref works.
        assertThat(imports.importStatement(statement("2026-08", song, "10.00")).duplicate()).isFalse();
    }

    @Test
    void rejectsDuplicateReleaseLinesAndNegativeAmounts() {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));
        assertThatThrownBy(() -> imports.importStatement(new StatementImportCommand("DK", "x", List.of(
                new StatementLine(song, Money.of("1.00")), new StatementLine(song, Money.of("2.00"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imports.importStatement(statement("y", song, "-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroStatementIsRecordedButPostsNothing() {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));
        StatementImportResult r = imports.importStatement(statement("2026-08", song, "0.00"));
        assertThat(r.gross()).isEqualTo(Money.ZERO);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_entry").query(Long.class).single()).isZero();
    }

    /**
     * Two identical imports at the same instant (e.g. a client retrying while the first is still running).
     * The UNIQUE key + ON CONFLICT makes one of them wait and then report "duplicate". Cash counted once.
     */
    @Test
    void concurrentIdenticalImportsPostExactlyOnce() throws Exception {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));

        List<Concurrently.Outcome<StatementImportResult>> outcomes =
                Concurrently.run(8, i -> imports.importStatement(statement("2026-08", song, "80.00")));

        assertThat(outcomes).allMatch(Concurrently.Outcome::succeeded);
        assertThat(outcomes.stream().filter(o -> !o.value().duplicate()).count()).isEqualTo(1);
        assertThat(cash()).isEqualTo(Money.of("80.00"));
        assertThat(payable(a)).isEqualTo(Money.of("80.00"));
    }

    /**
     * The lost-update race the collaborator row lock prevents. Advance 100; two DIFFERENT statements of 80 each
     * arrive together. Without the lock both read "owes 100", both recoup 80: 160 recouped against a 100
     * advance. With it, the second waits, sees "owes 20", recoups 20 and pays out 60.
     */
    @Test
    void concurrentImportsNeverRecoupMoreThanTheAdvance() throws Exception {
        long a = collaborator("A", "50.00").id();
        long song = release("Song", Share.of(a, "100"));
        advances.issue(a, Money.of("100.00"));

        Concurrently.run(2, i -> imports.importStatement(statement("2026-0" + (i + 1), song, "80.00")));

        assertThat(advanceOutstanding(a)).isEqualTo(Money.ZERO);
        assertThat(payable(a)).isEqualTo(Money.of("60.00"));
    }
}
