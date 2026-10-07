package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.DashboardResponse;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.Granularity;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.MethodBreakdown;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.ReportResponse;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.Summary;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The merchant's own numbers: a live dashboard (today and the last seven days) and a report over any date range,
 * grouped by day, week or month. Everything is scoped to the merchant and computed from a few indexed aggregate
 * queries; the days are then added up here, so a week or a month is just a sum of days.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    static final String CURRENCY = "INR"; // orders are only accepted in INR
    static final int DASHBOARD_DAYS = 7;
    static final int MAX_REPORT_DAYS = 366;

    // Payments that were captured at some point, and payments that ended without being captured.
    private static final Set<PaymentStatus> SUCCEEDED = Set.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED,
            PaymentStatus.REFUNDED, PaymentStatus.SETTLED);
    private static final Set<PaymentStatus> FAILED = Set.of(PaymentStatus.FAILED, PaymentStatus.AUTH_EXPIRED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID merchantId, LocalDate today, LocalDateTime now) {
        LocalDate from = today.minusDays(DASHBOARD_DAYS - 1L);
        TreeMap<LocalDate, Day> days = days(merchantId, from, today);

        List<Summary> daily = days.entrySet().stream().map(e -> e.getValue().summary(e.getKey())).toList();
        return new DashboardResponse(CURRENCY, now, today,
                days.get(today).summary(today),
                total(from, days.values()),
                daily,
                byMethod(merchantId, from, today));
    }

    @Transactional(readOnly = true)
    public ReportResponse report(UUID merchantId, LocalDate from, LocalDate to, Granularity granularity) {
        if (from.isAfter(to)) {
            throw new BusinessRuleViolationException("INVALID_DATE_RANGE", "from must not be after to");
        }
        if (from.plusDays(MAX_REPORT_DAYS - 1L).isBefore(to)) {
            throw new BusinessRuleViolationException("INVALID_DATE_RANGE",
                    "A report covers at most " + MAX_REPORT_DAYS + " days; ask for the range in parts");
        }

        TreeMap<LocalDate, Day> days = days(merchantId, from, to);

        TreeMap<LocalDate, Day> periods = new TreeMap<>();
        for (var entry : days.entrySet()) {
            periods.computeIfAbsent(periodStart(entry.getKey(), granularity), k -> new Day()).add(entry.getValue());
        }
        List<Summary> summaries = periods.entrySet().stream().map(e -> e.getValue().summary(e.getKey())).toList();

        return new ReportResponse(CURRENCY, from, to, granularity, total(from, days.values()), summaries,
                byMethod(merchantId, from, to));
    }

    // One Day per calendar day from..to inclusive, quiet days included as zeros, so a chart has no gaps.
    private TreeMap<LocalDate, Day> days(UUID merchantId, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay(); // exclusive

        TreeMap<LocalDate, Day> days = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.put(day, new Day());
        }

        for (Object[] row : paymentRepository.capturedByDay(merchantId, start, end)) {
            Day day = days.get(dateOf(row[0]));
            if (day != null) {
                day.capturedCount += number(row[1]);
                day.grossUnits += number(row[2]);
            }
        }
        for (Object[] row : paymentRepository.createdByDayAndStatus(merchantId, start, end)) {
            Day day = days.get(dateOf(row[0]));
            if (day == null) {
                continue;
            }
            PaymentStatus status = PaymentStatus.valueOf(String.valueOf(row[1]));
            long count = number(row[2]);
            day.created += count;
            if (SUCCEEDED.contains(status)) {
                day.succeeded += count;
            } else if (FAILED.contains(status)) {
                day.failed += count;
            }
        }
        for (Object[] row : refundRepository.processedByDay(merchantId, start, end)) {
            Day day = days.get(dateOf(row[0]));
            if (day != null) {
                day.refundedUnits += number(row[2]);
            }
        }
        return days;
    }

    private List<MethodBreakdown> byMethod(UUID merchantId, LocalDate from, LocalDate to) {
        List<MethodBreakdown> methods = new ArrayList<>();
        for (Object[] row : paymentRepository.capturedByMethod(merchantId, from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
            methods.add(new MethodBreakdown(PaymentMethod.valueOf(String.valueOf(row[0])), number(row[1]), number(row[2])));
        }
        return methods;
    }

    private static Summary total(LocalDate from, Iterable<Day> days) {
        Day sum = new Day();
        for (Day day : days) {
            sum.add(day);
        }
        return sum.summary(from);
    }

    private static LocalDate periodStart(LocalDate date, Granularity granularity) {
        return switch (granularity) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    // Native queries hand back java.sql.Date or LocalDate, and Long, Integer or BigInteger, depending on the driver
    private static LocalDate dateOf(Object value) {
        return value instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static final class Day {
        long grossUnits;
        long capturedCount;
        long refundedUnits;
        long created;
        long succeeded;
        long failed;

        void add(Day other) {
            grossUnits += other.grossUnits;
            capturedCount += other.capturedCount;
            refundedUnits += other.refundedUnits;
            created += other.created;
            succeeded += other.succeeded;
            failed += other.failed;
        }

        Summary summary(LocalDate periodStart) {
            long decided = succeeded + failed;
            Double successRate = decided == 0 ? null : Math.round(10_000.0 * succeeded / decided) / 10_000.0;
            return new Summary(periodStart, grossUnits, capturedCount, refundedUnits, grossUnits - refundedUnits,
                    created, failed, successRate);
        }
    }
}
