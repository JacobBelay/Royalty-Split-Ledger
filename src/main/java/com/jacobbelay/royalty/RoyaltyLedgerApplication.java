package com.jacobbelay.royalty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. {@code @SpringBootApplication} scans this package and everything under it for
 * {@code @Service}, {@code @Repository} and {@code @RestController} classes and wires them together.
 *
 * <p>Layers, outermost first (each only calls the one below it):
 * <pre>
 *   api/          HTTP in and out: JSON DTOs, validation, status codes
 *   *Service      one transaction per business action; takes row locks; orchestrates the pure math
 *   split/, recoupment/, ledger/Postings   pure rules with no I/O (the unit-tested core)
 *   *Repository   SQL via JdbcClient
 *   Postgres      constraints + triggers as the last line of defence (db/migration/V1__ledger_schema.sql)
 * </pre>
 * Start reading at docs/LEARNING_GUIDE.md.
 */
@SpringBootApplication
public class RoyaltyLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoyaltyLedgerApplication.class, args);
    }
}
