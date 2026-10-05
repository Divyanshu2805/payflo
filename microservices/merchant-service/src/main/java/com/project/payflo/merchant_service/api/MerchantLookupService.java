package com.project.payflo.merchant_service.api;

import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.common_lib.enums.MerchantStatus;

import java.util.List;
import java.util.UUID;

public interface MerchantLookupService {

    List<WebhookTarget> getActiveConfigsForEvent(UUID merchantId, String eventType);

    /** One of the merchant's webhook configs, with its current signing secret, whether or not it is paused. */
    WebhookTarget getWebhookTarget(UUID merchantId, UUID configId);

    List<UUID> listActiveMerchantIds();

    SettlementBankDetails getSettlementBankDetails(UUID merchantId);

    MerchantStatus getStatus(UUID merchantId);

}
