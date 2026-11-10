export default {
  title: '每日静态碳冻结证据',
  separator: ' · ',
  rule: '下载原粒度证据接口响应原文（含 Result 封装），不触发计算；保留 MONITORING 与模拟/来源标注。文件不是可直接按 evidenceHash 校验的存储正文。',
  download: '下载证据',
  failed: '证据下载失败，请重试。权限与数据完整性由服务端校验。',
  unknown: '来源未标注',
}
