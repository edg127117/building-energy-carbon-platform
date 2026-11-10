package com.platform.carbon;

import org.springframework.dao.QueryTimeoutException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 查询事务私有的当前人工来源快照；不使用计算时点、不读取计算输入，也不创建或延长事务。 */
final class CurrentReviewedDayVersionRead {
    static final Duration MAX_STATEMENT_TIME=Duration.ofSeconds(30);
    // 字节上限只统计保留正文的 UTF-8 字节，不声称限制 JVM 对象、索引或序列化临时内存。
    static final Limits DEFAULT_LIMITS=new Limits(1000,256,1_000_000,120_000,32L*1024*1024,16*1024);

    // Opt-in only: payload retention caps stay unchanged; transient JDBC batches become larger.
    static final Limits BATCHED_LIMITS=new Limits(8192,1000,1_000_000,120_000,32L*1024*1024,16*1024);

    record Limits(int metadataPageSize,int bodyPageSize,int maximumCandidates,int maximumRows,
                  long maximumBytes,int maximumRowBytes) {
        Limits {
            if(metadataPageSize<1||metadataPageSize>8192||bodyPageSize<1||bodyPageSize>1000
                    ||maximumCandidates<1||maximumCandidates>1_000_000||maximumRows<1||maximumRows>120_000
                    ||maximumBytes<1||maximumBytes>32L*1024*1024||maximumRowBytes<1||maximumRowBytes>16384)
                throw new IllegalArgumentException("Invalid current reviewed day limits");
        }
    }

    record Candidate(String id,long start,long end) { }
    record Fact(Candidate candidate,Map<String,Object> fields,Long bytes) {
        Fact {
            var copy=new org.springframework.util.LinkedCaseInsensitiveMap<Object>();
            copy.putAll(fields);fields=Collections.unmodifiableMap(copy);
        }
    }

    static final class DaySnapshot {
        private final Instant from,to;
        private final List<Fact> facts;

        private DaySnapshot(Instant from,Instant to,List<Fact> facts) {
            this.from=from;this.to=to;this.facts=List.copyOf(facts);
        }

        String version(Instant from,Instant to) {
            if(from==null||to==null||!from.isBefore(to)||from.isBefore(this.from)||to.isAfter(this.to))
                throw new IllegalArgumentException("Version interval is outside current reviewed day snapshot");
            long start=from.toEpochMilli(),end=to.toEpochMilli();
            // 时段字段只用于半开相交过滤；原六字段及数据库返回的全局顺序才进入摘要。
            var selected=new ArrayList<Map<String,Object>>();
            for(var fact:facts)if(fact.candidate().start()<end&&fact.candidate().end()>start)selected.add(fact.fields());
            return DynamicElectricityRepository.hash(DynamicElectricityRepository.encode(selected));
        }
    }

    private CurrentReviewedDayVersionRead() { }

    static Optional<DaySnapshot> read(DynamicElectricityRepository repository,String building,Instant from,Instant to,
            Supplier<Duration> remaining) {
        return read(repository,building,from,to,remaining,DEFAULT_LIMITS);
    }

