package com.settletrust.ledger.invoice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle, with no database in sight. These run in microseconds, which is the point
 * of keeping the rules in a class that reads nothing and writes nothing.
 */
class InvoiceStateMachineTest {

    @Nested
    @DisplayName("the table itself")
    class TheTable {

        @Test
        void hasTheTwentyStatesTheFrontendDefines() {
            assertEquals(20, InvoiceStatus.values().length);
        }

        @ParameterizedTest
        @EnumSource(InvoiceStatus.class)
        void everyStatusKnowsWhereItCanGo(InvoiceStatus status) {
            // A status added to the enum and forgotten in the table would otherwise fail
            // at runtime, on whichever invoice happened to reach it first.
            assertFalse(InvoiceTransitions.allowedFrom(status) == null);
        }

        @ParameterizedTest
        @EnumSource(InvoiceStatus.class)
        void noStatusCanTransitionToItself(InvoiceStatus status) {
            assertFalse(InvoiceTransitions.allowedFrom(status).contains(status));
        }

        @Test
        void onlyTwoStatesAreFinal() {
            Set<InvoiceStatus> terminal = Arrays.stream(InvoiceStatus.values())
                    .filter(InvoiceStatus::isTerminal)
                    .collect(Collectors.toSet());

            assertEquals(Set.of(InvoiceStatus.SETTLED, InvoiceStatus.CANCELLED), terminal,
                    "money settled, or the invoice abandoned; everything else can still move");
        }

        @ParameterizedTest
        @EnumSource(InvoiceStatus.class)
        void everyCodeRoundTrips(InvoiceStatus status) {
            assertEquals(status, InvoiceStatus.fromCode(status.code()));
        }

        @Test
        void anUnknownCodeIsRejectedRatherThanGuessed() {
            assertThrows(IllegalArgumentException.class, () -> InvoiceStatus.fromCode("paid"));
            assertThrows(IllegalArgumentException.class, () -> InvoiceStatus.fromCode(null));
        }
    }

    @Nested
    @DisplayName("walking the invoice")
    class Walking {

        @Test
        void theHappyPathIsReachableStepByStep() {
            List<InvoiceStatus> path = List.of(
                    InvoiceStatus.DRAFT,
                    InvoiceStatus.SUBMITTED,
                    InvoiceStatus.BUYER_ACCEPTED,
                    InvoiceStatus.RISK_REVIEW_PENDING,
                    InvoiceStatus.RISK_REVIEW_COMPLETED,
                    InvoiceStatus.ESCROW_PENDING,
                    InvoiceStatus.ESCROW_FUNDED,
                    InvoiceStatus.SHIPMENT_PENDING,
                    InvoiceStatus.DELIVERY_CONFIRMED,
                    InvoiceStatus.SETTLEMENT_PENDING,
                    InvoiceStatus.SETTLED);

            List<String> illegal = new ArrayList<>();
            for (int i = 0; i < path.size() - 1; i++) {
                if (!InvoiceTransitions.isAllowed(path.get(i), path.get(i + 1))) {
                    illegal.add(path.get(i).code() + " -> " + path.get(i + 1).code());
                }
            }

            assertTrue(illegal.isEmpty(), "these steps are not allowed: " + illegal);
        }

        @Test
        void aDraftCannotJumpStraightToSettled() {
            InvoiceTransitionRejected rejection = assertThrows(
                    InvoiceTransitionRejected.class,
                    () -> InvoiceRules.requireLegal(
                            "inv-1", InvoiceStatus.DRAFT, InvoiceStatus.SETTLED));

            assertEquals(InvoiceTransitionRejected.Reason.ILLEGAL_TRANSITION, rejection.reason());
        }

        @Test
        void aSettledInvoiceIsFinished() {
            InvoiceTransitionRejected rejection = assertThrows(
                    InvoiceTransitionRejected.class,
                    () -> InvoiceRules.requireLegal(
                            "inv-1", InvoiceStatus.SETTLED, InvoiceStatus.DISPUTED));

            assertEquals(InvoiceTransitionRejected.Reason.TERMINAL_STATE, rejection.reason());
        }

        @Test
        void movingToWhereItAlreadyIsIsRefused() {
            InvoiceTransitionRejected rejection = assertThrows(
                    InvoiceTransitionRejected.class,
                    () -> InvoiceRules.requireLegal(
                            "inv-1", InvoiceStatus.ESCROW_FUNDED, InvoiceStatus.ESCROW_FUNDED));

            assertEquals(InvoiceTransitionRejected.Reason.ILLEGAL_TRANSITION, rejection.reason());
        }

        @Test
        void aDisputeAndAFreezeBothHaveAWayBack() {
            // An invoice that can be stopped but never restarted is a support ticket
            // rather than a state machine.
            assertAll(
                    () -> assertTrue(InvoiceTransitions.isAllowed(
                            InvoiceStatus.DISPUTED, InvoiceStatus.ESCROW_FUNDED)),
                    () -> assertTrue(InvoiceTransitions.isAllowed(
                            InvoiceStatus.FROZEN, InvoiceStatus.ESCROW_FUNDED)),
                    () -> assertTrue(InvoiceTransitions.isAllowed(
                            InvoiceStatus.FAILED, InvoiceStatus.SETTLEMENT_PENDING)));
        }
    }

    @Nested
    @DisplayName("readiness to settle")
    class Readiness {

        @Test
        void onlyDeliveryConfirmedAndSettlementPendingAreReady() {
            Set<InvoiceStatus> ready = Arrays.stream(InvoiceStatus.values())
                    .filter(InvoiceRules::readyForSettlement)
                    .collect(Collectors.toSet());

            assertEquals(
                    Set.of(InvoiceStatus.DELIVERY_CONFIRMED, InvoiceStatus.SETTLEMENT_PENDING),
                    ready);
        }

        @Test
        void aDraftIsBlockedOnBothEscrowAndDelivery() {
            assertEquals(
                    List.of("Escrow is not funded.", "Delivery has not been confirmed."),
                    InvoiceRules.blockedReasons(InvoiceStatus.DRAFT));
        }

        @Test
        void aFundedEscrowStillWaitsOnDelivery() {
            assertEquals(
                    List.of("Delivery has not been confirmed."),
                    InvoiceRules.blockedReasons(InvoiceStatus.ESCROW_FUNDED));
        }

        @Test
        void aFrozenInvoiceSaysSoFirst() {
            List<String> reasons = InvoiceRules.blockedReasons(InvoiceStatus.FROZEN);

            assertAll(
                    () -> assertEquals("Invoice is frozen by compliance.", reasons.get(0)),
                    () -> assertFalse(InvoiceRules.readyForSettlement(InvoiceStatus.FROZEN)));
        }
    }
}
