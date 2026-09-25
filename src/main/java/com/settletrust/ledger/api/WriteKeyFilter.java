package com.settletrust.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import static com.settletrust.ledger.api.LedgerDtos.ErrorView;

/**
 * Lets anyone read the book and only the holder of one key write to it.
 *
 * <p>This exists for the public demo, not as the service's security model. A reviewer
 * should be able to open an account's entries or the open findings without asking
 * anyone, and a stranger should not be able to fill the database or move demo money
 * around underneath them. A real deployment would sit behind the platform's own
 * authentication and never be reachable like this.
 *
 * <p>It is only built when {@code ledger.api.write-key} is set, so a local run and every
 * test behave exactly as they did before it existed.
 */
class WriteKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    /** Shorter than this is guessable, and a guessable key is worse than none because it looks like one. */
    static final int MINIMUM_LENGTH = 32;

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    private final byte[] key;
    private final ObjectMapper json;

    WriteKeyFilter(String key, ObjectMapper json) {
        // Refused at startup rather than accepted and weak: a deployment that believes it
        // is protected by "changeme" is the case this has to make impossible.
        if (key == null || key.strip().length() < MINIMUM_LENGTH) {
            throw new IllegalArgumentException(
                    "ledger.api.write-key must be at least " + MINIMUM_LENGTH + " characters");
        }
        this.key = key.strip().getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (READS.contains(request.getMethod()) || presented(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ErrorView(
                "WRITE_KEY_REQUIRED",
                "Reads are open. Writes need the " + HEADER + " header."));
    }

    private boolean presented(HttpServletRequest request) {
        String offered = request.getHeader(HEADER);
        // Constant time, so the comparison does not tell a caller how many leading
        // characters they got right.
        return offered != null
                && MessageDigest.isEqual(key, offered.strip().getBytes(StandardCharsets.UTF_8));
    }
}
