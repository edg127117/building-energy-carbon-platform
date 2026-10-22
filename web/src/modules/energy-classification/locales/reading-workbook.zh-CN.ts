export const workbookCopy = {
  title: 'Excel人工来源预检', note: '使用标准.xlsx模板，以建筑内测点编码匹配。未知项保留待确认；不读取公式，不猜倍率、时区或日期精度。原有CSV映射仍可使用。',
  template: '下载标准工作簿', file: '选择工作簿', reason: '本批来源依据与导入原因', preview: '只读预检', submit: '按本次预检导入草稿',
  result: '预检状态', count: '记录数', separator: ' · ', noFile: '请选择工作簿并填写来源依据。', imported: '已建立待审核批次；仍须独立审核，尚未正式封账。', clear: '清空工作簿选择',
} as const
