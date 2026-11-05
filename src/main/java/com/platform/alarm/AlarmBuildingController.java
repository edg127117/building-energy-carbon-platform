package com.platform.alarm;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@RestController
@PreAuthorize("isAuthenticated()")
/** 报警页独立的建筑选择入口，菜单授权不附带资产维护权限。 */
public class AlarmBuildingController {
    private final JdbcTemplate jdbc;
    private final AlarmAccess access;
    private final BuildingScopeService scope;
    public AlarmBuildingController(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AlarmAccess access, BuildingScopeService scope) {
        this.jdbc = jdbc; this.access = access; this.scope = scope;
    }
    @GetMapping("/v1/alarms/buildings")
    public Result<List<Building>> buildings(Authentication auth, @RequestParam(defaultValue="live") String page) {
        String menu = switch (page) {
            case "live" -> "/operations/alarms/liveAlarms";
            case "history" -> "/operations/alarms/historyAlarms";
            case "rules" -> "/configuration/rules/alarmRules";
            default -> throw new com.platform.framework.exception.BusinessException("报警页面参数无效");
        };
        Long user = SecurityUser.userId(auth); Set<String> roles = SecurityUser.roles(auth);
        access.requireMenu(user, roles, menu, false);
        Set<String> ids = scope.getAccessibleBuildingIds(user, roles);
        if (ids != null && ids.isEmpty()) return Result.success(List.of());
        List<Object> args = new ArrayList<>();
        String filter = "";
        if (ids != null) {
            filter = " AND building_id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
            args.addAll(ids);
        }
        return Result.success(jdbc.query("SELECT building_id,building_name FROM biz_building WHERE del_flag=0" + filter + " ORDER BY building_name,building_id",
                (rs, row) -> new Building(rs.getString(1), rs.getString(2)), args.toArray()));
    }
    public record Building(String buildingId, String buildingName) { }
}
