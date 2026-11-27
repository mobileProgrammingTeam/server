package org.example.workersafety_server;

import java.time.Instant;
import org.example.workersafety_server.service.HeartRateReportPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HeartRateReportPolicyTests {
    @Test void missingReportsAreNotNormal() {
        assertThat(HeartRateReportPolicy.freshness(null, Instant.now())).isEqualTo("NOT_RECEIVED");
    }
    @Test void expiryUsesMeasurementTimeAndFiveMinutesPlusTwoMinuteGrace() {
        Instant at = Instant.parse("2026-11-27T00:00:00Z");
        assertThat(HeartRateReportPolicy.REPORT_INTERVAL.toSeconds()).isEqualTo(300);
        assertThat(HeartRateReportPolicy.freshness(at, at.plusSeconds(300))).isEqualTo("FRESH");
        assertThat(HeartRateReportPolicy.freshness(at, at.plusSeconds(420))).isEqualTo("FRESH");
        assertThat(HeartRateReportPolicy.freshness(at, at.plusSeconds(420).plusMillis(1))).isEqualTo("STALE");
    }
}
