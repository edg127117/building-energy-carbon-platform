package com.platform.relation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
/** 旧资产写入口共享的关系治理旁路保护。 */
public class RelationGovernanceGuard {
    private final RelationGovernanceRepository repository;

    /** 治理模式下，新资产的结构归属必须先进入关系草稿，旧接口不能直接创建。 */
    public void requireLegacyForStructuralCreate(String buildingId) {
        if (repository.isGoverned(buildingId)) {
            throw RelationErrors.error(409, RelationErrors.GOVERNANCE_REQUIRED,
                    "该建筑已启用关系治理，请通过关系版本维护结构归属");
        }
    }

    /** 相同值属于普通属性更新；只有实际改变旧投影关系时才拒绝。 */
    public void rejectChangedProjection(String buildingId, Object currentValue, Object requestedValue) {
        if (repository.isGoverned(buildingId) && !Objects.equals(currentValue, requestedValue)) {
            throw RelationErrors.error(409, RelationErrors.GOVERNANCE_REQUIRED,
                    "该关系字段由当前有效关系版本治理，不能通过资产接口直接修改");
        }
    }

    /** 活动草稿、待审核、已批准或当前有效版本引用的对象都不能被旧入口删除。 */
    public void requireDeletable(String objectType, String objectId) {
        if (repository.referencedByLiveVersion(objectType, objectId)) {
            throw RelationErrors.error(409, RelationErrors.REFERENCE_CONFLICT,
                    "资产仍被活动或有效关系版本引用，不能删除");
        }
    }

    public void requireBuildingDeletable(String buildingId) {
        if (repository.relationModelExists(buildingId)) {
            throw RelationErrors.error(409, RelationErrors.REFERENCE_CONFLICT,
                    "建筑已有关系治理历史，不能通过普通资产入口删除");
        }
    }
}
