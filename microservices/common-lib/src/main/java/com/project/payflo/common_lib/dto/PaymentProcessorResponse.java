package com.project.payflo.common_lib.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// Crosses the vault-service -> payment-service Feign hop, so it needs a type discriminator
// for Jackson to pick the right record on the way back in.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PaymentProcessorResponse.Pending.class, name = "PENDING"),
        @JsonSubTypes.Type(value = PaymentProcessorResponse.Success.class, name = "SUCCESS"),
        @JsonSubTypes.Type(value = PaymentProcessorResponse.Failure.class, name = "FAILURE")
})
public sealed interface PaymentProcessorResponse permits
        PaymentProcessorResponse.Pending,
        PaymentProcessorResponse.Success,
        PaymentProcessorResponse.Failure {

    record Pending(String processorReference) implements PaymentProcessorResponse {}

    record Success(String processorReference, String bankReference) implements PaymentProcessorResponse{}

    record Failure(String errorCode, String errorDescription) implements PaymentProcessorResponse{}
}
