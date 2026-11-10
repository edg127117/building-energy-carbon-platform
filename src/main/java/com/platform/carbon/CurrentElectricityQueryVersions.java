package com.platform.carbon;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** One authorized GET's current-source cache; it never crosses the caller's existing RR snapshot. */
final class CurrentElectricityQueryVersions implements AutoCloseable {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String REVIEWED="REVIEWED_INTERVAL";
    private final DynamicElectricityRepository repository;
    private final long user;
    private final Set<String> roles;
    private final String building;
    private final DataSource dataSource;
    private final Object snapshotResource;
    private final Set<LocalDate> eligibleDays;
    private final Supplier<Duration> remaining;
    private LocalDate retainedDay;
    private CurrentReviewedDayVersionRead.DaySnapshot snapshot;
    private boolean disabled;
    private boolean closed;

    CurrentElectricityQueryVersions(DynamicElectricityRepository repository,long user,Collection<String> roles,
            String building,List<DynamicElectricityRepository.ChartResult> results) {
        this(repository,user,roles,building,results,()->callerRemaining(repository.currentReviewedDayDataSource()));
    }

    CurrentElectricityQueryVersions(DynamicElectricityRepository repository,long user,Collection<String> roles,
            String building,List<DynamicElectricityRepository.ChartResult> results,Supplier<Duration> remaining) {
        this.repository=Objects.requireNonNull(repository);
        this.user=user;this.roles=Set.copyOf(roles);this.building=Objects.requireNonNull(building);
        this.remaining=Objects.requireNonNull(remaining);
        this.dataSource=repository.currentReviewedDayDataSource();
        this.snapshotResource=qualifiedResource();
        this.disabled=snapshotResource==null;
        var hours=new HashMap<LocalDate,Integer>();
        if(!disabled)for(var result:results) {
            if(!"HOUR".equals(result.grain()) || !REVIEWED.equals(result.sourceKind())
                    || !hour(result.start(),result.end()))continue;
            var local=result.start().atZone(ZONE);
            hours.merge(local.toLocalDate(),1<<local.getHour(),(a,b)->a|b);
        }
        eligibleDays=new HashSet<>();
        hours.forEach((day,mask)->{if(Integer.bitCount(mask)>1)eligibleDays.add(day);});
    }

    String version(long user,Collection<String> roles,String building,Instant from,Instant to,String sourceKind) {
        if(closed)throw new IllegalStateException("Current source query cache is closed");
        if(this.user!=user || !this.roles.equals(Set.copyOf(roles)) || !this.building.equals(building))
            throw new IllegalStateException("Current source query context changed");
        // A direct call bypassing Spring, a joined non-RR transaction or REQUIRES_NEW is never eligible.
        if(!disabled && qualifiedResource()!=snapshotResource)disable();
        LocalDate day=from.atZone(ZONE).toLocalDate();
        if(retainedDay!=null && !retainedDay.equals(day))releaseDay();
        if(disabled || !REVIEWED.equals(sourceKind) || !hour(from,to) || !eligibleDays.contains(day))
            return repository.electricityVersion(building,from,to,sourceKind);
        // Cache hits still obey a real caller/transaction deadline; only the optional SQL guard is renewed.
        CurrentReviewedDayVersionRead.available(remaining);
        if(!day.equals(retainedDay)) {
            releaseDay();retainedDay=day;
            // A cache limit is not a business-data limit: discard the entire optional day and use the old path.
            snapshot=repository.loadCurrentReviewedDay(building,day.atStartOfDay(ZONE).toInstant(),
                    day.plusDays(1).atStartOfDay(ZONE).toInstant(),remaining).orElse(null);
            if(snapshot==null)DynamicElectricityQueryObservation.count(DynamicElectricityQueryObservation.Count.DAY_FALLBACKS,1);
            if(qualifiedResource()!=snapshotResource)disable();
        }
        CurrentReviewedDayVersionRead.available(remaining);
        return snapshot==null?repository.electricityVersion(building,from,to,sourceKind):snapshot.version(from,to);
    }

    private Object qualifiedResource() {
        if(dataSource==null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        Connection.TRANSACTION_REPEATABLE_READ))return null;
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        return resource instanceof ConnectionHolder holder && holder.isSynchronizedWithTransaction()?resource:null;
    }

    private static Duration callerRemaining(DataSource dataSource) {
        // This is a per-statement ceiling, not a timer started when the GET began. Slow evidence
        // validation or rendering must not disable source reuse for the later days of a legal GET.
        Duration left=CurrentReviewedDayVersionRead.MAX_STATEMENT_TIME;
        Duration caller=CarbonCalculationDeadline.currentRemaining();
        if(caller!=null) {
            // The SQL guard has millisecond precision. A shorter real deadline is not an optional
            // cache-capacity fallback: the old query must not escape the caller's remaining limit.
            if(caller.compareTo(Duration.ofMillis(1))<0)throw CarbonCalculationDeadline.timeout();
            if(caller.compareTo(left)<0)left=caller;
        }
        Object resource=dataSource==null?null:TransactionSynchronizationManager.getResource(dataSource);
        if(resource instanceof ConnectionHolder holder && holder.hasTimeout()) {
            Duration transaction=Duration.ofMillis(holder.getTimeToLiveInMillis());
            if(transaction.compareTo(left)<0)left=transaction;
        }
        return left;
    }

    private static boolean hour(Instant from,Instant to) {
        if(from==null || to==null || !to.equals(from.plusSeconds(3600)))return false;
        var local=from.atZone(ZONE);
        return local.getMinute()==0 && local.getSecond()==0 && local.getNano()==0;
    }

    private void releaseDay(){snapshot=null;retainedDay=null;}
    private void disable(){releaseDay();eligibleDays.clear();disabled=true;}
    boolean hasRetainedDay(){return snapshot!=null;}
    @Override public void close(){releaseDay();eligibleDays.clear();closed=true;}
}
