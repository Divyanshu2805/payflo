package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.DashboardResponse;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.Granularity;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.ReportResponse;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.Summary;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final AnalyticsService service = new AnalyticsService(payments, refunds);

    private final UUID merchantId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 10, 7); // a Wednesday
    private final LocalDateTime now = today.atTime(15, 30);

    @BeforeEach
    void aQuietMerchant() {
        when(payments.capturedByDay(any(), any(), any())).thenReturn(List.of());
        when(payments.createdByDayAndStatus(any(), any(), any())).thenReturn(List.of());
        when(payments.capturedByMethod(any(), any(), any())).thenReturn(List.of());
        when(refunds.processedByDay(any(), any(), any())).thenReturn(List.of());
    }

    private static Object[] row(Object... columns) {
        return columns;
    }

    // List.of(oneArray) would spread the array as the elements, so rows go through here
    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    // ---- dashboard

    @Test
    void aMerchantWithNoPaymentsGetsSevenQuietDaysNotAnEmptyList() {
        DashboardResponse dashboard = service.dashboard(merchantId, today, now);

        assertThat(dashboard.daily()).hasSize(7);
        assertThat(dashboard.daily().getFirst().periodStart()).isEqualTo(today.minusDays(6));
        assertThat(dashboard.daily().getLast().periodStart()).isEqualTo(today);
        assertThat(dashboard.daily()).allSatisfy(day -> {
            assertThat(day.grossAmountUnits()).isZero();
            assertThat(day.successRate()).isNull();
        });
        assertThat(dashboard.todaySummary().netAmountUnits()).isZero();
        assertThat(dashboard.currency()).isEqualTo("INR");
        assertThat(dashboard.generatedAt()).isEqualTo(now);
    }

    @Test
    void todayAndTheWeekAreSummedFromTheDays() {
        when(payments.capturedByDay(eq(merchantId), any(), any())).thenReturn(rows(
                row(java.sql.Date.valueOf(today), 3L, 30_000L),
                row(java.sql.Date.valueOf(today.minusDays(2)), 2L, 20_000L),
                row(java.sql.Date.valueOf(today.minusDays(6)), 1L, 5_000L)));

        DashboardResponse dashboard = service.dashboard(merchantId, today, now);

        assertThat(dashboard.todaySummary().grossAmountUnits()).isEqualTo(30_000);
        assertThat(dashboard.todaySummary().capturedCount()).isEqualTo(3);
        assertThat(dashboard.last7Days().grossAmountUnits()).isEqualTo(55_000);
        assertThat(dashboard.last7Days().capturedCount()).isEqualTo(6);
        assertThat(dashboard.last7Days().periodStart()).isEqualTo(today.minusDays(6));
    }

    @Test
    void refundsAreSubtractedFromWhatTheMerchantKept() {
        when(payments.capturedByDay(eq(merchantId), any(), any())).thenReturn(rows(row(java.sql.Date.valueOf(today), 2L, 20_000L)));
        when(refunds.processedByDay(eq(merchantId), any(), any())).thenReturn(rows(row(java.sql.Date.valueOf(today), 1L, 4_500L)));

        Summary todaySummary = service.dashboard(merchantId, today, now).todaySummary();

        assertThat(todaySummary.refundedAmountUnits()).isEqualTo(4_500);
        assertThat(todaySummary.netAmountUnits()).isEqualTo(15_500);
    }

    @Test
    void theSuccessRateIsCapturedOverCapturedPlusFailedAndIgnoresPaymentsStillInFlight() {
        when(payments.createdByDayAndStatus(eq(merchantId), any(), any())).thenReturn(rows(
                row(java.sql.Date.valueOf(today), "CAPTURED", 6L),
                row(java.sql.Date.valueOf(today), "SETTLED", 1L),
                row(java.sql.Date.valueOf(today), "FAILED", 2L),
                row(java.sql.Date.valueOf(today), "AUTH_EXPIRED", 1L),
                row(java.sql.Date.valueOf(today), "AUTHORIZING", 5L)));   // not decided yet

        Summary todaySummary = service.dashboard(merchantId, today, now).todaySummary();

        assertThat(todaySummary.paymentsCreated()).isEqualTo(15);
        assertThat(todaySummary.failedCount()).isEqualTo(3);
        assertThat(todaySummary.successRate()).isEqualTo(0.7); // 7 / (7 + 3)
    }

    @Test
    void aRefundedPaymentStillCountsAsASuccess() {
        when(payments.createdByDayAndStatus(eq(merchantId), any(), any())).thenReturn(rows(
                row(java.sql.Date.valueOf(today), "REFUNDED", 1L),
                row(java.sql.Date.valueOf(today), "PARTIALLY_REFUNDED", 1L)));

        assertThat(service.dashboard(merchantId, today, now).todaySummary().successRate()).isEqualTo(1.0);
    }

    @Test
    void theMethodBreakdownNamesTheMethodFromItsStoredName() {
        // payment.method holds the enum constant's name
        when(payments.capturedByMethod(eq(merchantId), any(), any())).thenReturn(rows(
                row("UPI", 5L, 50_000L), row("CARD", 2L, 20_000L)));

        var byMethod = service.dashboard(merchantId, today, now).byMethod();

        assertThat(byMethod).extracting("method").containsExactly(PaymentMethod.UPI, PaymentMethod.CARD);
        assertThat(byMethod.getFirst().grossAmountUnits()).isEqualTo(50_000);
        assertThat(byMethod.getFirst().capturedCount()).isEqualTo(5);
    }

    @Test
    void aRowOutsideTheWindowIsIgnoredNotAllowedToBreakTheDashboard() {
        when(payments.capturedByDay(eq(merchantId), any(), any())).thenReturn(rows(row(java.sql.Date.valueOf(today.plusDays(3)), 1L, 999L)));

        assertThat(service.dashboard(merchantId, today, now).last7Days().grossAmountUnits()).isZero();
    }

    @Test
    void aDriverThatReturnsLocalDatesAndIntegersWorksToo() {
        when(payments.capturedByDay(eq(merchantId), any(), any())).thenReturn(rows(row(today, 2, 1_000)));

        assertThat(service.dashboard(merchantId, today, now).todaySummary().grossAmountUnits()).isEqualTo(1_000);
    }

    @Test
    void theQueriesAreScopedToTheMerchantAndTheWindow() {
        service.dashboard(merchantId, today, now);

        verify(payments).capturedByDay(merchantId, today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());
        verify(refunds).processedByDay(merchantId, today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    // ---- historical report

    @Test
    void aReportCoversEveryDayOfTheRangeIncludingQuietOnes() {
        ReportResponse report = service.report(merchantId, today.minusDays(29), today, Granularity.DAY);

        assertThat(report.periods()).hasSize(30);
        assertThat(report.granularity()).isEqualTo(Granularity.DAY);
        assertThat(report.from()).isEqualTo(today.minusDays(29));
    }

    @Test
    void weeklyPeriodsStartOnMondayAndAddUpTheirDays() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        when(payments.capturedByDay(eq(merchantId), any(), any())).thenReturn(rows(
                row(java.sql.Date.valueOf(monday), 1L, 1_000L),
                row(java.sql.Date.valueOf(monday.plusDays(2)), 1L, 2_000L),
                row(java.sql.Date.valueOf(monday.minusDays(1)), 1L, 500L)));   // the Sunday before

        ReportResponse report = service.report(merchantId, monday.minusDays(1), monday.plusDays(6), Granularity.WEEK);

        assertThat(report.periods()).extracting(Summary::periodStart).containsExactly(monday.minusDays(7), monday);
        assertThat(report.periods().get(0).grossAmountUnits()).isEqualTo(500);
        assertThat(report.periods().get(1).grossAmountUnits()).isEqualTo(3_000);
        assertThat(report.totals().grossAmountUnits()).isEqualTo(3_500);
    }

    @Test
    void monthlyPeriodsStartOnTheFirstAndTotalsMatchTheSumOfThePeriods() {
        when(payments.capturedByDay(eq(merchantId), any(), any())).thenReturn(rows(
                row(java.sql.Date.valueOf(LocalDate.of(2026, 8, 31)), 1L, 100L),
                row(java.sql.Date.valueOf(LocalDate.of(2026, 9, 1)), 2L, 200L),
                row(java.sql.Date.valueOf(LocalDate.of(2026, 9, 30)), 3L, 300L),
                row(java.sql.Date.valueOf(LocalDate.of(2026, 10, 1)), 4L, 400L)));

        ReportResponse report = service.report(merchantId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 31), Granularity.MONTH);

        assertThat(report.periods()).extracting(Summary::periodStart)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
        assertThat(report.periods()).extracting(Summary::grossAmountUnits).containsExactly(100L, 500L, 400L);
        assertThat(report.totals().grossAmountUnits()).isEqualTo(1_000);
        assertThat(report.totals().capturedCount()).isEqualTo(10);
    }

    @Test
    void aReportCanGoBackAYear() {
        ReportResponse report = service.report(merchantId, today.minusDays(365), today, Granularity.MONTH);

        assertThat(report.periods().size()).isBetween(12, 13);
    }

    @Test
    void aRangeLongerThanAYearIsRefusedBeforeAnyQueryRuns() {
        assertThatThrownBy(() -> service.report(merchantId, today.minusDays(366), today, Granularity.DAY))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_DATE_RANGE"));
        verify(payments, org.mockito.Mockito.never()).capturedByDay(any(), any(), any());
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> service.report(merchantId, today, today.minusDays(1), Granularity.DAY))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_DATE_RANGE"));
    }

    @Test
    void aSingleDayReportIsAllowed() {
        assertThat(service.report(merchantId, today, today, Granularity.DAY).periods()).hasSize(1);
    }
}
