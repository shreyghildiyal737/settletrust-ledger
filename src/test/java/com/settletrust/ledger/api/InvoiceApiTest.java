package com.settletrust.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settletrust.ledger.TestDatabases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invoice endpoints over HTTP. As with the ledger's API tests, these check the
 * translation rather than re-proving the state machine: which status code each refusal
 * earns, and that a client is told what it may do next without having to know the table.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceApiTest {

    @DynamicPropertySource
    static void pointAtTheTestDatabase(DynamicPropertyRegistry registry) {
        TestDatabases.Target target = TestDatabases.resolve();
        registry.add("spring.datasource.url", target::url);
        registry.add("spring.datasource.username", target::username);
        registry.add("spring.datasource.password", target::password);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private String invoiceId;

    @BeforeEach
    void openAnInvoice() throws Exception {
        invoiceId = "inv-" + UUID.randomUUID().toString().substring(0, 8);

        mvc.perform(post("/api/v1/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "id", invoiceId,
                                "reference", "PO-4471",
                                "sellerId", "seller-acme",
                                "buyerId", "buyer-globex",
                                "amountMinor", 250_000L,
                                "currency", "EUR"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("draft")))
                .andExpect(jsonPath("$.sequence", is(0)));
    }

    @Test
    @DisplayName("an invoice reports where it is, what blocks it and where it may go")
    void anInvoiceReportsItsOptions() throws Exception {
        mvc.perform(get("/api/v1/invoices/{id}", invoiceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("draft")))
                .andExpect(jsonPath("$.readyForSettlement", is(false)))
                .andExpect(jsonPath("$.blockedReasons", contains(
                        "Escrow is not funded.", "Delivery has not been confirmed.")))
                .andExpect(jsonPath("$.nextStates", containsInAnyOrder("submitted", "cancelled")));
    }

    @Test
    @DisplayName("a legal move is recorded and the invoice reports the new state")
    void aLegalMoveIsRecorded() throws Exception {
        transition("submitted", "draft", "sent to buyer")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.from", is("draft")))
                .andExpect(jsonPath("$.to", is("submitted")))
                .andExpect(jsonPath("$.sequence", is(1)));

        mvc.perform(get("/api/v1/invoices/{id}", invoiceId))
                .andExpect(jsonPath("$.status", is("submitted")))
                .andExpect(jsonPath("$.sequence", is(1)));
    }

    @Test
    @DisplayName("an illegal move is 422 and a stale expectation is 409")
    void refusalsMapToStatusCodes() throws Exception {
        transition("delivery_confirmed", null, "wishful")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason", is("ILLEGAL_TRANSITION")));

        // Money states cannot be asserted, only earned through the settlement endpoints.
        transition("settled", null, "trust me")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason", is("MONEY_MOVEMENT_REQUIRED")));

        transition("submitted", "draft", null).andExpect(status().isCreated());

        // A second client still holding the draft it read a moment ago.
        transition("cancelled", "draft", "too late")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason", is("STATE_CHANGED")));
    }

    @Test
    @DisplayName("an unknown invoice is 404 and an unknown status is 400")
    void unknownThingsAreRefusedDistinctly() throws Exception {
        mvc.perform(get("/api/v1/invoices/{id}", "inv-nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason", is("UNKNOWN_INVOICE")));

        transition("paid", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", is("MALFORMED_REQUEST")));
    }

    @Test
    @DisplayName("the whole history is readable, opening transition included")
    void theHistoryIsReadable() throws Exception {
        transition("submitted", "draft", "sent").andExpect(status().isCreated());
        transition("buyer_accepted", "submitted", "signed").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/invoices/{id}/transitions", invoiceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(3)))
                .andExpect(jsonPath("$[0].from").doesNotExist())
                .andExpect(jsonPath("$[0].to", is("draft")))
                .andExpect(jsonPath("$[2].to", is("buyer_accepted")))
                .andExpect(jsonPath("$[2].reason", is("signed")));
    }

    @Test
    @DisplayName("an invoice funded and delivered reports itself ready to settle")
    void anInvoiceCanReachReadiness() throws Exception {
        for (String[] step : new String[][] {
                {"submitted", "draft"},
                {"buyer_accepted", "submitted"},
                {"escrow_pending", "buyer_accepted"}}) {
            transition(step[0], step[1], null).andExpect(status().isCreated());
        }

        openAccount("house-" + invoiceId, "HOUSE");
        openAccount("buyer-" + invoiceId, "CUSTOMER");
        fundAccount("house-" + invoiceId, "buyer-" + invoiceId, 250_000L);

        mvc.perform(post("/api/v1/invoices/{id}/escrow-funding", invoiceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("account", "buyer-" + invoiceId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transition.to", is("escrow_funded")))
                .andExpect(jsonPath("$.amountMinor", is(250000)));

        transition("delivery_confirmed", "escrow_funded", null).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/invoices/{id}", invoiceId))
                .andExpect(jsonPath("$.status", is("delivery_confirmed")))
                .andExpect(jsonPath("$.readyForSettlement", is(true)))
                .andExpect(jsonPath("$.blockedReasons.length()", is(0)));
    }

    private void openAccount(String id, String kind) throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("id", id, "currency", "EUR", "kind", kind))))
                .andExpect(status().isCreated());
    }

    private void fundAccount(String from, String to, long amountMinor) throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "from", from, "to", to,
                                "amountMinor", amountMinor, "currency", "EUR"))))
                .andExpect(status().isCreated());
    }

    private ResultActions transition(String to, String expected, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("to", to);
        if (expected != null) {
            body.put("expected", expected);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return mvc.perform(post("/api/v1/invoices/{id}/transitions", invoiceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }
}
