package com.project.payflo.payment_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.DashboardResponse;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.Granularity;
import com.project.payflo.payment_service.dto.response.AnalyticsResponses.ReportResponse;
import com.project.payflo.payment_service.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private static final int DEFAULT_REPORT_DAYS = 30;

    private final AnalyticsService analyticsService;
    private final MerchantContext merchantContext;

    // Live: read from the database on every call, so it is never cached anywhere.
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> dashboard() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(analyticsService.dashboard(merchantContext.getMerchantId(), LocalDate.now(), LocalDateTime.now()));
    }

    // The last 30 days by day, unless a range or a grouping is asked for.
    @GetMapping("/report")
    public ResponseEntity<ReportResponse> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") Granularity granularity) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_REPORT_DAYS - 1L);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(analyticsService.report(merchantContext.getMerchantId(), start, end, granularity));
    }
}
