package org.example.workersafety_server.service;

import java.time.Duration;
import java.time.Instant;

/** A five-minute assessment cadence with the app's two-minute delivery allowance. */
public final class HeartRateReportPolicy {
    public static final Duration REPORT_INTERVAL = Duration.ofMinutes(5);
    public static final Duration MAX_AGE = REPORT_INTERVAL.plusMinutes(2);
    private HeartRateReportPolicy() { }

    public static String freshness(Instant measuredAt, Instant now) {
        if (measuredAt == null) return "NOT_RECEIVED";
        return now.isAfter(measuredAt.plus(MAX_AGE)) ? "STALE" : "FRESH";
    }
}
