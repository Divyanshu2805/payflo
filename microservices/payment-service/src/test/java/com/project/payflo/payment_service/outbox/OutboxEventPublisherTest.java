package com.project.payflo.payment_service.outbox;

import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.payment_service.entity.OutboxEvent;
import com.project.payflo.payment_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxEventPublisherTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxEventPublisher publisher = new OutboxEventPublisher(repository);

    private OutboxEvent published(Map<String, Object> payload) {
        publisher.publish(EventAggregateType.PAYMENT, UUID.randomUUID(), "PAYMENT_STATUS_CHANGED", payload);
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void anEnumAndAUuidInThePayloadAreStoredAsTheTextJsonWouldGiveBack() {
        UUID orderId = UUID.randomUUID();

        Map<String, Object> payload = published(Map.of("orderId", orderId, "paymentMethod", PaymentMethod.UPI,
                "amountUnits", 1000, "merchantId", "m-1")).getPayload();

        assertThat(payload).containsEntry("orderId", orderId.toString())
                .containsEntry("paymentMethod", "UPI")
                .containsEntry("amountUnits", 1000)
                .containsEntry("merchantId", "m-1");
    }

    // What Hibernate does with a JSON column: copy it by writing it out and reading it in, and treat the row as changed
    // if the copy isn't equal to the value. A payload that comes back equal is never UPDATEd after its INSERT.
    @Test
    void whatIsStoredComesBackEqualFromAJsonRoundTrip() throws Exception {
        Map<String, Object> raw = new HashMap<>();
        raw.put("orderId", UUID.randomUUID());
        raw.put("paymentMethod", PaymentMethod.CARD);
        raw.put("amountUnits", 2500);
        raw.put("amountCurrency", "INR");
        raw.put("errorCode", null);

        Map<String, Object> stored = published(raw).getPayload();

        JsonMapper json = JsonMapper.builder().build();
        Map<?, ?> roundTripped = json.readValue(json.writeValueAsString(stored), Map.class);
        assertThat(roundTripped).isEqualTo(stored);
    }

    @Test
    void theJsonThatGoesOutIsTheSameAsBefore() throws Exception {
        UUID orderId = UUID.randomUUID();
        JsonMapper json = JsonMapper.builder().build();

        Map<String, Object> stored = published(Map.of("orderId", orderId, "paymentMethod", PaymentMethod.UPI)).getPayload();

        assertThat(json.readTree(json.writeValueAsString(stored)))
                .isEqualTo(json.readTree(json.writeValueAsString(Map.of("orderId", orderId, "paymentMethod", PaymentMethod.UPI))));
    }
}
