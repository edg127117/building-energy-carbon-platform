export const stagingCopy = {
  title: '新设备与测点草稿', note: '新档案先保存在当前关系草稿，独立审核生效后才进入正式资产。不会创建采集身份或自动完成设备注册。',
  equipment: '新增设备草稿', point: '新增测点草稿', name: '名称', type: '设备类型', product: '产品（可选）', manufacturer: '厂家（可选）',
  space: '空间归属', system: '系统归属', target: '所属设备', rule: '测点命名规则', code: '标准编码（可唯一生成时可留空）', suffix: '参数后缀',
  dataType: '数据类型', analog: '瞬时数值', digital: '状态值', accumulate: '累计量', unit: '测点单位', calculation: '是否参与计算',
  no: '仅展示', yes: '参与计算', status: '档案启用状态', offline: '停用', online: '启用（不代表现场在线）', reason: '建档或取消依据',
  save: '加入关系草稿', cancel: '取消暂存档案', staged: '尚未发布', published: '已发布', actions: '操作', codeColumn: '编码',
  edit: '补充归属', saveAssignment: '保存归属草稿', reset: '清空未保存内容',
  empty: '该版本没有新建档案', pending: '归属未知可留空；会阻止审核发布，不能自动推断。',
  loading: '正在读取后端目录', error: '读取或保存失败，请刷新后核对修订', required: '请填写名称、类型或命名规则、数据类型及依据',
} as const
