package com.project.payflo.vault_service.dto;

import com.project.payflo.vault_service.dto.request.TokenizeRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizeRequestTest {

    @Test
    void printingARequestNeverShowsTheCardNumberOrTheCvv() {
        TokenizeRequest request = new TokenizeRequest("4111111111111111", "737", 12, 2031, null, "Asha Rao");

        String text = request.toString();

        assertThat(text).doesNotContain("4111111111111111").doesNotContain("737");
        assertThat(text).contains("****1111").contains("cvv=***").contains("Asha Rao");
    }
}
