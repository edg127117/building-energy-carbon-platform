package com.platform.iot.daikin.control;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import static com.platform.iot.daikin.control.DaikinControlContracts.BuildingOption;

@RestController
@PreAuthorize("isAuthenticated()")
/** 空调专用建筑入口：TEST 开放已对接内机范围，不扩展天气、电量或公共建筑权限。 */
public class DaikinControlBuildingController {
    private final JdbcTemplate jdbc;
    private final DaikinControlAccess access;
    private final BuildingScopeService scope;
    public DaikinControlBuildingController(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                          DaikinControlAccess access, BuildingScopeService scope) {
        this.jdbc = jdbc; this.access = access; this.scope = scope;
    }
    @GetMapping("/v1/hvac-control/buildings")
    public Result<List<BuildingOption>> buildings(Authentication authentication) {
        long user = SecurityUser.userId(authentication);
        access.requireAccount(user);
        if (access.testOpenControls()) {
            return Result.success(jdbc.query("""
                    SELECT DISTINCT b.building_id,b.building_name FROM building b
                    JOIN biz_equipment e ON e.building_id=b.building_id AND e.del_flag=0
                    JOIN biz_device_identity i ON i.equip_id=e.equip_id AND i.building_id=e.building_id
                      AND i.identity_type='DAIKIN_UNIT' AND i.status=1
                    JOIN biz_pending_device p ON p.bound_identity_id=i.identity_id AND p.status='BOUND'
                    JOIN biz_daikin_directory d ON d.pending_id=p.pending_id AND d.device_kind='INDOOR' AND d.missing=0
                    JOIN biz_daikin_project_mapping m ON m.source_id=d.source_id AND m.site_id=d.site_id AND m.building_id=e.building_id
                    WHERE b.del_flag=0 AND NOT EXISTS
                      (SELECT 1 FROM biz_test_building_retirement t WHERE t.building_id=b.building_id)
                    ORDER BY b.building_id
                    """, (r,n) -> new BuildingOption(r.getString(1),r.getString(2))));
        }
        var allowed = scope.getAccessibleBuildingIds(user, SecurityUser.roles(authentication));
        return Result.success(jdbc.query("""
                SELECT building_id,building_name FROM building b WHERE del_flag=0 AND NOT EXISTS
                (SELECT 1 FROM biz_test_building_retirement t WHERE t.building_id=b.building_id)
                ORDER BY building_id
                """,
                (r,n) -> new BuildingOption(r.getString(1),r.getString(2))).stream()
                .filter(b -> allowed == null || allowed.contains(b.buildingId())).toList());
    }
}
