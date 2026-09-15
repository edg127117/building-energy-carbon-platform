export default {
  title: '协议配置与预览',
  description: '复用产品测点模板，将 JSON 字段映射为标准指标并由后端验证解析结果。',
  draftNotice: '当前仅保存草稿，不会发布到适配器，也不会使配置生效。',
  actions: {
    newDraft: '新建草稿', loadDraft: '加载草稿', saveDraft: '保存草稿', inspect: '识别字段', preview: '后端解析预览',
    useIdentity: '设为身份路径', useDiscriminator: '设为判别路径', useTimestamp: '设为时间路径', addMapping: '添加测点映射',
    clearOptionalPath: '清除', removeMapping: '移除', openProducts: '管理产品模板',
  },
  sections: { basics: '草稿与产品', sample: '报文样例', fields: '字段树', mapping: '测点映射', preview: '解析预览' },
  labels: {
    draft: '已有草稿', name: '配置名称', product: '产品模板', productStatus: '产品状态', profileCode: '协议标识', sourceTopic: '原始 Topic', identityType: '身份类型',
    identityPath: '身份路径', discriminatorPath: '判别路径', discriminatorValue: '判别值', timestampPath: '时间路径', samplePayload: 'JSON 报文',
    selectedField: '当前字段', fieldType: '字段类型', sampleValue: '样例值', sourcePath: '来源路径', metric: '产品测点', sourceUnit: '来源单位',
    targetUnit: '标准单位', scale: '倍率', offset: '偏移', required: '必需', enabled: '启用', sortOrder: '顺序', status: '状态',
    identityValue: '身份值', timeSource: '时间来源', eventTime: '事件时间', rawValue: '原值', value: '换算值', result: '结果', errorPath: '错误路径',
  },
  placeholders: {
    draft: '选择要继续编辑的草稿', name: '例如：内机电表 V1', product: '搜索并选择产品', topic: '例如：building/+/meter/up',
    sample: '粘贴一条 JSON 报文，样例只用于当前页面，不随草稿保存。', discriminatorValue: '与判别路径配套填写', sourceUnit: '按设备协议确认',
  },
  states: { draft: '草稿未生效', fieldEmpty: '粘贴样例并识别后显示字段', mappingEmpty: '从数字字段添加测点映射', previewEmpty: '完成映射后由后端执行真实解析预览', present: '已解析', missingOptional: '未提供', success: '样例解析成功', failed: '样例解析失败' },
  fieldRoot: '报文根节点',
  productStatus: { draft: '产品草稿', enabled: '已启用产品' },
  messages: { saved: '草稿已保存并重新读取服务端版本。', inspected: '字段识别完成，请选择身份、时间和测点路径。' },
  errors: { conflict: '草稿已被其他操作更新，请重新加载草稿后再编辑。', validation: '配置或样例校验失败，请检查必填项、字段格式和限制。', rateLimited: '预览操作过于频繁，请稍后重试。' },
  counters: { sampleBytes: '{count} / 65536 B', mappings: '{count} / 128' },
  validation: {
    sampleRequired: '请先粘贴 JSON 报文。', sampleTooLarge: '样例不能超过 64 KiB。', selectedField: '请先在字段树中选择叶节点。', numericMapping: '只有数字字段可以添加测点映射。',
    required: '请填写配置名称、原始 Topic 和身份路径。', product: '请选择产品模板。', productContract: '协议标识或身份类型与产品模板不一致。', discriminator: '设置判别路径后必须填写判别值。',
    mappingLimit: '测点映射不能超过 128 项。', mappingRequired: '请至少启用一项测点映射。', duplicateMetric: '同一指标只能启用一次。', mappingContract: '映射的指标、必需性或标准单位与产品模板不一致。',
    mappingFields: '请补全启用映射的来源路径、来源单位、倍率和偏移。', missingRequiredMetric: '产品模板中的必需测点必须全部映射。',
  },
} as const
