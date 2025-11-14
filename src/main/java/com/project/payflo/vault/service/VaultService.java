package com.project.payflo.vault.service;

import com.project.payflo.vault.dto.request.TokenizeRequest;
import com.project.payflo.vault.dto.response.TokenizeResponse;
import java.util.UUID;

public interface VaultService {

    TokenizeResponse tokenize(TokenizeRequest request, UUID merchantId);

}
