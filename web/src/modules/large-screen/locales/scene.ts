import redesign from './redesign'
import analytical from './analytical'
import summaries from './summaries'
import summaryReview from './summary-review'
import finalB from './final-b'

export default {
  ...redesign,
  ...analytical,
  ...summaries,
  ...summaryReview,
  ...finalB,
  platform: '建筑能碳监测管理平台', sceneLoading: '正在加载建筑与场景…', sceneUnavailable: '三维场景暂不可用', sceneHelp: '请使用支持 WebGL 2 的浏览器，或重新加载场景。', retryScene: '重新加载场景',
  front: '主立面全景', terrace: '露台侧全景', rear: '背立面全景', aerial: '高位鸟瞰', reset: '复位', free: '自由查看', tour: '机位轮播', pause: '暂停动效', resume: '恢复动效', reload: '重载', switchPage: '切换页面', fullscreen: '全屏显示', exitFullscreen: '退出全屏', exit: '返回工作区',
  yearCarbon: '年累计碳排放', yearIntensity: '年碳排放强度', dayCarbon: '当日累计碳排放', dayIntensity: '当日碳排放强度', tco2: 'tCO₂e', kgco2: 'kgCO₂e', intensityUnit: 'kgCO₂e/m²', tce: 'tce', kwh: 'kWh', kw: 'kW', percent: '%', missing: '—',
  composition: '碳排放构成', annual: '年累计', carbonTotal: '碳排放总量', scopeOne: '范围一', scopeTwo: '范围二', gas: '天然气', diesel: '柴油', electricity: '外购电力', heat: '外购热力',
  flow: '能碳流向', energyFlow: '能流图', carbonFlow: '碳流图', standardCoal: '折标煤', source: '能源类型', endUse: '设备分项', buildingTotal: '建筑合计', totalEnergy: '综合能耗', flowHelp: '悬停节点或连线查看读数', functions: '功能碳排放占比',
  hvac: '空调', heating: '供暖', ventilation: '通风', water: '给排水', lighting: '照明', power: '动力', fire: '消防', hotWater: '生活热水', cooking: '炊事', elevator: '电梯',
  comparison: '碳排放同期对比', temperature: '碳排放与室外温度', prediction: '碳排放实测与预测', emissions: '碳排放', outsideTemperature: '室外温度', actual: '实际值', predicted: '全天预测', previousYear: '去年同月', current: '本期', previousMonth: '上月', hourly: '分时排放量 · 非累计',
  noData: '暂无正式数据', noFlow: '暂无同口径能流数据', noCarbon: '暂无已发布的正式碳核算结果', noForecast: '尚未接入预测服务', noComparison: '暂无同口径对比数据', noTemperature: '暂无同周期碳排放与温度数据', noRanking: '暂无正式排名结果', noAlarms: '尚未接入建筑级告警查询', failed: '数据加载失败，请重试', loading: '正在读取正式数据…',
  alarms: '报警信息', all: '全部', offlineAlarms: '设备掉线', energyAlarms: '能耗超标', viewAll: '查看全部', ranking: '设备排名', highCarbon: '高碳排放强度', lowCop: '低 COP', unitPending: '单位待确认', dimensionless: '无量纲', device: '设备', value: '指标值',
  collapseAlarms: '收起报警信息', expandAlarms: '展开报警信息', collapseRanking: '收起设备排名', expandRanking: '展开设备排名',
  building: '授权建筑', noBuildings: '暂无可访问建筑', month: '统计月份', day: '统计日期', monthShort: '月', dayShort: '日', retry: '重试', dataInfo: '数据口径', close: '关闭', back: '返回总览', modelNotice: '建筑与专业对象为原型模型，尚未关联正式资产', mappingPending: '模型对象未关联正式设备', sourceNotice: '展示数据来自已授权建筑；缺失不补零，模型运动不代表设备运行。', hourlyCarbonNotice: '小时碳排放为监测结果，不代表月度正式封账。',
  enterSystem: '进入{system}系统', systemTitle: '{system}系统', floorDistribution: '{system}楼层分布', energySituation: '{system}用能情况', floor: '楼层', modelObjects: '模型对象', modelUnit: '组', countUnit: '台', installation: '按模型安装位置查看对象', allBuilding: '整栋大楼', viewBuilding: '查看整栋大楼', roof: '屋顶', floorName: '{floor} 层', outdoor: '外机', indoor: '内机',
  formalAssets: '正式台账资产', backendOnline: '通讯在线', backendOffline: '通讯离线', backendUnknown: '通讯未知', noLedgerAccess: '暂无台账统计，请核对建筑范围与查询权限',
  todayElectricity: '今日用电', currentPower: '当前功率', monthlyElectricity: '本月用电', yesterdayComparison: '昨日同期对比', updatedAt: '数据更新时间', energyTrend: '今日用电趋势', selectedModel: '当前模型对象', modelId: '模型标识', modelScope: '模型范围', modelFloor: '安装楼层', geometryOnly: '仅定位几何对象，不以模型标识自动绑定正式设备。', modelAssets: '查看模型对象', modelEmpty: '当前楼层无模型对象', noEnergy: '尚无该模型范围的正式计量关系', formalState: '正式运行状态', communication: '正式通讯状态', selectedBuilding: '正式数据建筑',
  modelPlaying: '播放模型动效', modelPaused: '暂停模型动效', modelSystem: '专业系统模型', apply: '应用', cancel: '取消', panelRestore: '恢复面板', details: '详细信息', dataBatch: '正式核算批次', noBinding: '尚未建立正式资产映射',
  modelName: '创新港', modelContext: '创新港参考模型 · 尚未建立正式资产映射', typicalOutdoor: '典型空调外机', typicalIndoor: '典型空调内机', combination: '组合视图',
  sceneOperatingState: '运行状态', sceneOperating: '运行正常', sceneCommunication: '通讯状态', sceneScope: '当前查看范围',
  classificationScheme: '正式分类方案', separator: ' · ',
  conversionView: '折标查看口径', chooseConversion: '请选择已审核参数', flowSourceManual: '已审核来源统计 · 本次查询折标', flowSourceFormal: '正式原生来源 · 本次查询折标',
  energyFlowPending: '能流节点与连线接口待补齐', flowEvidenceMissing: '该批次尚无可用的守恒分类流图', batchListLimited: '最近100个批次中未找到该期间，历史完整性尚不能确认', batchAmbiguous: '该期间存在多个有效候选，需核对当前正式版本',
  periodReady: '正式结果', periodEmpty: '未找到正式结果', periodError: '查询失败', periodLimited: '超出已返回批次范围或未生成', periodAmbiguous: '正式结果冲突', comparisonCaliber: '展示各期正式核算值；未计算同比或环比增幅', hourlyCarbonLoading: '正在读取小时碳排放', hourlyCarbonReady: '小时碳排放读取完成', hourlyCarbonPartial: '小时碳排放部分可用，缺失点留空', hourlyCarbonMissing: '小时碳排放尚未生成', hourlyCarbonError: '小时碳排放读取失败', hourlyCarbonUnavailable: '小时碳排放日期或建筑无效', hourlyCarbonSimulated: '模拟数据', hourlyCarbonSourceLabelled: '来源标注监测', hourlyCarbonNoSourceDeclaration: '无来源声明', hourlyCarbonBatch: '小时碳批次', hourlyCarbonRevision: '小时碳修订', hourlyCarbonSourceNature: '小时碳来源性质', weatherReady: '历史温度', weatherEmpty: '无可用历史温度', weatherError: '历史天气查询失败', weatherUnavailable: '日期或建筑未配置',
  weatherLoading: '正在读取历史温度', weatherPartial: '历史温度部分可用，缺失点留空',
  comparisonEvidence: '月度对比批次', weatherEvidence: '历史天气数据集', weatherSource: 'Open-Meteo 历史天气为模型回溯数据，不等同于现场温度传感器。',
} as const
