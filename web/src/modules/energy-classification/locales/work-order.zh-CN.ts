export const workOrderCopy = {
  title: '配置工作单', note: '按步骤处理一次明确操作。各域仍独立审核，操作权限由后端核对；配置完成不代表正式核算就绪。',
  load: '读取最新工作单', execute: '执行所选操作', reason: '操作依据或审核意见', action: '选择操作', effective: '明确生效时间',
  empty: '尚未读取工作单', dirty: '存在未保存内容，请先保存或还原。', stale: '配置或依赖已变化，请重新读取工作单。',
  required: '请填写操作依据；审核或激活需要明确生效时间时，按原领域规则填写。',
  actions: { SUBMIT: '提交审核', APPROVE: '审核通过', REJECT: '退回修改', WITHDRAW: '撤回本人申请', ACTIVATE: '发布生效' },
  success: '本次操作已完成，请刷新工作单查看下一步。', clear: '清空未提交操作',
} as const
