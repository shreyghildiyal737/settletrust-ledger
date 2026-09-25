package com.settletrust.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settletrust.ledger.TestDatabases;
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

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public deployment's one rule: anyone may read, only the key holder may write.
 *
 * <p>Every other API test runs without a key configured, which is itself the check that
 * an unset key changes nothing.
 */
@SpringBootTest(properties = {
        "ledger.reconciliation.enabled=false",
        "ledger.api.write-key=" + WriteKeyApiTest.KEY
})
@AutoConfigureMockMvc
class WriteKeyApiTest {

    static final String KEY = "test-key-long-enough-to-be-accepted-0123456789";

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

    @Test
    @DisplayName("a write without the key is refused, and says why")
    void aWriteWithoutTheKeyIsRefused() throws Exception {
        String id = "stranger-" + UUID.randomUUID().toString().substring(0, 8);

        openAccount(id, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason", is("WRITE_KEY_REQUIRED")));
        // Refused before it reached the ledger, so there is nothing to read back.
        mvc.perform(get("/api/v1/accounts/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a wrong key is refused the same way as no key")
    void aWrongKeyIsRefused() throws Exception {
        String id = "guesser-" + UUID.randomUUID().toString().substring(0, 8);

        openAccount(id, KEY.substring(1) + "x").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the key holder writes, and anyone reads what was written")
    void theKeyHolderWritesAndAnyoneReads() throws Exception {
        String id = "owner-" + UUID.randomUUID().toString().substring(0, 8);

        openAccount(id, KEY).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/accounts/" + id)).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a key short enough to guess stops the service starting")
    void aShortKeyIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new WriteKeyFilter("changeme", json));
    }

    private ResultActions openAccount(String id, String key) throws Exception {
        var request = post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("id", id, "currency", "EUR", "kind", "CUSTOMER")));
        if (key != null) {
            request.header(WriteKeyFilter.HEADER, key);
        }
        return mvc.perform(request);
    }
}
