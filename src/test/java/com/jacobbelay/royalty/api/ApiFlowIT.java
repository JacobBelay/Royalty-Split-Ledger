package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.support.AbstractPostgresIT;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the HTTP API the way a client would, via {@link MockMvc} (Spring's in-process HTTP simulator: real
 * controllers, validation and error handling, no network). Checks the status codes and JSON the controllers
 * promise, while the service-level ITs cover the money logic in depth.
 */
class ApiFlowIT extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    private ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long idFrom(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void fullLifecycleFromReleaseToPayout() throws Exception {
        long ana = idFrom(postJson("/api/collaborators", "{\"name\":\"Ana\",\"payoutThreshold\":25.00}").andExpect(status().isCreated()));
        long ben = idFrom(postJson("/api/collaborators", "{\"name\":\"Ben\"}").andExpect(status().isCreated()));

        long song = idFrom(postJson("/api/releases", """
                {"title":"Night Drive","shares":[
                  {"collaboratorId":%d,"percent":60},
                  {"collaboratorId":%d,"percent":40}]}""".formatted(ana, ben))
                .andExpect(status().isCreated()));

        postJson("/api/collaborators/" + ben + "/advances", "{\"amount\":30.00}").andExpect(status().isCreated());

        String statement = """
                {"source":"DistroKid","externalRef":"2026-08","lines":[{"releaseId":%d,"amount":100.00}]}""".formatted(song);
        postJson("/api/statements", statement)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.gross").value(100.00))
                .andExpect(jsonPath("$.earnings[0].payable").value(60.00))    // Ana: 60% of 100
                .andExpect(jsonPath("$.earnings[1].recouped").value(30.00))   // Ben: 40, of which 30 repays his advance
                .andExpect(jsonPath("$.earnings[1].payable").value(10.00));

        postJson("/api/statements", statement)                                // same statement again
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        mvc.perform(get("/api/collaborators/" + ana + "/balances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payable").value(60.00));

        mvc.perform(post("/api/collaborators/" + ana + "/payouts").header("Idempotency-Key", "pay-ana-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(60.00));
        mvc.perform(post("/api/collaborators/" + ana + "/payouts").header("Idempotency-Key", "pay-ana-1"))
                .andExpect(status().isOk())                                    // replay, not a second payment
                .andExpect(jsonPath("$.duplicate").value(true));

        mvc.perform(post("/api/collaborators/" + ben + "/payouts").header("Idempotency-Key", "pay-ben-1"))
                .andExpect(status().isUnprocessableEntity());                  // Ben has 10.00, threshold 50.00

        mvc.perform(get("/api/ledger/trial-balance"))
                .andExpect(jsonPath("$.balanced").value(true));
    }

    @Test
    void badInputComesBackAsProblemDetails() throws Exception {
        long ana = idFrom(postJson("/api/collaborators", "{\"name\":\"Ana\"}"));

        postJson("/api/releases", """
                {"title":"Bad split","shares":[{"collaboratorId":%d,"percent":90}]}""".formatted(ana))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("exactly 100%")));

        postJson("/api/collaborators", "{\"name\":\"\"}").andExpect(status().isBadRequest());                         // @NotBlank
        postJson("/api/collaborators/" + ana + "/advances", "{\"amount\":1.005}").andExpect(status().isBadRequest());  // 3 decimals
        mvc.perform(post("/api/collaborators/" + ana + "/payouts")).andExpect(status().isBadRequest());               // no Idempotency-Key
        mvc.perform(get("/api/collaborators/999/balances")).andExpect(status().isNotFound());
    }

    @Test
    void changedStatementUnderTheSameReferenceIsAConflict() throws Exception {
        long ana = idFrom(postJson("/api/collaborators", "{\"name\":\"Ana\"}"));
        long song = idFrom(postJson("/api/releases", """
                {"title":"Solo","shares":[{"collaboratorId":%d,"percent":100}]}""".formatted(ana)));

        postJson("/api/statements", """
                {"source":"DK","externalRef":"2026-08","lines":[{"releaseId":%d,"amount":10.00}]}""".formatted(song))
                .andExpect(status().isCreated());
        postJson("/api/statements", """
                {"source":"DK","externalRef":"2026-08","lines":[{"releaseId":%d,"amount":11.00}]}""".formatted(song))
                .andExpect(status().isConflict());
    }
}
