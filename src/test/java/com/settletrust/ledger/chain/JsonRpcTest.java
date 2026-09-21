package com.settletrust.ledger.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the client does with the answers a node is allowed to give.
 *
 * <p>Against a stub server rather than a node, because the cases worth pinning down are the
 * ones a healthy node does not produce on request: a null result, a missing one, an error
 * object. A real chain will not oblige.
 */
class JsonRpcTest {

    private HttpServer server;
    private JsonRpc rpc;
    private final AtomicReference<String> reply = new AtomicReference<>();

    @BeforeEach
    void startStubNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        rpc = new JsonRpc("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStubNode() {
        rpc.close();
        server.stop(0);
    }

    /**
     * The one that was wrong, and the reason it stayed wrong: nothing throws, nothing logs,
     * and an empty list of logs is a perfectly ordinary thing for a quiet range to return.
     * The watcher would have advanced its cursor past blocks it had never looked at.
     */
    @Test
    @DisplayName("a result that is present and null is refused, not read as an empty answer")
    void nullResultIsRefused() {
        reply.set("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");

        JsonRpc.ChainUnavailable refused = assertThrows(
                JsonRpc.ChainUnavailable.class,
                () -> rpc.call("eth_getLogs", Map.of("fromBlock", "0x1")));

        assertTrue(refused.getMessage().contains("eth_getLogs"),
                () -> "the message should name the call that failed: " + refused.getMessage());
    }

    @Test
    @DisplayName("a missing result is refused too, and says something different")
    void absentResultIsRefused() {
        reply.set("{\"jsonrpc\":\"2.0\",\"id\":1}");

        JsonRpc.ChainUnavailable refused = assertThrows(
                JsonRpc.ChainUnavailable.class, () -> rpc.call("eth_blockNumber"));

        assertTrue(refused.getMessage().contains("neither a result nor an error"),
                () -> "a malformed reply and a null answer are different faults: "
                        + refused.getMessage());
    }

    /**
     * The exception that proves the rule. Asking for a block that does not exist is a
     * question with a real answer, and null is how the node gives it.
     */
    @Test
    @DisplayName("a null result is handed back when the caller asked for one")
    void nullResultIsAnAnswerWhereItMeansSomething() {
        reply.set("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");

        JsonNode block = rpc.callAllowingNull("eth_getBlockByNumber", "0xff", false);

        assertTrue(block.isNull(), () -> "expected a null node, got " + block);
    }

    @Test
    @DisplayName("an error object is refused whatever the result says")
    void errorIsRefused() {
        reply.set("{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"error\":{\"code\":-32000,\"message\":\"query returned more than 10000 results\"}}");

        JsonRpc.ChainUnavailable refused = assertThrows(
                JsonRpc.ChainUnavailable.class,
                () -> rpc.call("eth_getLogs", List.of()));

        assertTrue(refused.getMessage().contains("10000"),
                () -> "the node's own words are the useful part: " + refused.getMessage());
    }

    @Test
    @DisplayName("an ordinary result comes back unwrapped")
    void ordinaryResultIsReturned() {
        reply.set("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x10\"}");

        assertEquals("0x10", rpc.call("eth_blockNumber").asText());
    }
}
