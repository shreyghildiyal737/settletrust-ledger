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

    @Test
    @DisplayName("the open findings say what they are anchored on")
    void openFindingsCarryTheirAnchor() throws Exception {
        // A deep run first, so there is an anchor. Without one the endpoint answers 404,
        // which is the right answer and not the one under test here.
        MvcResult deep = mvc.perform(post("/api/v1/reconciliation/runs").param("deep", "true"))
                .andExpect(status().isCreated())
                .andReturn();

        String runId = json.readTree(deep.getResponse().getContentAsString())
                .get("runId")
                .asText();

        mvc.perform(get("/api/v1/reconciliation/findings/open"))
                .andExpect(status().isOk())
                // The database is shared with tests that leave deliberate wreckage, so
                // whether anything is open says nothing. That it is anchored on the run
                // just made, and counts what it lists, is the contract.
                .andExpect(jsonPath("$.sinceRun", is(runId)))
                .andExpect(jsonPath("$.sinceRanAt", notNullValue()))
                .andExpect(jsonPath("$.openCount", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.findings", notNullValue()));
    }

    @Test
    @DisplayName("deep=true re-derives the book rather than the window since the last run")
    void aDeepRunCanBeDemanded() throws Exception {
        // One run first, so there is a watermark an ordinary run would have continued from
        // and the flag has something to override.
        mvc.perform(post("/api/v1/reconciliation/runs")).andExpect(status().isCreated());

        mvc.perform(post("/api/v1/reconciliation/runs").param("deep", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode", is("FULL")))
                .andExpect(jsonPath("$.checkedFrom", is(0)));
    }
}
