package com.platform.carbon;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataSnapshotTest {
    private static final Instant LEAP_FROM=Instant.parse("2024-02-28T16:00:00Z"); // 2024-02-29 00:00 Shanghai
    private static final Instant LEAP_TO=Instant.parse("2024-03-01T16:00:00Z");   // 2024-03-02 00:00 Shanghai

    @Test void futureVersionsAndLongExpiredHistoryDoNotChangeLeapMonthFingerprint() {
        var active=row("active",1,LocalDateTime.of(2024,2,1,0,0),null);
        String baseline=version(List.of(List.of(active)),LEAP_FROM,LEAP_TO);

        var future=row("future",1,LocalDateTime.of(2024,4,1,0,0),null);
        var historical=row("history",1,LocalDateTime.of(2023,1,1,0,0),LocalDateTime.of(2024,1,1,0,0));
        assertThat(version(List.of(List.of(active,future,historical)),LEAP_FROM,LEAP_TO)).isEqualTo(baseline);

        var bounded=row("active",1,LocalDateTime.of(2024,2,1,0,0),LocalDateTime.of(2024,4,1,0,0));
        assertThat(version(List.of(List.of(bounded)),LEAP_FROM,LEAP_TO)).isEqualTo(baseline);
    }

    @Test void intersectingRevisionAndDeletionChangeFingerprint() {
        var active=row("active",1,LocalDateTime.of(2024,1,1,0,0),LocalDateTime.of(2024,12,1,0,0));
        String baseline=version(List.of(List.of(active)),LEAP_FROM,LEAP_TO);
        assertThat(version(List.of(List.of(row("active",2,LocalDateTime.of(2024,1,1,0,0),LocalDateTime.of(2024,12,1,0,0)))),LEAP_FROM,LEAP_TO))
                .isNotEqualTo(baseline);
        assertThat(version(List.of(List.of()),LEAP_FROM,LEAP_TO)).isNotEqualTo(baseline);
    }

    @Test void startAndEndBoundariesEnteringTheHalfOpenPeriodChangeFingerprint() {
        var startsAtEnd=row("edge",1,LocalDateTime.of(2024,3,2,0,0),null);
        String outside=version(List.of(List.of(startsAtEnd)),LEAP_FROM,LEAP_TO);
        var startEnters=row("edge",1,LocalDateTime.of(2024,3,1,23,59,59),null);
        assertThat(version(List.of(List.of(startEnters)),LEAP_FROM,LEAP_TO)).isNotEqualTo(outside);

        var endedAtStart=row("edge",1,LocalDateTime.of(2024,1,1,0,0),LocalDateTime.of(2024,2,29,0,0));
        String endedOutside=version(List.of(List.of(endedAtStart)),LEAP_FROM,LEAP_TO);
        var endEnters=row("edge",1,LocalDateTime.of(2024,1,1,0,0),LocalDateTime.of(2024,2,29,0,0,1));
        assertThat(version(List.of(List.of(endEnters)),LEAP_FROM,LEAP_TO)).isNotEqualTo(endedOutside);
    }

    @Test void timestampUsesShanghaiLocalTimeAndEpochMillisecondsUseUtcAcrossYearBoundary() {
        Instant yearFrom=Instant.parse("2026-12-31T16:00:00Z"); // 2027-01-01 00:00 Shanghai
        Instant yearTo=Instant.parse("2027-01-01T16:00:00Z");   // 2027-01-02 00:00 Shanghai
        var localFrom=LocalDateTime.of(2027,1,1,0,0);
        var localTo=LocalDateTime.of(2027,1,2,0,0);

        var timestampRow=new LinkedHashMap<String,Object>();
        timestampRow.put("version_id","tz");timestampRow.put("config_revision",1);
        timestampRow.put("effective_from",Timestamp.valueOf(localFrom));timestampRow.put("effective_to",Timestamp.valueOf(localTo));
        var localRow=row("tz",1,localFrom,localTo);
        assertThat(version(List.of(List.of(timestampRow)),yearFrom,yearTo))
                .isEqualTo(version(List.of(List.of(localRow)),yearFrom,yearTo));

        var epochRow=new LinkedHashMap<String,Object>();
        epochRow.put("version_id","tz");epochRow.put("config_revision",1);
        epochRow.put("start_epoch_ms",yearFrom.toEpochMilli());epochRow.put("end_epoch_ms",yearTo.toEpochMilli());
        var epochEquivalent=new LinkedHashMap<String,Object>();
        epochEquivalent.put("version_id","tz");epochEquivalent.put("config_revision",1);
        epochEquivalent.put("start_epoch_ms",Instant.parse("2026-12-31T16:00:00Z").toEpochMilli());
        epochEquivalent.put("end_epoch_ms",Instant.parse("2027-01-01T16:00:00Z").toEpochMilli());
        assertThat(version(List.of(List.of(epochRow)),yearFrom,yearTo))
                .isEqualTo(version(List.of(List.of(epochEquivalent)),yearFrom,yearTo));
        assertThat(version(List.of(List.of(epochRow)),yearFrom,yearTo))
                .isNotEqualTo(version(List.of(List.of(epochRow)),yearFrom.plusSeconds(1),yearTo));
    }

    private static String version(List<List<Map<String,Object>>> groups,Instant from,Instant to) {
        return new MetadataSnapshot(groups).version(from,to);
    }

    private static Map<String,Object> row(String id,int revision,LocalDateTime from,LocalDateTime to) {
        var row=new LinkedHashMap<String,Object>();
        row.put("version_id",id);row.put("config_revision",revision);
        row.put("effective_from",from);row.put("effective_to",to);
        return row;
    }
}
