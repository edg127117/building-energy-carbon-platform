package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import static com.platform.carbon.CurrentReviewedDayVersionRead.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CurrentReviewedDayBatchBoundaryTest {
    private static final Instant FROM=Instant.parse("2026-01-01T00:00:00Z"),TO=FROM.plusSeconds(86400);
    private static final Supplier<Duration> BUDGET=()->Duration.ofSeconds(30);

    @Test void springPropertyDefaultsOffAndExplicitFalseRestoresLegacyProfile() {
        for(String flag:List.of("absent","true","false")) {
            try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                if(!flag.equals("absent"))context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("test",Map.of(
                                "carbon.dynamic.current-day-batched-read-enabled",flag)));
                context.registerBean(DynamicElectricityRepository.class,()->new DynamicElectricityRepository(
                        mock(org.springframework.jdbc.core.JdbcTemplate.class),new com.fasterxml.jackson.databind.ObjectMapper()));
                context.refresh();
                assertThat(context.getBean(DynamicElectricityRepository.class).currentReviewedDayLimits())
                        .isEqualTo(flag.equals("true")?BATCHED_LIMITS:DEFAULT_LIMITS);
            }
        }
    }

    @Test void optInChangesOnlyPageSizesAndCannotIncreaseRetentionCaps() {
        assertThat(BATCHED_LIMITS).isEqualTo(new Limits(8192,1000,1_000_000,120_000,33554432,16384));
        assertThatThrownBy(()->new Limits(8193,1000,1,1,1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Limits(8192,1001,1,1,1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Limits(8192,1000,1_000_001,1,1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Limits(8192,1000,1,120_001,1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Limits(8192,1000,1,1,33554433,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Limits(8192,1000,1,1,1,16385)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void completeLargePagesAndPartialNextPagesKeepInputDatabaseOrderAndHash() {
        for(int count:new int[]{1000,1001,8192,8193,9000}) {
            var facts=facts(count);var old=source(facts);var batched=source(facts);
            var expected=read(old,"B",FROM,TO,BUDGET,DEFAULT_LIMITS).orElseThrow();
            var actual=read(batched,"B",FROM,TO,BUDGET,BATCHED_LIMITS).orElseThrow();
            assertThat(actual.version(FROM,TO)).isEqualTo(expected.version(FROM,TO));
            assertThat(actual.version(FROM,TO)).isEqualTo(DynamicElectricityRepository.hash(
                    DynamicElectricityRepository.encode(facts.stream().map(Fact::fields).toList())));
            verify(batched,times(count/8192+1)).currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any());
        }
    }

    @Test void capFallbackValidatesTheWholeLegacyPrefixBeforeDiscardingFinalPage() {
        for(int cap:new int[]{1000,1001,2000,8192,8193}) {
            int prefix=((cap-1)/1000)*1000;
            for(String corruption:List.of("none","metadata","body","range")) {
                var rows=new ArrayList<>(facts(cap));
                if(prefix>0&&!corruption.equals("none")) {
                    var row=rows.get(prefix-1);var candidate=row.candidate();
                    if(corruption.equals("metadata"))candidate=new Candidate(null,candidate.start(),candidate.end());
                    if(corruption.equals("range"))candidate=new Candidate(candidate.id(),TO.toEpochMilli(),TO.plusSeconds(1).toEpochMilli());
                    var fields=new LinkedHashMap<>(row.fields());
                    if(corruption.equals("body"))fields.remove("revision");
                    rows.set(prefix-1,new Fact(candidate,fields,2L));
                }
                var repository=source(rows);
                var limits=new Limits(8192,1000,cap,120000,33554432,16384);
                if(prefix>0&&!corruption.equals("none"))
                    assertThatThrownBy(()->read(repository,"B",FROM,TO,BUDGET,limits))
                            .as("cap %s corruption %s",cap,corruption).isInstanceOf(IllegalStateException.class);
                else {
                    assertThat(read(repository,"B",FROM,TO,BUDGET,limits)).isEmpty();
                    verify(repository,times(prefix==0?0:prefix/1000))
                            .currentReviewedDayBodies(any(),any(),any(),any(),any());
                }
            }
        }
    }

    @Test void shortPageAtOrAroundLegacyPrefixStillCompletesAndFinalCapPageStillFallsBack() {
        for(int cap:new int[]{1000,1001,2000,8192,8193}) {
            int prefix=((cap-1)/1000)*1000;
            for(int count:new int[]{Math.max(0,prefix-1),prefix,cap-1}) {
                var rows=facts(count);
                var old=read(source(rows),"B",FROM,TO,BUDGET,new Limits(1000,256,cap,120000,33554432,16384));
                var fast=read(source(rows),"B",FROM,TO,BUDGET,new Limits(8192,1000,cap,120000,33554432,16384));
                assertThat(fast.orElseThrow().version(FROM,TO)).isEqualTo(old.orElseThrow().version(FROM,TO));
            }
            var rows=new ArrayList<>(facts(cap));var last=rows.getLast();
            rows.set(cap-1,new Fact(new Candidate(null,TO.toEpochMilli(),TO.plusSeconds(1).toEpochMilli()),
                    last.fields(),2L));
            for(Limits limits:List.of(new Limits(1000,256,cap,120000,33554432,16384),
                    new Limits(8192,1000,cap,120000,33554432,16384)))
                assertThat(read(source(rows),"B",FROM,TO,BUDGET,limits)).isEmpty();
        }
    }

    @Test void rowAndByteCapsAtBodyBoundaryDiscardWholeSnapshot() {
        for(Limits limits:List.of(new Limits(8192,1000,10000,1000,33554432,16384),
                new Limits(8192,1000,10000,120000,2000,16384))) {
            assertThat(read(source(facts(1000)),"B",FROM,TO,BUDGET,limits)).isPresent();
            assertThat(read(source(facts(1001)),"B",FROM,TO,BUDGET,limits)).isEmpty();
        }
    }

    @Test void malformedSixFieldBodiesStillFailWithBothProfiles() {
        var candidate=facts(1).getFirst().candidate();
        for(Limits limits:List.of(DEFAULT_LIMITS,BATCHED_LIMITS)) {
            for(String bad:List.of("missing","extra","id","bytes","type","null")) {
                var fields=new LinkedHashMap<>(facts(1).getFirst().fields());Long bytes=2L;
                switch(bad) {
                    case "missing" -> fields.remove("revision");
                    case "extra" -> fields.put("extra",1);
                    case "id" -> fields.put("fact_id","wrong");
                    case "bytes" -> bytes=1L;
                    case "type" -> fields.put("reading_json",2);
                    case "null" -> fields.put("reading_json",null);
                }
                var repository=source(List.of(new Fact(candidate,fields,bytes)));
                assertThatThrownBy(()->read(repository,"B",FROM,TO,BUDGET,limits))
                        .as(bad).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    private List<Fact> facts(int count) {
        return IntStream.range(0,count).mapToObj(index->{
            // Deliberately not Java lexical order: the mock represents database-returned order.
            var candidate=new Candidate((index%2==0?"a":"B")+index,FROM.toEpochMilli(),TO.toEpochMilli());
            return new Fact(candidate,Map.of("fact_id",candidate.id(),"point_id","P","reading_json","{}",
                    "normalized_quantity",java.math.BigDecimal.ONE,"revision",1,"reviewed_at",1L),2L);
        }).toList();
    }

    private DynamicElectricityRepository source(List<Fact> facts) {
        var repository=mock(DynamicElectricityRepository.class);
        var ids=facts.stream().map(row->row.candidate().id()).toList();
        when(repository.currentReviewedDayCandidates(any(),any(),any(),any(),anyInt(),any())).thenAnswer(call->{
            String after=call.getArgument(3);int size=call.getArgument(4);
            int offset=after==null?0:ids.indexOf(after)+1;
            return facts.subList(offset,Math.min(facts.size(),offset+size)).stream().map(Fact::candidate).toList();
        });
        when(repository.currentReviewedDayBodies(any(),any(),any(),any(),any())).thenAnswer(call->{
            List<String> selected=call.getArgument(3);
            return selected.stream().map(id->facts.get(ids.indexOf(id))).toList();
        });
        return repository;
    }
}
