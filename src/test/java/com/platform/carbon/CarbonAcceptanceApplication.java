package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.cache.BuildingScopeCacheService;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.api.CarbonManagementController;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.security.JwtUserPrincipal;
import com.platform.system.mapper.SysUserBuildingMapper;
import com.platform.system.service.BuildingScopeService;
import com.platform.system.service.impl.BuildingScopeServiceImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仅在测试 classpath 启动的隔离碳子系统：真实 HTTP、事务、MySQL、审计和职责服务。
 * 身份由本机测试头注入，Redis 缓存关闭，活动输入由已落库的合成快照替代；不覆盖登录、设备链路。
 */
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
        RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class}, excludeName = {
        "org.redisson.spring.starter.RedissonAutoConfigurationV2",
        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
@EnableTransactionManagement
@EnableMethodSecurity
@EnableScheduling
@Import({CarbonCalculationCore.class, CarbonAuthorization.class,
        CarbonCalculationRepository.class, CarbonCalculationPersistence.class,
        CarbonCalculationService.class, CarbonRuleRepository.class, CarbonRuleService.class,
        CarbonRecalculationRepository.class, CarbonRecalculationPersistence.class,
        CarbonRecalculationService.class, CarbonManagementController.class,
        AuditGovernanceProperties.class, JdbcBackendDutyService.class,
        AuditSummarySanitizer.class, JdbcSecurityAuditEvidenceWriter.class,
        SecurityAuditService.class, GlobalExceptionHandler.class, Control.class})
public class CarbonAcceptanceApplication {
    @Bean CarbonProperties carbonProperties() { return new CarbonProperties(); }

    public static void main(String[] args) {
        if (!"true".equals(System.getenv("CARBON_ACCEPTANCE_ISOLATED"))) {
            throw new IllegalStateException("Only an explicitly isolated acceptance environment is allowed");
        }
        SpringApplication.run(CarbonAcceptanceApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    CarbonAcceptanceDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("CARBON_ACCEPTANCE_URL"));
        config.setUsername(System.getenv().getOrDefault("CARBON_ACCEPTANCE_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("CARBON_ACCEPTANCE_PASSWORD", ""));
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(4);
        config.setConnectionTimeout(2_000);
        config.setPoolName("carbon-acceptance");
        return new CarbonAcceptanceDataSource(new HikariDataSource(config));
    }

    @Bean(name = {"mysqlJdbcTemplate", "jdbcTemplate"})
    JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        SqlSessionFactory result = factory.getObject();
        result.getConfiguration().addMapper(SysUserBuildingMapper.class);
        return result;
    }

    @Bean
    BuildingScopeService buildingScopeService(SqlSessionFactory factory, ObjectMapper mapper) {
        BuildingScopeCacheService noRedis = new BuildingScopeCacheService(null, mapper) {
            @Override public Set<String> get(Long userId) { return null; }
            @Override public void set(Long userId, Set<String> ids) { }
            @Override public void evict(Long userId) { }
        };
        return new BuildingScopeServiceImpl(new SqlSessionTemplate(factory)
                .getMapper(SysUserBuildingMapper.class), noRedis);
    }

    @Bean
    CarbonActivityInputPort activityInput(JdbcTemplate jdbc) {
        return (building, period, start, end, limit) -> {
            List<Map<String, Object>> controls = jdbc.queryForList(
                    "SELECT * FROM acceptance_activity_control WHERE building_id=?", building);
            if (!controls.isEmpty()) {
                Map<String, Object> control = controls.getFirst();
                jdbc.update("""
                        UPDATE acceptance_activity_control SET read_count=read_count+1,
                          read_in_transaction=? WHERE building_id=?
                        """, TransactionSynchronizationManager.isActualTransactionActive(), building);
                long delay = ((Number) control.get("delay_ms")).longValue();
                try { TimeUnit.MILLISECONDS.sleep(delay); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test input interrupted", failure);
                }
                if (((Number) control.get("fail_read")).intValue() != 0) {
                    throw new IllegalStateException("Injected isolated activity failure");
                }
            }
            List<ActivitySegment> values = jdbc.query("""
                    SELECT * FROM acceptance_activity WHERE building_id=?
                      AND start_at>=? AND end_at<=? ORDER BY start_at, snapshot_id LIMIT ?
                    """, (rs, row) -> new ActivitySegment(rs.getString("snapshot_id"), building,
                    period, rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                    "Asia/Shanghai", rs.getString("energy_item"), rs.getBigDecimal("quantity"),
                    rs.getString("unit_code"), "LOCKED_COMPLETE", "COMPLETE",
                    ResultNature.valueOf(rs.getString("nature")),
                    CarbonCalculationCore.sha256(rs.getString("snapshot_id") + rs.getBigDecimal("quantity"))),
                    building, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), limit + 1);
            if (values.size() > limit) throw CarbonErrors.error(409, CarbonErrors.LIMIT_EXCEEDED,
                    "合成上游快照超过硬上限");
            return values;
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request,
                            HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                        String header = request.getHeader("X-Acceptance-User");
                        long user = header == null ? 9001 : Long.parseLong(header);
                        String role = user == 9003 ? "ENERGY_MANAGER" : "PLATFORM_ADMIN";
                        var auth = new UsernamePasswordAuthenticationToken(
                                new JwtUserPrincipal(user, "synthetic-" + user), null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        try { chain.doFilter(request, response); }
                        finally { SecurityContextHolder.clearContext(); }
                    }
                }, AnonymousAuthenticationFilter.class).build();
    }
}

