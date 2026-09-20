package com.settletrust.ledger.api;

import com.settletrust.ledger.TestDatabases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an alert can be written against.
 *
 * <p>The values are not asserted and cannot be: this shares a database with tests that
 * leave deliberate wreckage, so whether anything is open here means nothing. What has to
 * hold is that the series exist and are named what an alerting rule would name, because a
 * rule referring to a metric that is not published fires never and looks exactly like a
 * rule that is passing.
 */
@SpringBootTest(properties = {
        // Off, so nothing runs in the background while the endpoint is being read. It also
        // makes the point: these values come from the record read at startup, not from a
        // run that happened to fire during the test.
        "ledger.reconciliation.enabled=false"
})
@AutoConfigureMockMvc
class MetricsApiTest {

    @DynamicPropertySource
    static void pointAtTheTestDatabase(DynamicPropertyRegistry registry) {
        TestDatabases.Target target = TestDatabases.resolve();
        registry.add("spring.datasource.url", target::url);
        registry.add("spring.datasource.username", target::username);
        registry.add("spring.datasource.password", target::password);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("the two numbers worth alerting on are published, under the names a rule uses")
    void theAlertableSeriesArePublished() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        // How much is wrong.
                        org.hamcrest.Matchers.containsString(
                                "settletrust_reconciliation_open_findings"),
                        // And whether anyone has looked recently enough for that to mean
                        // anything. Without this one a stopped reconciler reports a
                        // permanently clean book.
                        org.hamcrest.Matchers.containsString(
                                "settletrust_reconciliation_full_run_age_seconds"),
                        org.hamcrest.Matchers.containsString(
                                "settletrust_reconciliation_run_age_seconds"))));
    }

    @Test
    @DisplayName("exposing metrics did not expose anything else")
    void nothingElseCameWithIt() throws Exception {
        // Actuator has endpoints that list beans, dump the environment and shut the
        // service down. Adding one to the exposure list is exactly the change that
        // quietly adds the others, so this asserts the list is still two long.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the probes still answer, and still differ from each other")
    void theProbesAreUnchanged() throws Exception {
        // Liveness deliberately excludes the database and readiness includes it. Both are
        // reachable here, and the split is what stops a Postgres outage restarting every
        // replica in a loop.
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
