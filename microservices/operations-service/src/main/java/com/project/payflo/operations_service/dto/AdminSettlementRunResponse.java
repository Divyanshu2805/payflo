package com.project.payflo.operations_service.dto;

import java.time.LocalDateTime;

/**
 * How a manual settlement run went. {@code settlementsCreated} are the payouts it started; the bank answers a few
 * seconds later, so their outcome is in the merchants' {@code GET /v1/settlements}.
 */
public record AdminSettlementRunResponse(LocalDateTime startedAt, LocalDateTime finishedAt, int merchants,
                                         int failedMerchants, long settlementsCreated) {
}
