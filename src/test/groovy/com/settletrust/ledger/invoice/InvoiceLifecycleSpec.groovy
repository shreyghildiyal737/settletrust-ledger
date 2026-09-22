package com.settletrust.ledger.invoice

import spock.lang.Specification

import static com.settletrust.ledger.invoice.InvoiceTransitionRejected.Reason.ILLEGAL_TRANSITION
import static com.settletrust.ledger.invoice.InvoiceTransitionRejected.Reason.TERMINAL_STATE

/**
 * The lifecycle, where the subject is genuinely a table.
 *
 * <p>{@code InvoiceStateMachineTest} keeps the cases that are one scenario each. What
 * lives here is the part that was being expressed as a list of near-identical methods:
 * which refusals carry which reason, and what an invoice is waiting for at each status.
 * A {@code where:} block says that in the shape the thing actually has, and the two
 * properties at the top are ones no amount of per-case testing would have caught.
 */
class InvoiceLifecycleSpec extends Specification {

    def "a status is final exactly when it has nowhere left to go"() {
        expect: "the flag and the table agree, in both directions"
        status.terminal == InvoiceTransitions.allowedFrom(status).isEmpty()

        where:
        status << InvoiceStatus.values()
    }

    def "a status is settleable exactly when settling is a move it may make"() {
        expect: "the flag published to clients and the table agree, in both directions"
        InvoiceRules.settleableNow(status) ==
                InvoiceTransitions.isAllowed(status, InvoiceStatus.SETTLED)

        and: "readiness is the wider question, so it can never be the narrower one's opposite"
        !(InvoiceRules.settleableNow(status) && !InvoiceRules.readyForSettlement(status))

        where:
        status << InvoiceStatus.values()
    }

    def "every status can be reached from a draft"() {
        given: "a walk of the table from the only state an invoice starts in"
        Set<InvoiceStatus> reached = [InvoiceStatus.DRAFT] as Set
        Queue<InvoiceStatus> toVisit = new LinkedList<>([InvoiceStatus.DRAFT])

        when:
        while (!toVisit.isEmpty()) {
            InvoiceTransitions.allowedFrom(toVisit.poll()).each { next ->
                if (reached.add(next)) {
                    toVisit.add(next)
                }
            }
        }

        then: "nothing is stranded. A status nobody can get to is dead code with a name"
        reached == InvoiceStatus.values() as Set
    }

    def "#from cannot become #to, and says why"() {
        when:
        InvoiceRules.requireLegal("inv-1", from, to)

        then:
        InvoiceTransitionRejected rejected = thrown()
        rejected.reason() == reason

        where:
        from                              | to                                || reason
        InvoiceStatus.DRAFT               | InvoiceStatus.SETTLED             || ILLEGAL_TRANSITION
        InvoiceStatus.DRAFT               | InvoiceStatus.ESCROW_FUNDED       || ILLEGAL_TRANSITION
        InvoiceStatus.SUBMITTED           | InvoiceStatus.DELIVERY_CONFIRMED  || ILLEGAL_TRANSITION
        InvoiceStatus.ESCROW_PENDING      | InvoiceStatus.SETTLED             || ILLEGAL_TRANSITION
        InvoiceStatus.DELIVERY_CONFIRMED  | InvoiceStatus.SETTLED             || ILLEGAL_TRANSITION
        InvoiceStatus.SETTLED             | InvoiceStatus.DISPUTED            || TERMINAL_STATE
        InvoiceStatus.CANCELLED           | InvoiceStatus.SUBMITTED           || TERMINAL_STATE
        // Standing still is refused before finality is even considered, so a settled
        // invoice asked to settle again is told it is already there rather than that it
        // is finished. Pinned because the order of those two checks is a real choice.
        InvoiceStatus.ESCROW_FUNDED       | InvoiceStatus.ESCROW_FUNDED       || ILLEGAL_TRANSITION
        InvoiceStatus.SETTLED             | InvoiceStatus.SETTLED             || ILLEGAL_TRANSITION
    }

    def "a #status invoice is waiting on #waitingFor"() {
        expect:
        InvoiceRules.blockedReasons(status) == waitingFor
        InvoiceRules.readyForSettlement(status) == waitingFor.isEmpty()

        where:
        status                           || waitingFor
        InvoiceStatus.DRAFT              || [ESCROW, DELIVERY]
        InvoiceStatus.BUYER_ACCEPTED     || [ESCROW, DELIVERY]
        InvoiceStatus.ESCROW_PENDING     || [ESCROW, DELIVERY]
        InvoiceStatus.ESCROW_FUNDED      || [DELIVERY]
        InvoiceStatus.SHIPMENT_PENDING   || [DELIVERY]
        InvoiceStatus.DELIVERY_CONFIRMED || []
        InvoiceStatus.SETTLEMENT_PENDING || []
        // The three stopped states say what stopped them first, because that is the line
        // a person needs to read, and the rest follows underneath it.
        InvoiceStatus.FROZEN             || [FROZEN, ESCROW, DELIVERY]
        InvoiceStatus.DISPUTED           || [DISPUTED, ESCROW, DELIVERY]
        InvoiceStatus.EXPIRED            || [EXPIRED, ESCROW, DELIVERY]
        // A settled invoice reads oddly here and correctly: this answers "may it be
        // settled now", and the answer for one already settled is no.
        InvoiceStatus.SETTLED            || [ESCROW, DELIVERY]
    }

    private static final String ESCROW = "Escrow is not funded."
    private static final String DELIVERY = "Delivery has not been confirmed."
    private static final String FROZEN = "Invoice is frozen by compliance."
    private static final String DISPUTED = "Invoice has an open dispute."
    private static final String EXPIRED = "Invoice has expired."
}
