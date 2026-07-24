package com.platform.iot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.framework.common.Result;
import com.platform.iot.core.model.entity.IotDevice;
import com.platform.iot.service.IotDeviceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import com.platform.security.FormalRole;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 IoT 设备台账接口。
 *
 * <p>查询接口同时受角色和建筑范围约束：平台管理员不过滤建筑，建筑业主和能效管理方
 * 只能看到已授权建筑下的设备。新增、删除和批量初始化仅允许平台管理员执行。</p>
 */
@RestController
@RequestMapping("/device")
public class IotDeviceController {

    @Autowired
    private IotDeviceService deviceService;

    @Autowired
    private BuildingScopeService buildingScopeService;

    /** 分页查询当前用户建筑范围内的设备。 */
    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<Page<IotDevice>> getDeviceList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) Integer status,
            Authentication authentication) {

        LambdaQueryWrapper<IotDevice> wrapper = new LambdaQueryWrapper<>();
        if (deviceId != null && !deviceId.isBlank()) {
            wrapper.eq(IotDevice::getDeviceId, deviceId);
        }
        if (status != null) {
            wrapper.eq(IotDevice::getStatus, status);
        }
        // null=平台管理员全部建筑；空集合=普通用户没有建筑；非空集合=按 building_id 过滤。
        var buildingIds = buildingScopeService.getAccessibleBuildingIds(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication));
        if (buildingIds != null && buildingIds.isEmpty()) {
            return Result.success(new Page<>(page, pageSize));
        }
        if (buildingIds != null) wrapper.in(IotDevice::getBuildingId, buildingIds);
        wrapper.orderByAsc(IotDevice::getDeviceId);

        Page<IotDevice> pageResult = new Page<>(page, pageSize);
        deviceService.page(pageResult, wrapper);
        return Result.success(pageResult);
    }

    /** 按当前用户建筑范围统计在线、离线和故障设备数量。 */
    @GetMapping("/status-summary")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<Map<String, Long>> getStatusSummary(Authentication authentication) {
        var buildingIds = buildingScopeService.getAccessibleBuildingIds(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication));
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("online", countByStatus(1, buildingIds));
        summary.put("offline", countByStatus(0, buildingIds));
        summary.put("fault", countByStatus(2, buildingIds));
        return Result.success(summary);
    }

    /** 平台管理员新增设备台账。 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<String> addDevice(@RequestBody IotDevice device) {
        deviceService.save(device);
        return Result.success("设备添加成功");
    }

    /** 平台管理员删除设备台账。 */
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<String> deleteDevice(@PathVariable Long id) {
        deviceService.removeById(id);
        return Result.success("设备删除成功");
    }

    /** 演示环境批量初始化测试设备，生产环境不应对普通角色开放。 */
    @PostMapping("/batch-init")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<String> batchInit(@RequestParam(defaultValue = "10000") int count) {
        List<IotDevice> devices = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            IotDevice d = new IotDevice();
            d.setDeviceId("meter-" + String.format("%04d", i));
            d.setDeviceName("测试设备-" + String.format("%04d", i));
            d.setDeviceType(1);
            d.setLocation("测试区域-" + ((i - 1) / 1000 + 1));
            devices.add(d);
        }
        deviceService.saveBatch(devices, 500);
        return Result.success("已初始化 " + count + " 台测试设备");
    }

    /** 使用与设备列表相同的建筑范围约定进行状态统计。 */
    private long countByStatus(int status, java.util.Set<String> buildingIds) {
        if (buildingIds != null && buildingIds.isEmpty()) return 0;
        LambdaQueryWrapper<IotDevice> wrapper = new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getStatus, status);
        if (buildingIds != null) wrapper.in(IotDevice::getBuildingId, buildingIds);
        return deviceService.count(wrapper);
    }
}