/** 测试控制面不进入生产产物；在真实短事务提交之后提供可重复的断电边界。 */
@RestController
@org.springframework.boot.test.context.TestComponent
@RequestMapping("/__acceptance")
class Control {
    private final CarbonRecalculationService service;
    private final CarbonRecalculationPersistence persistence;
    private final CarbonRecalculationRepository repository;
    private final CarbonProperties properties;
    private final CarbonAcceptanceDataSource source;
    private final CarbonCalculationService calculation;
    private final Map<String, RecalculationItem> capturedItems = new ConcurrentHashMap<>();
    private final Map<String, String> capturedTokens = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger maxHttpBusy = new AtomicInteger();
    private final AtomicInteger maxDbActive = new AtomicInteger();
    private final AtomicInteger maxDbPending = new AtomicInteger();
    private volatile org.apache.coyote.AbstractProtocol<?> protocol;

    Control(CarbonRecalculationService service, CarbonRecalculationPersistence persistence,
            CarbonRecalculationRepository repository, CarbonProperties properties,
            CarbonAcceptanceDataSource source, CarbonCalculationService calculation) {
        this.service = service; this.persistence = persistence; this.repository = repository;
        this.properties = properties; this.source = source;
        this.calculation = calculation;
        sampler.scheduleAtFixedRate(this::sample, 0, 10, TimeUnit.MILLISECONDS);
    }

    @EventListener
    void initialized(WebServerInitializedEvent event) {
        protocol = (org.apache.coyote.AbstractProtocol<?>) ((TomcatWebServer) event.getWebServer())
                .getTomcat().getConnector().getProtocolHandler();
    }

    private void sample() {
        maxDbActive.accumulateAndGet(source.pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
        maxDbPending.accumulateAndGet(source.pool.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
        if (protocol != null) {
            if (protocol.getExecutor() instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor executor) {
                maxHttpBusy.accumulateAndGet(executor.getActiveCount(), Math::max);
            } else if (protocol.getExecutor() instanceof ThreadPoolExecutor executor) {
                maxHttpBusy.accumulateAndGet(executor.getActiveCount(), Math::max);
            }
        }
    }

    @GetMapping("/metrics")
    Map<String, Object> metrics() {
        sample();
        Map<String, Object> values = new LinkedHashMap<>(source.metrics());
        values.put("pid", ProcessHandle.current().pid());
        values.put("httpMax", protocol.getMaxThreads()); values.put("httpBusyPeak", maxHttpBusy.get());
        values.put("httpExecutor", protocol.getExecutor().getClass().getName());
        values.put("dbMax", source.pool.getMaximumPoolSize()); values.put("dbActivePeak", maxDbActive.get());
        values.put("dbPendingPeak", maxDbPending.get());
        values.put("dbActiveNow", source.pool.getHikariPoolMXBean().getActiveConnections());
        values.put("dbPendingNow", source.pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
        return values;
    }

    @PostMapping("/reset-metrics")
    void reset() { source.reset(); maxHttpBusy.set(0); maxDbActive.set(0); maxDbPending.set(0); }

    @PostMapping("/scheduler/{enabled}")
    void scheduler(@PathVariable boolean enabled) { properties.setRecalculationEnabled(enabled); }

    @PostMapping("/step/{action}")
    Object step(@PathVariable String action) {
        return switch (action) {
            case "analyze" -> { service.analyzeOne(); yield Map.of("done", true); }
            case "execute" -> { service.executeOne(); yield Map.of("done", true); }
            case "tick" -> {
                properties.setRecalculationEnabled(true);
                try { service.scheduledWork(); } finally { properties.setRecalculationEnabled(false); }
                yield Map.of("done", true);
            }
            case "claim-change" -> Optional.ofNullable(persistence.claimChange());
            case "claim-batch" -> Optional.ofNullable(persistence.claimBatch());
            default -> throw new IllegalArgumentException(action);
        };
    }

    @PostMapping("/item/{itemId}/start")
    boolean start(@PathVariable String itemId) {
        RecalculationItem item = repository.findItem(itemId);
        capturedItems.put(itemId, item);
        String token = repository.findBatch(item.batchId()).leaseToken();
        capturedTokens.put(itemId, token);
        return persistence.startItem(itemId, item.batchId(), token);
    }

    @PostMapping("/item/{itemId}/fail")
    void fail(@PathVariable String itemId) {
        persistence.failItem(capturedItems.get(itemId), "ACCEPTANCE_STALE_WORKER", "stale worker probe",
                capturedTokens.get(itemId));
    }

    @PostMapping("/item/{itemId}/compute")
    Map<String, String> compute(@PathVariable String itemId) {
        RecalculationItem item = repository.findItem(itemId);
        var batch = repository.findBatch(item.batchId());
        persistence.startItem(itemId, item.batchId(), batch.leaseToken());
        var candidate = calculation.runCandidate(item.buildingId(), item.accountingYear(),
                batch.resultNature(), item.oldCalculationBatchId(), "recalc:" + itemId + ':' + item.retryCount());
        persistence.succeedItem(item, candidate.batch().batchId(), batch.leaseToken());
        return Map.of("candidateId", candidate.batch().batchId());
    }

    @jakarta.annotation.PreDestroy
    void close() { sampler.shutdownNow(); }
}
