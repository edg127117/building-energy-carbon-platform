package com.platform.iot.onboarding;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.iot.daikin.sync.DaikinDirectorySyncService;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 同步入口复用待接入菜单、建筑范围和提交职责，不增加角色或自动绑定权限。
 * 厂家分页按来源执行；运维只能触发与自己建筑有映射的来源，响应不含全来源设备数量或设备信息。
 * 设备详情仍由既有范围受控的待接入查询提供，首次尚未映射的来源仅管理员可触发。
 */
@Service
public class DaikinSyncAccessService {
    private static final Set<String> MENUS = Set.of("/system/device-onboarding",
            "/configuration/ingestion/pendingDevices", "/operations/devices/pendingDevices");
    private final JdbcTemplate jdbc;
    private final BuildingScopeService buildings;
    private final SysMenuMapper menus;
    private final BackendDutyService duties;
    private final DaikinDirectorySyncService sync;

    public DaikinSyncAccessService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            BuildingScopeService buildings, SysMenuMapper menus, BackendDutyService duties,
            DaikinDirectorySyncService sync) {
        this.jdbc = jdbc;
        this.buildings = buildings;
        this.menus = menus;
        this.duties = duties;
        this.sync = sync;
    }

    public SyncJobView request(Long userId, Set<String> roles, String sourceId) {
        requireAccess(userId, roles, sourceId);
        duties.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        return view(sync.request(sourceId, userId));
    }

    public SyncJobView get(Long userId, Set<String> roles, String sourceId, String jobId) {
        requireAccess(userId, roles, sourceId);
        return view(sync.get(sourceId, jobId));
    }

    public PageResponse<SyncJobView> list(Long userId, Set<String> roles, int page, int size) {
        if (userId == null || roles == null) throw forbidden();
        if (roles.contains("PLATFORM_ADMIN")) return map(sync.listAll(page, size));
        requireVisibleMenu(userId);
        Set<String> allowed = buildings.getAccessibleBuildingIds(userId, roles);
        return map(sync.listForBuildings(allowed, page, size));
    }

    public List<SourceOption> sources(Long userId, Set<String> roles) {
        if (userId == null || roles == null) throw forbidden();
        if (roles.contains("PLATFORM_ADMIN")) {
            return availableSources("SELECT source_id,source_name FROM biz_daikin_source ORDER BY source_name,source_id",
                    new Object[0]);
        }
        requireVisibleMenu(userId);
        Set<String> allowed = buildings.getAccessibleBuildingIds(userId, roles);
        if (allowed == null || allowed.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(allowed.size(), "?"));
        return availableSources("""
                SELECT DISTINCT s.source_id,s.source_name FROM biz_daikin_source s
                JOIN biz_daikin_project_mapping m ON m.source_id=s.source_id
                WHERE m.building_id IN (%s)
                ORDER BY s.source_name,s.source_id
                """.formatted(placeholders), allowed.toArray());
    }

    private List<SourceOption> availableSources(String sql, Object[] parameters) {
        return jdbc.query(sql, (rs, row) -> new SourceOption(rs.getString(1), rs.getString(2)), parameters)
                .stream().filter(source -> sync.isAvailable(source.sourceId())).toList();
    }

    private void requireAccess(Long userId, Set<String> roles, String sourceId) {
        if (userId == null || roles == null) throw forbidden();
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 200) {
            throw new BusinessException(400, "DAIKIN_SYNC_INVALID_REQUEST", "来源身份无效");
        }
        if (roles.contains("PLATFORM_ADMIN")) return;
        requireVisibleMenu(userId);
        Set<String> allowed = buildings.getAccessibleBuildingIds(userId, roles);
        // 每次读取当前映射和授权，不把创建任务时的权限当作永久查询凭证。
        if (allowed == null || allowed.isEmpty() || jdbc.queryForList(
                "SELECT building_id FROM biz_daikin_project_mapping WHERE source_id=?",
                String.class, sourceId).stream().noneMatch(allowed::contains)) throw forbidden();
    }

    private static SyncJobView view(DaikinDirectorySyncService.JobView job) {
        return new SyncJobView(job.jobId(), job.sourceId(), job.status(), job.attempts(), job.errorCode(),
                job.createdAt(), job.updatedAt(), job.completedAt());
    }

    private void requireVisibleMenu(Long userId) {
        if (menus.selectVisibleMenusByUserId(userId).stream()
                .noneMatch(menu -> "C".equals(menu.getMenuType()) && MENUS.contains(menu.getPath()))) {
            throw forbidden();
        }
    }

    private static PageResponse<SyncJobView> map(PageResponse<DaikinDirectorySyncService.JobView> page) {
        return new PageResponse<>(page.page(), page.size(), page.total(), page.items().stream()
                .map(DaikinSyncAccessService::view).toList());
    }

    private static BusinessException forbidden() {
        return new BusinessException(403, "DAIKIN_SYNC_FORBIDDEN", "无权访问该来源同步任务");
    }

    public record SyncJobView(String jobId, String sourceId, String status, int attempts, String errorCode,
                              long createdAt, long updatedAt, Long completedAt) { }
    public record SourceOption(String sourceId, String sourceName) { }
}
