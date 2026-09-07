package com.platform.energy.efficiency;

import com.platform.energy.efficiency.EerpAnnualCore.Segment;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.platform.energy.efficiency.EerpContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EerpAnnualCoreTest {
    private static final String BUILDING_ID = "BLD001";
    private static final String STATION_ID = "STATION001";
    private static final String TIMEZONE_VERSION = "TZDB-TEST-2026";

    private final EerpAnnualCore core = new EerpAnnualCore();

    @Test
    void calculatesCompleteNaturalYearAtExactFive() {
        int year = 2025;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<Segment> segments = calendarDaySegments(
                year, zone, new BigDecimal("5000000"), new BigDecimal("1000000"), true);

        AnnualResult annual = core.calculate(request(year, zone, segments), segments, afterYear(year, zone));

        assertThat(segments).hasSize(365);
        assertThat(annual.fromInclusive()).isEqualTo(LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant());
        assertThat(annual.toExclusive()).isEqualTo(LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant());
        assertThat(annual.coolingKwh()).isEqualByComparingTo("5000000");
        assertThat(annual.electricityKwh()).isEqualByComparingTo("1000000");
        assertThat(annual.eerp()).isEqualByComparingTo("5");
        assertThat(annual.calculationStatus()).isEqualTo("CALCULATED");
        assertThat(annual.completeness()).isEqualTo("COMPLETE");
        assertThat(annual.evaluationStatus()).isEqualTo("DEVELOPMENT_EVALUATED");
        assertThat(annual.evaluationBand()).isEqualTo("GUIDANCE_ONLY");
        assertThat(annual.inputTaskIds()).hasSize(365);
        assertThat(annual.issues()).isEmpty();
    }

    @Test
    void usesCalendarDaySegmentsForLeapYearAndTimezoneTransitions() {
        int year = 2024;
        ZoneId zone = ZoneId.of("America/New_York");
        List<Segment> segments = calendarDaySegments(
                year, zone, new BigDecimal("366"), new BigDecimal("366"), true);

        AnnualResult annual = core.calculate(request(year, zone, segments), segments, afterYear(year, zone));
        Segment springForward = segments.get(LocalDate.of(year, 3, 10).getDayOfYear() - 1);
        Segment fallBack = segments.get(LocalDate.of(year, 11, 3).getDayOfYear() - 1);

        assertThat(segments).hasSize(366);
        assertThat(segments.getFirst().result().fromInclusive())
                .isEqualTo(LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant());
        assertThat(segments.getLast().result().toExclusive())
                .isEqualTo(LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant());
        for (int index = 1; index < segments.size(); index++) {
            assertThat(segments.get(index).result().fromInclusive())
                    .isEqualTo(segments.get(index - 1).result().toExclusive());
        }
        assertThat(Duration.between(springForward.result().fromInclusive(), springForward.result().toExclusive()))
                .isEqualTo(Duration.ofHours(23));
        assertThat(Duration.between(fallBack.result().fromInclusive(), fallBack.result().toExclusive()))
                .isEqualTo(Duration.ofHours(25));
        assertThat(annual.fromInclusive()).isEqualTo(segments.getFirst().result().fromInclusive());
        assertThat(annual.toExclusive()).isEqualTo(segments.getLast().result().toExclusive());
        assertThat(annual.eerp()).isEqualByComparingTo("1");
        assertThat(annual.completeness()).isEqualTo("COMPLETE");
    }

    @Test
    void marksCalendarGapsAndPartialInputsIncomplete() {
        int year = 2025;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<Segment> segments = new ArrayList<>(calendarDaySegments(
                year, zone, new BigDecimal("3650"), new BigDecimal("365"), true));
        segments.remove(60);
        segments.set(200, withComplete(segments.get(200), false));

        AnnualResult annual = core.calculate(request(year, zone, segments), segments, afterYear(year, zone));

        assertThat(annual.eerp()).isNull();
        assertThat(annual.calculationStatus()).isEqualTo("NOT_CALCULABLE");
        assertThat(annual.completeness()).isEqualTo("INCOMPLETE");
        assertThat(annual.evaluationStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(annual.issues()).extracting(Issue::code)
                .contains("PERIOD_GAP", "INPUT_INCOMPLETE");
    }

    @Test
    void rejectsOverlappingCalendarSegments() {
        int year = 2025;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<Segment> days = calendarDaySegments(year, zone, new BigDecimal("3650"), new BigDecimal("365"), true);
        Segment first = days.getFirst();
        Segment second = days.get(1);
        Segment overlap = segment("overlap", first.result().fromInclusive(), second.result().toExclusive(),
                BigDecimal.TEN, BigDecimal.ONE, true, List.of(), first.configuration());

        assertThatThrownBy(() -> core.calculate(
                request(year, zone, List.of(first, overlap)), List.of(first, overlap), afterYear(year, zone)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("EERP_PERIOD_OVERLAP"));
    }

    @Test
    void doesNotCalculateWhenAnnualElectricityIsZero() {
        int year = 2025;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<Segment> segments = calendarDaySegments(year, zone, new BigDecimal("5000000"), BigDecimal.ZERO, true);

        AnnualResult annual = core.calculate(request(year, zone, segments), segments, afterYear(year, zone));

        assertThat(annual.eerp()).isNull();
        assertThat(annual.calculationStatus()).isEqualTo("NOT_CALCULABLE");
        assertThat(annual.evaluationStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(annual.evaluationBand()).isNull();
        assertThat(annual.issues()).extracting(Issue::code).contains("ZERO_DENOMINATOR");
    }

    @Test
    void classifiesExactThresholdsWithoutUsingDisplayRounding() {
        BigDecimal justAboveGuidance = new BigDecimal("4.00001");
        BigDecimal justAboveAdvanced = new BigDecimal("5.00001");

        assertThat(EerpAnnualCore.band(new BigDecimal("3.99999"), BigDecimal.ONE)).isEqualTo("BELOW_GUIDANCE");
        assertThat(EerpAnnualCore.band(new BigDecimal("4"), BigDecimal.ONE)).isEqualTo("AT_GUIDANCE");
        assertThat(EerpAnnualCore.band(new BigDecimal("5"), BigDecimal.ONE)).isEqualTo("GUIDANCE_ONLY");
        assertThat(EerpAnnualCore.band(justAboveGuidance, BigDecimal.ONE)).isEqualTo("GUIDANCE_ONLY");
        assertThat(EerpAnnualCore.band(justAboveAdvanced, BigDecimal.ONE)).isEqualTo("ADVANCED");
        assertThat(justAboveGuidance.setScale(1, RoundingMode.HALF_UP)).isEqualByComparingTo("4.0");
        assertThat(justAboveAdvanced.setScale(1, RoundingMode.HALF_UP)).isEqualByComparingTo("5.0");
    }

    private AnnualRequest request(int year, ZoneId zone, List<Segment> segments) {
        return new AnnualRequest("annual-" + year, BUILDING_ID, STATION_ID, year, zone.getId(),
                TIMEZONE_VERSION, segments.stream().map(Segment::taskId).toList(), null);
    }

    private Instant afterYear(int year, ZoneId zone) {
        return LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().plusMillis(1);
    }

    private List<Segment> calendarDaySegments(
            int year, ZoneId zone, BigDecimal coolingKwh, BigDecimal electricityKwh, boolean complete) {
        LocalDate firstDay = LocalDate.of(year, 1, 1);
        LocalDate firstDayNextYear = firstDay.plusYears(1);
        int dayCount = Math.toIntExact(ChronoUnit.DAYS.between(firstDay, firstDayNextYear));
        BigDecimal[] coolingDivision = coolingKwh.divideAndRemainder(BigDecimal.valueOf(dayCount));
        BigDecimal[] electricityDivision = electricityKwh.divideAndRemainder(BigDecimal.valueOf(dayCount));
        Configuration configuration = configuration(year, zone);
        List<Segment> segments = new ArrayList<>(dayCount);
        LocalDate date = firstDay;
        for (int index = 0; index < dayCount; index++, date = date.plusDays(1)) {
            boolean lastDay = index == dayCount - 1;
            BigDecimal dayCooling = coolingDivision[0].add(lastDay ? coolingDivision[1] : BigDecimal.ZERO);
            BigDecimal dayElectricity = electricityDivision[0].add(lastDay ? electricityDivision[1] : BigDecimal.ZERO);
            segments.add(segment("day-" + index, date.atStartOfDay(zone).toInstant(),
                    date.plusDays(1).atStartOfDay(zone).toInstant(), dayCooling, dayElectricity,
                    complete, List.of(), configuration));
        }
        return segments;
    }

    private Configuration configuration(int year, ZoneId zone) {
        return new Configuration(BUILDING_ID, STATION_ID, "boundary-v1", "relation-v1", zone.getId(),
                TIMEZONE_VERSION, LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
                LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant(), List.of(), List.of(), List.of(),
                "minimal fixture evidence", "EERP_RULE_V1", "EERP_REFERENCE_V1");
    }

    private Segment withComplete(Segment original, boolean complete) {
        PeriodResult result = original.result();
        return segment(original.taskId(), result.fromInclusive(), result.toExclusive(), result.coolingKwh(),
                result.electricityKwh(), complete, result.issues(), original.configuration());
    }

    private Segment segment(
            String taskId, Instant fromInclusive, Instant toExclusive, BigDecimal coolingKwh, BigDecimal electricityKwh,
            boolean complete, List<Issue> issues, Configuration configuration) {
        PeriodResult result = new PeriodResult("config-v1", BUILDING_ID, STATION_ID, fromInclusive, toExclusive,
                toExclusive, configuration.timezoneId(), TIMEZONE_VERSION, coolingKwh, electricityKwh, complete,
                List.of(), issues, "DEVELOPMENT_SIMULATION", "UNKNOWN");
        return new Segment(taskId, result, configuration);
    }
}
