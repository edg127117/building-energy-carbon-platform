package com.platform.audit.query;

import java.util.List;

/** 已按跨来源稳定顺序合并的有界页面；下一页继续使用不承载权限的服务端游标。 */
public record AuditQueryPage(List<AuditEvidenceRecord> items, String nextCursor) {
}
