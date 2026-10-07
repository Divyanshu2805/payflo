package com.project.payflo.payment_service.outbox;

import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.payment_service.entity.OutboxEvent;
import com.project.payflo.payment_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;

    public void publish(EventAggregateType aggregateType, UUID aggregateId, String eventType,
                        Map<String, Object> payload) {
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(jsonStable(payload))
                .build();
        outboxEventRepository.save(outboxEvent);
    }

    /**
     * The payload as JSON will give it back: an enum is its name and a UUID its text. Hibernate keeps a copy of a JSON
     * column made by writing the value out and reading it in again, and compares it with the value at flush; a UUID or
     * an enum in the map is a String after that round trip, so the row looked changed and was UPDATEd straight after
     * its INSERT, for every event, which was about a third of the work the database did under load. The JSON that goes
     * out is the same either way.
     */
    static Map<String, Object> jsonStable(Map<String, Object> payload) {
        Map<String, Object> stable = new LinkedHashMap<>(payload.size() * 2);
        payload.forEach((key, value) -> stable.put(key, switch (value) {
            case Enum<?> named -> named.name();
            case UUID id -> id.toString();
            case null, default -> value;
        }));
        return stable;
    }
}
