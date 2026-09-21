package com.settletrust.ledger.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An Ethereum node, over the only four calls this service makes of one.
 *
 * <p><b>Why there is no chain library here.</b> The watcher reads; it never signs, never
 * sends a transaction and never holds a key. That reduces the whole chain dependency to
 * {@code eth_blockNumber}, {@code eth_getBlockByNumber}, {@code eth_getLogs} and
 * {@code eth_call} over JSON-RPC, which is a stable wire format that has not changed in
 * years. A client library would add a large dependency and a code generator to the
 * deployment image in exchange for four requests, and would hide the one part worth
 * reading: exactly what is asked of the node and exactly how the answer is decoded.
 *
 * <p>The keys stay out for the same reason. A component that can only read cannot be made
 * to move money by a bug, and the settler key lives where the releases are authorised
 * rather than where the chain is followed.
 */
public class JsonRpc implements AutoCloseable {

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final AtomicLong nextId = new AtomicLong(1);

    public JsonRpc(String endpoint) {
        this(endpoint, Duration.ofSeconds(10));
    }

    /**
     * @param timeout applied per request. A node that has stopped answering must surface
     *                as a failure the watcher can log and retry on its next tick, not as a
     *                thread parked for ever on a socket.
     */
    public JsonRpc(String endpoint, Duration timeout) {
        this.endpoint = URI.create(Objects.requireNonNull(endpoint, "endpoint must not be null"));
        this.json = new ObjectMapper();
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        this.requestTimeout = timeout;
    }

    /**
     * Makes one call and returns its {@code result}, refusing a null one.
     *
     * <p>A JSON-RPC error is thrown rather than returned. Every call here asks a question
     * whose answer the caller cannot proceed without, so there is no branch that would
     * usefully inspect an error code; a watcher that carried on with a null would write
     * the absence of a deposit into the ledger as though it were a fact.
     *
     * <p>"Refusing a null one" means both shapes of null, and the distinction is the
     * whole point: an absent {@code result} key is a malformed reply, while a present
     * {@code result} holding JSON {@code null} is well-formed and still says nothing.
     * Jackson hands back a Java {@code null} for the first and a {@code NullNode} for the
     * second, and a {@code NullNode} iterates as an empty list, so a node answering
     * {@code "result": null} to {@code eth_getLogs} reads exactly like a range containing
     * no deposits. The cursor would then advance past blocks nobody has looked at.
     *
     * <p>{@link #callAllowingNull} is for the one method where a null answer is the
     * protocol saying something real.
     */
    public JsonNode call(String method, Object... params) {
        JsonNode result = callAllowingNull(method, params);
        if (result.isNull()) {
            throw new ChainUnavailable(method + " returned a null result");
        }
        return result;
    }

    /**
     * Makes one call and returns its {@code result} even when that result is JSON
     * {@code null}.
     *
     * <p>The methods that want this are the ones whose question has "not yet" or "not
     * here" as a real answer: {@code eth_getBlockByNumber} for a height the node does not
     * have, {@code eth_getTransactionReceipt} for a transaction still pending. In both
     * cases the null is a fact the caller needs rather than a failure to report.
     *
     * <p>Everything else goes through {@link #call}. The split is deliberately opt-in, so
     * a new call site has to say out loud that a null means something there, instead of
     * inheriting a permissiveness nobody chose.
     */
    public JsonNode callAllowingNull(String method, Object... params) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", nextId.getAndIncrement(),
                "method", method,
                "params", List.of(params));

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new ChainUnavailable(method + " could not reach " + endpoint, failure);
        } catch (InterruptedException interrupted) {
            // Restored rather than swallowed: whoever asked for the shutdown is entitled
            // to have the next blocking call notice it too.
            Thread.currentThread().interrupt();
            throw new ChainUnavailable(method + " was interrupted", interrupted);
        }

        if (response.statusCode() != 200) {
            throw new ChainUnavailable(
                    method + " returned HTTP " + response.statusCode() + " from " + endpoint);
        }

        JsonNode parsed;
        try {
            parsed = json.readTree(response.body());
        } catch (IOException malformed) {
            throw new ChainUnavailable(method + " returned something that is not JSON", malformed);
        }

        JsonNode error = parsed.get("error");
        if (error != null && !error.isNull()) {
            throw new ChainUnavailable(method + " was refused by the node: " + error);
        }

        JsonNode result = parsed.get("result");
        if (result == null) {
            throw new ChainUnavailable(method + " returned neither a result nor an error");
        }
        return result;
    }

    @Override
    public void close() {
        http.close();
    }

    /**
     * The node could not be asked, or would not answer.
     *
     * <p>Unchecked, and deliberately not distinguished from a malformed reply: both mean
     * this pass has no information, and the caller's only sane response to either is to
     * log it and try again on the next tick. A watcher that treated "the node is down" as
     * different from "the node said something I cannot parse" would still do the same
     * thing in both branches.
     */
    public static class ChainUnavailable extends RuntimeException {

        public ChainUnavailable(String message) {
            super(message);
        }

        public ChainUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
