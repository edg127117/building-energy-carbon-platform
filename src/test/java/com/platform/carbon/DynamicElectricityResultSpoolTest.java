package com.platform.carbon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.platform.carbon.DynamicElectricityRepository.*;
import static org.assertj.core.api.Assertions.*;

/** 仅操作每用例私有临时目录，验证完整证据暂存、字节边界、损坏拒绝与失败清理。 */
class DynamicElectricityResultSpoolTest {
    @TempDir Path directory;
    private CarbonCalculationDeadline deadline(){return CarbonCalculationDeadline.start(Duration.ofMinutes(1));}
    private DynamicElectricityResultSpool open(DynamicElectricityResultSpool.Limits limits) {
        return new DynamicElectricityResultSpool(deadline(),limits,directory);
    }

    @Test void preservesCanonicalBodiesOrderingHashesAndDeletesOnClose() throws Exception {
        var first=result("first");var second=result("second");
        var values=new ArrayList<String>();
        try(var spool=open(DynamicElectricityResultSpool.DEFAULT_LIMITS)) {
            spool.append(first);spool.append(second);
            assertThat(spool.ids()).containsExactly(first.id(),second.id());
            assertThat(spool.heads().getFirst().sourceVersion()).isEqualTo(first.sourceVersion());
            spool.visit((body,digest)->{assertThat(hash(body)).isEqualTo(digest);values.add(body);});
            assertThat(values).containsExactly(encode(first),encode(second));
            assertThatThrownBy(()->spool.append(first)).hasMessageContaining("已经封存");
        }
        assertDirectoryEmpty();
    }

    @Test void enforcesRowCountAndTotalBytesWithoutKeepingFullResults() throws Exception {
        var value=result("limit");int size=encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        try(var spool=open(new DynamicElectricityResultSpool.Limits(1,size,2L*(size+4)))) {
            spool.append(value);
            assertThatThrownBy(()->spool.append(value)).hasMessageContaining("暂存上限");
        }
        int stored;
        try(var spool=open(DynamicElectricityResultSpool.DEFAULT_LIMITS)) {
            spool.append(value);stored=spool.heads().getFirst().storedBytes();
        }
        try(var spool=open(new DynamicElectricityResultSpool.Limits(3,size,stored+4L))) {
            spool.append(value);
            assertThatThrownBy(()->spool.append(value)).hasMessageContaining("暂存上限");
        }
        assertDirectoryEmpty();
    }

    @Test void keepsTheFull744Hour219PointEvidenceWithinTheUnchangedDiskBudget() throws Exception {
        var evidence=CarbonEvidence.object();var inputs=evidence.putObject("pointInputs");
        var contexts=evidence.putObject("contexts");
        for(int point=0;point<219;point++) {
            var input=inputs.putObject("point-"+point);
            input.put("quantityKwh","12.34567890123456789");
            input.put("factId",hash("synthetic-fact-"+point));
            input.put("sourceReference","合成规模回归，不是真实现场来源。".repeat(28));
            var context=contexts.putObject(hash("context-"+point));
            context.put("pointId","point-"+point);
            context.put("approvedConfiguration","完整固定规则证据。".repeat(70));
        }
        evidence.set("activities",inputs.deepCopy());
        var template=result("scale");var count=new java.util.concurrent.atomic.AtomicInteger();
        try(var spool=new DynamicElectricityResultSpool(CarbonCalculationDeadline.start(Duration.ofMinutes(5)),
                DynamicElectricityResultSpool.DEFAULT_LIMITS,directory)) {
            for(int hour=0;hour<744;hour++) {
                Instant from=template.start().plusSeconds(hour*3600L);
                spool.append(new Result(hash("scale-"+hour),template.building(),"HOUR",from,from.plusSeconds(3600),
                        "version-"+hour,template.sourceKind(),template.values(),hash(CarbonEvidence.canonical(evidence)),
                        evidence,template.factorHash(),hour));
            }
            assertThat(spool.heads()).hasSize(744);
            long expanded=spool.heads().stream().mapToLong(Head->Head.bytes()+4L).sum();
            long stored=spool.heads().stream().mapToLong(Head->Head.storedBytes()+4L).sum();
            assertThat(expanded).isGreaterThan(DynamicElectricityResultSpool.DEFAULT_LIMITS.totalBytes());
            assertThat(stored).isLessThan(64L*1024*1024);
            spool.visit((payload,digest)->{
                int hour=count.getAndIncrement();
                assertThat(hash(payload)).isEqualTo(digest);
                var restored=CarbonEvidence.parse(payload);
                assertThat(restored.path("id").asText()).isEqualTo(hash("scale-"+hour));
                assertThat(restored.at("/electricityEvidence/pointInputs")).hasSize(219);
                assertThat(CarbonEvidence.canonical(restored.path("electricityEvidence")))
                        .isEqualTo(CarbonEvidence.canonical(evidence));
            });
            assertThat(count.get()).isEqualTo(744);
            assertThat(Files.size(onlyFile())).isEqualTo(stored);
            System.out.printf("SYNTHETIC_219_744_SPOOL expanded=%d stored=%d%n",expanded,stored);
        }
        assertDirectoryEmpty();
    }

