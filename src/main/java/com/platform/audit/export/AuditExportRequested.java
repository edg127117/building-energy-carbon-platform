package com.platform.audit.export;

/** 创建事务提交后触发异步生成；任务主键是唯一允许跨线程传递的内容。 */
public record AuditExportRequested(String exportId) {
}
