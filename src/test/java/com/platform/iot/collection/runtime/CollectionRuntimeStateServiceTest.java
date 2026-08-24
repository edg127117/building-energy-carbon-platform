package com.platform.iot.collection.runtime;

import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.iot.collection.mapper.BizCollectionPolicyMapper;
import com.platform.iot.collection.mapper.BizCollectionPolicyVersionMapper;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.CollectionModels.RuntimeApplyStatus;
import com.platform.iot.collection.model.entity.BizDataSource;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionRuntimeStateServiceTest {
    @Mock private BizDataSourceMapper sourceMapper;
    @Mock private BizPointAliasMapper aliasMapper;
    @Mock private BizCollectionPolicyMapper policyMapper;
    @Mock private BizCollectionPolicyVersionMapper versionMapper;
    @Mock private MySqlDataPointConfigProvider pointProvider;

    @Test
    void refreshFailureKeepsPublishedRevisionDistinctAndManualRetryRecovers() {
        BizDataSource source = new BizDataSource();
        source.setSourceId("SOURCE001");
        source.setStatus("ENABLED");
        source.setRuntimeRevision(2L);
        when(sourceMapper.selectList(any()))
                .thenThrow(new IllegalStateException("mysql unavailable"))
                .thenReturn(List.of(source));
        when(aliasMapper.selectList(any())).thenReturn(List.of());
        when(policyMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.selectList(any())).thenReturn(List.of());
        doNothing().when(pointProvider).refreshAllOrThrow();
        CollectionRuntimeStateService service = new CollectionRuntimeStateService(
                sourceMapper, aliasMapper, policyMapper, versionMapper, pointProvider);

        service.refreshAfterCommit("SOURCE001", 2L);
        assertThat(service.state("SOURCE001", "ENABLED", 2L).applyStatus())
                .isEqualTo(RuntimeApplyStatus.REFRESH_FAILED);

        assertThat(service.refreshAll()).isTrue();
        assertThat(service.state("SOURCE001", "ENABLED", 2L).applyStatus())
                .isEqualTo(RuntimeApplyStatus.APPLIED);
        assertThat(service.state("SOURCE001", "ENABLED", 2L).appliedRuntimeRevision()).isEqualTo(2L);
    }
}
