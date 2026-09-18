package com.settletrust.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settletrust.ledger.TestDatabases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract, against the real application context and a real database.
 *
 * <p>These tests deliberately do not re-prove the ledger's rules, which are covered far
 * faster below the edge. What they check is the translation: that a replay is a 200 and a
 * new transfer a 201, that a refusal carries a reason a client can branch on, and that
 * each refusal lands on the status code it deserves.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiTest {

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

    private String house;
    private String alice;
    private String bob;

    @BeforeEach
    void openAccounts() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        house = "house-" + run;
        alice = "alice-" + run;
        bob = "bob-" + run;

        openAccount(house, "EUR", "HOUSE");
        openAccount(alice, "EUR", "CUSTOMER");
        openAccount(bob, "EUR", "CUSTOMER");
        transfer(house, alice, 10_000L, "EUR", "seed-" + run).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a new transfer is 201 and moves the balances")
    void aNewTransferIsCreated() throws Exception {
        transfer(alice, bob, 2_500L, "EUR", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountMinor", is(2500)))
                .andExpect(jsonPath("$.currency", is("EUR")))
                .andExpect(jsonPath("$.replayed", is(false)));

        mvc.perform(get("/api/v1/accounts/{id}", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor", is(7500)));
        mvc.perform(get("/api/v1/accounts/{id}", bob))
                .andExpect(jsonPath("$.balanceMinor", is(2500)));
    }

    @Test
    @DisplayName("the same key again is 200, the same transfer, and no second movement")
    void aReplayIsOkRatherThanCreated() throws Exception {
        String key = key();

        MvcResult first = transfer(alice, bob, 2_500L, "EUR", key)
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult replay = transfer(alice, bob, 2_500L, "EUR", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)))
                .andReturn();

        assertEquals(idOf(first), idOf(replay), "a retry is the same transfer");

        mvc.perform(get("/api/v1/accounts/{id}", bob))
                .andExpect(jsonPath("$.balanceMinor", is(2500)));
    }

    @Test
    @DisplayName("a missing idempotency key is refused outright")
    void theKeyIsMandatory() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "from", alice, "to", bob,
                                "amountMinor", 100L, "currency", "EUR"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("each refusal lands on the status code it deserves")
    void refusalsMapToStatusCodes() throws Exception {
        transfer(alice, bob, 10_001L, "EUR", key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason", is("INSUFFICIENT_FUNDS")));

        transfer("nobody-at-all", bob, 100L, "EUR", key())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason", is("UNKNOWN_ACCOUNT")));

        transfer(alice, alice, 100L, "EUR", key())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", is("SAME_ACCOUNT")));

        transfer(alice, bob, 0L, "EUR", key())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", is("AMOUNT_NOT_POSITIVE")));
    }

    @Test
    @DisplayName("a currency mismatch is refused, not silently converted")
    void aCurrencyMismatchIsRefused() throws Exception {
        String usd = "carol-" + UUID.randomUUID().toString().substring(0, 8);
        openAccount(usd, "USD", "CUSTOMER");

        transfer(alice, usd, 100L, "EUR", key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason", is("CURRENCY_MISMATCH")));
    }

    @Test
    @DisplayName("an invalid body says which field, and reopening an account is a conflict")
    void badRequestsAreExplained() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("id", "x", "currency", "EU"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", is("INVALID_REQUEST")));

        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("id", alice, "currency", "EUR", "kind", "CUSTOMER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason", is("CONFLICT")));
    }

    @Test
    @DisplayName("an account's entries are readable and every transfer left two")
    void entriesAreReadable() throws Exception {
        transfer(alice, bob, 100L, "EUR", key()).andExpect(status().isCreated());
        transfer(alice, bob, 200L, "EUR", key()).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/accounts/{id}/entries", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].amountMinor", is(100)))
                .andExpect(jsonPath("$[1].amountMinor", is(200)));
    }

    private void openAccount(String id, String currency, String kind) throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("id", id, "currency", currency, "kind", kind))))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions transfer(
            String from, String to, long amountMinor, String currency, String key)
            throws Exception {
        return mvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "from", from,
                        "to", to,
                        "amountMinor", amountMinor,
                        "currency", currency))));
    }

    private String idOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString())
                .get("transferId")
                .asText();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
