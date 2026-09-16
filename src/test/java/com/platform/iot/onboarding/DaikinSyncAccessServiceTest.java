package com.platform.iot.onboarding;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.sync.DaikinDirectorySyncService;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaikinSyncAccessServiceTest {
    private final BuildingScopeService buildings = mock(BuildingScopeService.class);
    private final SysMenuMapper menus = mock(SysMenuMapper.class);
    private final BackendDutyService duties = mock(BackendDutyService.class);
    private final DaikinDirectorySyncService sync = mock(DaikinDirectorySyncService.class);
    private JdbcTemplate jdbc;
    private DaikinSyncAccessService service;
    private static final Set<String> OPS = Set.of("BUILDING_OWNER");

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:sync-access-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE biz_daikin_project_mapping(source_id VARCHAR(200), building_id VARCHAR(32))");
        jdbc.update("INSERT INTO biz_daikin_project_mapping VALUES ('source-a','BLD001'),('source-b','BLD002')");
        service = new DaikinSyncAccessService(jdbc, buildings, menus, duties, sync);
        var menu = new SysMenu();
        menu.setMenuType("C");
        menu.setPath("/operations/devices/pendingDevices");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of(menu));
        when(buildings.getAccessibleBuildingIds(7L, OPS)).thenReturn(Set.of("BLD001"));
    }

    @Test
    void requiresMenuAndBuildingBeforeCallingSync() {
        assertThatThrownBy(() -> service.request(7L, OPS, "source-b")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(7L, OPS, "unmapped", "job")).isInstanceOf(BusinessException.class);
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.request(7L, OPS, "source-a")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sync, duties);
    }

    @Test
    void usesExistingSubmitDutyAndDoesNotExposeSourceWideDeviceData() {
        var job = mock(DaikinDirectorySyncService.JobView.class);
        when(job.jobId()).thenReturn("job");
        when(job.sourceId()).thenReturn("source-a");
        when(job.status()).thenReturn("QUEUED");
        when(sync.request("source-a", 7L)).thenReturn(job);
        assertThat(service.request(7L, OPS, "source-a"))
                .isEqualTo(new DaikinSyncAccessService.SyncJobView("job", "source-a", "QUEUED", 0, null));
        verify(duties).requireDuty(7L, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        doThrow(new BusinessException(403, "无提交职责")).when(duties)
                .requireDuty(7L, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        assertThatThrownBy(() -> service.request(7L, OPS, "source-a")).isInstanceOf(BusinessException.class);
        verify(sync, times(1)).request(anyString(), anyLong());
    }

    @Test
    void rechecksBuildingMappingWhenReadingExistingJob() {
        jdbc.update("UPDATE biz_daikin_project_mapping SET building_id='BLD002' WHERE source_id='source-a'");
        assertThatThrownBy(() -> service.get(7L, OPS, "source-a", "job")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(sync);
    }

    @Test
    void adminMayBootstrapUnmappedSourceButStillRequiresSubmitDuty() {
        when(sync.request("new-source", 1L)).thenThrow(new BusinessException(503, "未配置来源客户端"));
        assertThatThrownBy(() -> service.request(1L, Set.of("PLATFORM_ADMIN"), "new-source"))
                .isInstanceOf(BusinessException.class).hasMessage("未配置来源客户端");
        verify(duties).requireDuty(1L, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        verifyNoInteractions(menus, buildings);
    }
}
