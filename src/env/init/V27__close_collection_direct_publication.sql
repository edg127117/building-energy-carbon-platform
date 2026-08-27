-- 扩展采集领域审核目标，使正式来源和别名的停启也复用原领域审核表。

ALTER TABLE `biz_collection_review_request`
    DROP CHECK `chk_collection_review_target_type`;

ALTER TABLE `biz_collection_review_request`
    ADD CONSTRAINT `chk_collection_review_target_type`
    CHECK (`target_type` IN (
        'SOURCE_ACTIVATION',
        'SOURCE_DEACTIVATION',
        'ALIAS_ACTIVATION',
        'ALIAS_DEACTIVATION',
        'POLICY_VERSION'
    ));
