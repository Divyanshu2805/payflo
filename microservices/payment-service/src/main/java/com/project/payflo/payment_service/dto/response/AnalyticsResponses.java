package com.project.payflo.payment_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.payflo.common_lib.enums.PaymentMethod;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What the merchant dashboard and the historical report return. Amounts are in the currency's smallest unit (paise)
 * and are {@code long}, because a day's revenue can exceed what one payment's {@code int} holds.
 */
public final class AnalyticsResponses {

    private AnalyticsResponses() {
    }

    public enum Granularity { DAY, WEEK, MONTH }

    /**
     * The figures for one period.
     *
     * <ul>
     *   <li>{@code grossAmountUnits} / {@code capturedCount}: payments captured in the period (by capture time).</li>
     *   <li>{@code refundedAmountUnits}: refunds the bank processed in the period (by processing time).</li>
     *   <li>{@code netAmountUnits}: gross minus refunded, the revenue the merchant kept.</li>
     *   <li>{@code paymentsCreated}, {@code failedCount}, {@code successRate}: payments started in the period (by
     *       creation time): how many, how many failed, and captured / (captured + failed). Absent while none has
     *       reached an outcome yet.</li>
     * </ul>
     *
     * {@code periodStart} is the first day of the period: the day itself, the Monday of the week, or the first of the
     * month; for a window total it is the window's first day.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(
            LocalDate periodStart,
            long grossAmountUnits,
            long capturedCount,
            long refundedAmountUnits,
            long netAmountUnits,
            long paymentsCreated,
            long failedCount,
            Double successRate
    ) {
    }

    /** Captured payments by method over a window. */
    public record MethodBreakdown(PaymentMethod method, long capturedCount, long grossAmountUnits) {
    }

    /** Today and the rolling last seven days, read live from the database on every call. */
    public record DashboardResponse(
            String currency,
            LocalDateTime generatedAt,
            LocalDate today,
            Summary todaySummary,
            Summary last7Days,
            List<Summary> daily,
            List<MethodBreakdown> byMethod
    ) {
    }

    /** A date range, grouped into days, weeks or months. */
    public record ReportResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            Granularity granularity,
            Summary totals,
            List<Summary> periods,
            List<MethodBreakdown> byMethod
    ) {
    }
}
