package com.settletrust.ledger.api;

/**
 * The client sent something the domain cannot be built from: a blank account id, a
 * currency that is not three letters, a missing idempotency key.
 *
 * <p>This type exists rather than the edge simply catching {@link IllegalArgumentException},
 * which was the first attempt and was wrong. A blanket handler for that catches the
 * framework's own failures too, and the first thing it did was report a server
 * misconfiguration as a 400, blaming the caller for a bug on this side. Only input that
 * this service has actually tried and failed to parse becomes a 400; everything else is
 * allowed to surface as the 500 it is.
 */
class MalformedRequest extends RuntimeException {

    MalformedRequest(String message) {
        super(message);
    }
}