    static Optional<DaySnapshot> read(DynamicElectricityRepository repository,String building,Instant from,Instant to,
            Supplier<Duration> remaining,Limits limits) {
        Objects.requireNonNull(repository);Objects.requireNonNull(building);Objects.requireNonNull(remaining);
        Objects.requireNonNull(limits);
        if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(1))>0)
            return Optional.empty();
        var retained=new ArrayList<Fact>();var retainedIds=new HashSet<String>();
        String cursor=null;int scanned=0;long retainedBytes=0;
        while(true) {
            available(remaining);
            int pageLimit=Math.min(limits.metadataPageSize(),limits.maximumCandidates()-scanned);
            // Never jump over the prefix validated by legacy 1000-row pages before cap fallback.
            // At the boundary the final cap page is still discarded without a sentinel query.
            if(limits.metadataPageSize()>DEFAULT_LIMITS.metadataPageSize()) {
                int checkedPrefix=((limits.maximumCandidates()-1)/DEFAULT_LIMITS.metadataPageSize())
                        *DEFAULT_LIMITS.metadataPageSize();
                if(scanned<checkedPrefix)pageLimit=Math.min(pageLimit,checkedPrefix-scanned);
            }
            var page=repository.currentReviewedDayCandidates(building,from,to,cursor,pageLimit,remaining);
            if(page==null||page.size()>pageLimit)throw integrity();
            scanned=Math.addExact(scanned,page.size());
            // 满页恰好到达上限时也不能证明完整，保守放弃；不额外读取哨兵行突破候选上限。
            if(scanned>=limits.maximumCandidates())return Optional.empty();
            var candidates=new LinkedHashMap<String,Candidate>();
            for(var candidate:page) {
                available(remaining);
                if(candidate==null||candidate.id()==null||candidate.id().equals(cursor)
                        ||candidate.start()>=to.toEpochMilli()||candidate.end()<=from.toEpochMilli()
                        ||candidates.put(candidate.id(),candidate)!=null)throw integrity();
            }
            var ids=List.copyOf(candidates.keySet());
            for(int offset=0;offset<ids.size();offset+=limits.bodyPageSize()) {
                available(remaining);
                var batch=ids.subList(offset,Math.min(ids.size(),offset+limits.bodyPageSize()));
                var rows=repository.currentReviewedDayBodies(building,from,to,batch,remaining);
                if(rows==null||rows.size()>batch.size())throw integrity();
                int previous=-1;
                for(var fact:rows) {
                    available(remaining);
                    if(fact==null||fact.candidate()==null)throw integrity();
                    var candidate=fact.candidate();int position=batch.indexOf(candidate.id());
                    if(position<=previous||!candidate.equals(candidates.get(candidate.id()))||!retainedIds.add(candidate.id())
                            ||fact.fields().size()!=6||!fact.fields().keySet().stream().map(key->key.toLowerCase(Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet()).equals(Set.of("fact_id","point_id","reading_json",
                                    "normalized_quantity","revision","reviewed_at"))
                            ||!Objects.equals(fact.fields().get("fact_id"),candidate.id()))throw integrity();
                    previous=position;
                    Long bytes=fact.bytes();Object json=fact.fields().get("reading_json");
                    if(bytes!=null&&bytes<0)throw integrity();
                    if(bytes!=null&&bytes>limits.maximumRowBytes())return Optional.empty();
                    if((bytes==null)!=(json==null)||(json!=null&&!(json instanceof String)))throw integrity();
                    if(json instanceof String text&&text.getBytes(StandardCharsets.UTF_8).length!=bytes)throw integrity();
                    retainedBytes=Math.addExact(retainedBytes,bytes==null?0:bytes);
                    if(retainedBytes>limits.maximumBytes()||retained.size()>=limits.maximumRows())return Optional.empty();
                    retained.add(fact);
                }
            }
            if(page.size()<pageLimit)break;
            cursor=page.getLast().id();
        }
        available(remaining);
        return Optional.of(new DaySnapshot(from,to,retained));
    }

    static Duration available(Supplier<Duration> remaining) {
        if(Thread.currentThread().isInterrupted())throw new QueryTimeoutException("Current reviewed day read interrupted");
        Duration left=remaining.get();
        if(left==null||left.isNegative()||left.compareTo(Duration.ofMillis(1))<0)
            throw new QueryTimeoutException("Current reviewed day budget is invalid or expired");
        return left;
    }

    /** JDBC 超时只有整秒；执行前不足一秒必须停止，不能向上取整或回到没有该保护的旧查询。 */
    static Duration executionAvailable(Supplier<Duration> remaining) {
        Duration left=available(remaining);
        if(left.compareTo(Duration.ofSeconds(1))<0)
            throw new QueryTimeoutException("Current reviewed day has less than one second before execution");
        return left;
    }

    /** 每条 SQL 独立计时，并服从调用方已有期限；查询间的处理时间不构成新的 GET 总期限。 */
    static Supplier<Duration> statementBudget(Supplier<Duration> remaining,LongSupplier nanoTime) {
        Objects.requireNonNull(remaining);Objects.requireNonNull(nanoTime);
        long started=nanoTime.getAsLong();
        Duration initial=available(remaining);
        long maximumNanos=(initial.compareTo(MAX_STATEMENT_TIME)<0?initial:MAX_STATEMENT_TIME).toNanos();
        return ()->{
            Duration caller=available(remaining);
            long elapsed=Math.max(0,nanoTime.getAsLong()-started);
            Duration statement=Duration.ofNanos(Math.max(0,maximumNanos-elapsed));
            return statement.compareTo(caller)<0?statement:caller;
        };
    }

    static IllegalStateException integrity() {
        return new IllegalStateException("Current reviewed day source snapshot is inconsistent");
    }
}
