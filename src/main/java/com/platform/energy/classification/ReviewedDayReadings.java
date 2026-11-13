package com.platform.energy.classification;

import org.springframework.dao.QueryTimeoutException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.platform.energy.classification.ClassificationContracts.ReadingView;
import static com.platform.energy.classification.ClassificationCore.error;

/** 日内请求私有读取，不缓存身份或元数据；所有页必须在调用方同一个只读 RR 事务内。 */
final class ReviewedDayReadings {
    // 日并集允许24个各5000条的小时；字节界另外限制大正文，不能静默截断成成功结果。
    static final Limits DEFAULT_LIMITS=new Limits(1000,256,1_000_000,120_000,32L*1024*1024,16*1024);
    record Limits(int metadataPageSize,int bodyPageSize,int maximumCandidates,int maximumRows,long maximumBytes,int maximumRowBytes) {
        Limits(int pageSize,int maximumCandidates,int maximumRows,long maximumBytes,int maximumRowBytes) {
            this(pageSize,Math.min(pageSize,256),maximumCandidates,maximumRows,maximumBytes,maximumRowBytes);
        }
        Limits {
            if(metadataPageSize<1||metadataPageSize>1000||bodyPageSize<1||bodyPageSize>256
                    ||maximumCandidates<1||maximumRows<1||maximumBytes<1||maximumRowBytes<1||maximumRowBytes>16384)
                throw new IllegalArgumentException("Invalid reviewed day source limits");
        }
    }

    private ReviewedDayReadings() {}

    static List<ReadingView> read(ClassificationRepository repository,String building,Instant from,Instant to,
            Instant asOf,Supplier<Duration> remaining,Function<ClassificationRepository.StoredReading,ReadingView> normalize) {
        return read(repository,building,from,to,asOf,remaining,normalize,DEFAULT_LIMITS);
    }

    static List<ReadingView> read(ClassificationRepository repository,String building,Instant from,Instant to,
            Instant asOf,Supplier<Duration> remaining,Function<ClassificationRepository.StoredReading,ReadingView> normalize,
            Limits limits) {
        var result=new ArrayList<ReadingView>();
        Instant cursor=null;String cursorId=null;int scanned=0;long retainedBytes=0;
        while(true) {
            checkRemaining(remaining);
            var page=repository.overlappingWindowCandidatesPage(building,from,to,cursor,cursorId,limits.metadataPageSize(),remaining);
            if(page.size()>limits.metadataPageSize())throw integrity();
            scanned=Math.addExact(scanned,page.size());
            if(scanned>limits.maximumCandidates())throw capacity();
            var candidates=new LinkedHashMap<String,ClassificationRepository.WindowCandidate>();
            // Check timestamps and duplicate IDs, not Java lexical order: MySQL ID collation can be case-insensitive.
            var pageIds=new HashSet<String>();
            if(cursorId!=null)pageIds.add(cursorId);
            for(var candidate:page) {
                checkRemaining(remaining);
                if(candidate.id()==null||candidate.id().isEmpty()||candidate.id().length()>32||!pageIds.add(candidate.id())||candidate.startInclusive()==null
                        ||candidate.endExclusive()==null||!candidate.startInclusive().isBefore(to)
                        ||(cursor!=null&&(candidate.startInclusive().isBefore(cursor)
                        ||(candidate.startInclusive().equals(cursor)&&candidate.id().equals(cursorId)))))throw integrity();
                cursor=candidate.startInclusive();cursorId=candidate.id();
                // 在覆盖索引中过滤无关历史；不加开始时间下界，跨日事实与异常空结束时间仍进入核验。
                if(candidate.endExclusive().isAfter(from)&&candidates.put(candidate.id(),candidate)!=null)throw integrity();
            }
            var ids=List.copyOf(candidates.keySet());
            // Metadata pages stay narrow; split payloads independently so each JDBC result carries at most 4 MiB JSON.
            for(int offset=0;offset<ids.size();offset+=limits.bodyPageSize()) {
                checkRemaining(remaining);
                var batchIds=ids.subList(offset,Math.min(ids.size(),offset+limits.bodyPageSize()));
                var pending=new HashSet<>(batchIds);
                var rows=repository.approvedWindowByIdsAsOf(building,from,to,asOf,batchIds,remaining);
                if(rows.size()>batchIds.size())throw integrity();
                for(var value:rows) {
                    checkRemaining(remaining);
                    var row=value.reading();var candidate=candidates.remove(row.id());
                    if(candidate==null||!pending.remove(row.id())||!Objects.equals(candidate.startInclusive(),row.startInclusive())
                            ||!Objects.equals(candidate.endExclusive(),value.endExclusive())
                            ||!value.endExclusive().isAfter(row.startInclusive())||!"APPROVED".equals(row.batchStatus()))throw integrity();
                    // SQL CASE caps driver buffering before getString; null is an oversized-body failure, never absence.
                    if(row.json()==null||row.json().length()>limits.maximumRowBytes())throw capacity();
                    int bytes=row.json().getBytes(StandardCharsets.UTF_8).length;
                    retainedBytes=Math.addExact(retainedBytes,bytes);
                    if(bytes>limits.maximumRowBytes()||retainedBytes>limits.maximumBytes()
                            ||result.size()>=limits.maximumRows())throw capacity();
                    var view=normalize.apply(row);
                    if(!Objects.equals(view.reading().startInclusive(),row.startInclusive())
                            ||!Objects.equals(view.reading().endExclusive(),value.endExclusive()))throw integrity();
                    result.add(view);
                }
            }
            if(page.size()<limits.metadataPageSize())break;
        }
        checkRemaining(remaining);
        result.sort(Comparator.comparing((ReadingView r)->r.reading().startInclusive()).thenComparing(ReadingView::factId));
        checkRemaining(remaining);
        return List.copyOf(result);
    }

    static void checkRemaining(Supplier<Duration> remaining) {
        Duration left=Objects.requireNonNull(remaining).get();
        if(left==null||left.isNegative()||left.toMillis()<1||Thread.currentThread().isInterrupted())
            throw new QueryTimeoutException("Reviewed day source budget expired");
    }

    private static RuntimeException capacity(){return error(409,"Reviewed day source candidate, row or byte limit exceeded");}
    private static RuntimeException integrity(){return error(409,"Reviewed day source evidence is inconsistent");}
}
