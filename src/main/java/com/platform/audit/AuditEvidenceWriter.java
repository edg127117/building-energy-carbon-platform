package com.platform.audit;

/** 只追加规范化审计证据，不向调用方暴露任意表名或 SQL。 */
public interface AuditEvidenceWriter {
    void append(AuditEvidence evidence);
}