    @Test void rejectsTruncatedCompressedFramesAndTrailingBytesBeforeARepeatPublication() throws Exception {
        try(var spool=open(DynamicElectricityResultSpool.DEFAULT_LIMITS)) {
            spool.append(result("truncated"));spool.visit((body,digest)->{});
            assertThat(spool.heads().getFirst().storedBytes()).isLessThan(spool.heads().getFirst().bytes());
            try(var file=new RandomAccessFile(onlyFile().toFile(),"rw")){file.setLength(file.length()-1);}
            assertThatThrownBy(()->spool.visit((body,digest)->fail("publisher must not run")))
                    .hasMessageContaining("证据不一致");
        }
        try(var spool=open(DynamicElectricityResultSpool.DEFAULT_LIMITS)) {
            spool.append(result("trailing"));spool.visit((body,digest)->{});
            Files.write(onlyFile(),new byte[]{1},StandardOpenOption.APPEND);
            assertThatThrownBy(()->spool.visit((body,digest)->{})).hasMessageContaining("证据不一致");
        }
        assertDirectoryEmpty();
    }

    @Test void measuresUtf8BytesRatherThanCharacters() throws Exception {
        var value=result("utf8");String payload=encode(value);
        int bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(bytes).isGreaterThan(payload.length());
        try(var spool=open(new DynamicElectricityResultSpool.Limits(1,bytes-1,bytes+4L))) {
            assertThatThrownBy(()->spool.append(value)).hasMessageContaining("暂存上限");
        }
        assertDirectoryEmpty();
    }

    @Test void detectsAlteredBodyBeforeCallingPublisher() throws Exception {
        try(var spool=open(DynamicElectricityResultSpool.DEFAULT_LIMITS)) {
            spool.append(result("hash"));spool.visit((body,digest)->{});
            try(var file=new RandomAccessFile(onlyFile().toFile(),"rw")){file.seek(4);file.writeByte('!');}
            assertThatThrownBy(()->spool.visit((body,digest)->fail("publisher must not receive corrupt evidence")))
                    .hasMessageContaining("证据不一致");
        }
        assertDirectoryEmpty();
    }

    @Test void rejectsOversizedFrameBeforeAllocatingItsBody() throws Exception {
        try(var spool=open(DynamicElectricityResultSpool.DEFAULT_LIMITS)) {
            spool.append(result("frame"));spool.visit((body,digest)->{});
            try(var file=new RandomAccessFile(onlyFile().toFile(),"rw")){file.writeInt(Integer.MAX_VALUE);}
            assertThatThrownBy(()->spool.visit((body,digest)->fail("publisher must not run")))
                    .hasMessageContaining("证据不一致");
        }
        assertDirectoryEmpty();
    }

    @Test void consumerFailureAndExpiredDeadlineStillCleanUp() throws Exception {
        assertThatThrownBy(()->{
            try(var spool=open(DynamicElectricityResultSpool.DEFAULT_LIMITS)) {
                spool.append(result("failure"));
                spool.visit((body,digest)->{throw new IllegalStateException("publish-failed");});
            }
        }).hasMessage("publish-failed");
        try(var spool=new DynamicElectricityResultSpool(CarbonCalculationDeadline.start(Duration.ZERO),
                DynamicElectricityResultSpool.DEFAULT_LIMITS,directory)) {
            assertThatThrownBy(()->spool.append(result("expired"))).hasMessageContaining("超时");
        }
        assertDirectoryEmpty();
    }

    private Path onlyFile() throws Exception {
        try(var paths=Files.list(directory)){return paths.findFirst().orElseThrow();}
    }
    private void assertDirectoryEmpty() throws Exception {
        try(var paths=Files.list(directory)){assertThat(paths.toList()).isEmpty();}
    }
    private Result result(String key) {
        Instant from=Instant.parse("2025-01-01T00:00:00Z");
        var applied=new DynamicElectricityCore.Applied(new BigDecimal("12.34567890123456789"),
                new BigDecimal("6.172839450617283945"),new BigDecimal("0.5"),List.of("factor-id"),List.of("SIMULATED"),true);
        return new Result(hash(key),"B","HOUR",from,from.plusSeconds(3600),"source-version","REVIEWED_INTERVAL",
                applied,"electricity-hash",CarbonEvidence.tree(Map.of("完整证据",List.of("测点一","测点二"))),"factor-hash",1);
    }
}
