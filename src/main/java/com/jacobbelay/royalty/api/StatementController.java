package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.api.ApiDtos.ImportStatementRequest;
import com.jacobbelay.royalty.api.ApiDtos.StatementImportResponse;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.statement.StatementImportCommand;
import com.jacobbelay.royalty.statement.StatementImportResult;
import com.jacobbelay.royalty.statement.StatementImportService;
import com.jacobbelay.royalty.statement.StatementLine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/statements: import a distributor statement. Idempotent on (source, externalRef):
 * 201 the first time, 200 with the same body on an identical re-send, 409 if the content changed.
 * The whole pipeline is explained in {@link StatementImportService}.
 */
@RestController
@RequestMapping("/api/statements")
public class StatementController {

    private final StatementImportService imports;

    public StatementController(StatementImportService imports) {
        this.imports = imports;
    }

    @PostMapping
    public ResponseEntity<StatementImportResponse> importStatement(@Valid @RequestBody ImportStatementRequest request) {
        StatementImportCommand command = new StatementImportCommand(
                request.source(),
                request.externalRef(),
                request.lines().stream().map(l -> new StatementLine(l.releaseId(), new Money(l.amount()))).toList());
        StatementImportResult result = imports.importStatement(command);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(StatementImportResponse.from(result));
    }
}
