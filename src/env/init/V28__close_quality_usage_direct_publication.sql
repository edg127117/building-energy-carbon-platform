-- 新请求只能通过质量策略领域审核状态机发布；历史直接发布事实保持原值、只读留痕。

ALTER TABLE `biz_quality_usage_review_request`
    ADD COLUMN `legacy_direct_publish` TINYINT NOT NULL DEFAULT 0 AFTER `review_mode`;

UPDATE `biz_quality_usage_review_request`
SET `legacy_direct_publish` = 1
WHERE `review_mode` = 'DIRECT_PUBLISH';

ALTER TABLE `biz_quality_usage_review_request`
    DROP CHECK `chk_quality_usage_review_mode`;

ALTER TABLE `biz_quality_usage_review_request`
    ADD CONSTRAINT `chk_quality_usage_review_mode`
    CHECK (
        `review_mode` = 'NORMAL'
        OR (`review_mode` = 'DIRECT_PUBLISH' AND `legacy_direct_publish` = 1)
    );
