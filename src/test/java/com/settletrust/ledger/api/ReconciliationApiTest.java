package com.settletrust.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settletrust.ledger.TestDatabases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reconciliation endpoints.
 *
 * <p>This deliberately does not assert that the books balance. The database is shared
 * with every other test class, several of which leave deliberate wreckage behind, so
 * whether a run comes back clean here says nothing at all. What the edge has to get right
 * is that a run happens, is answered with 201 whatever it found, and can be read back
 * afterwards. Whether the rails actually agree is {@code ReconcilerTest}'s subject, in a
 * schema where the question has an answer.
 */
// The schedule stays off: this test runs the reconciler itself, and a background pass
// would race it for which run is the latest one.
@SpringBootTest(properties = "ledger.reconciliation.enabled=false")
@AutoConfigureMockMvc
class ReconciliationApiTest {

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
    @DisplayName("a run is created, reported with its counts, and readable afterwards")
    void aRunIsRecordedAndReadBack() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/reconciliation/runs"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId", notNullValue()))
                .andExpect(jsonPath("$.agreed", notNullValue()))
                .andExpect(jsonPath("$.observationsChecked", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.transfersChecked", greaterThanOrEqualTo(0)))
                // False here, and it should be: no node is configured, so the edge has to
                // report a run that compared our own records against each other.
                .andExpect(jsonPath("$.reservesChecked", is(false)))
                .andReturn();

        String runId = json.readTree(created.getResponse().getContentAsString())
                .get("runId")
                .asText();

        mvc.perform(get("/api/v1/reconciliation/runs/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(runId)));
    }
}
