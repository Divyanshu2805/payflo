package com.project.payflo.payment_service.statemachine;

import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    private final PaymentStateMachine machine = new PaymentStateMachine();

    @Test
    void theHappyPathFromAuthorizationToPayout() {
        PaymentStatus status = PaymentStatus.CREATED;
        status = machine.transition(status, PaymentEvent.AUTHORIZE_ATTEMPT);
        assertThat(status).isEqualTo(PaymentStatus.AUTHORIZING);
        status = machine.transition(status, PaymentEvent.AUTHORIZE_SUCCESS);
        assertThat(status).isEqualTo(PaymentStatus.AUTHORIZED);
        status = machine.transition(status, PaymentEvent.CAPTURE_REQUEST);
        assertThat(status).isEqualTo(PaymentStatus.CAPTURING);
        status = machine.transition(status, PaymentEvent.CAPTURE_SUCCESS);
        assertThat(status).isEqualTo(PaymentStatus.CAPTURED);
        status = machine.transition(status, PaymentEvent.SETTLE);
        assertThat(status).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void severalPartialRefundsInARowStayPartlyRefunded() {
        PaymentStatus status = machine.transition(PaymentStatus.CAPTURED, PaymentEvent.REFUND_INIT);
        assertThat(status).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        assertThat(machine.transition(status, PaymentEvent.REFUND_INIT)).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void completingTheRefundsMakesThePaymentRefunded() {
        assertThat(machine.transition(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_COMPLETE))
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void aFailedRefundWithNothingElseRefundedGoesBackToCaptured() {
        assertThat(machine.transition(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.REFUND_FAIL))
                .isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void aPartlyRefundedPaymentCanStillBePaidOut() {
        assertThat(machine.transition(PaymentStatus.PARTIALLY_REFUNDED, PaymentEvent.SETTLE))
                .isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void thingsThatMustNotHappenAreRefused() {
        assertThatThrownBy(() -> machine.transition(PaymentStatus.FAILED, PaymentEvent.REFUND_INIT))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> machine.transition(PaymentStatus.REFUNDED, PaymentEvent.SETTLE))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> machine.transition(PaymentStatus.CAPTURED, PaymentEvent.REFUND_FAIL))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> machine.transition(PaymentStatus.AUTHORIZING, PaymentEvent.SETTLE))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
