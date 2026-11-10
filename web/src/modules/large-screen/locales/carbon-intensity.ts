export default {
  carbonIntensityTitle: '碳排放强度', areaLabel: '单位建筑面积碳排放强度',
  sourceMissing: '未收录适用标准', timeUnclear: '适用时间未明确，仅供参考',
  ytd: '年内累计，截至{month}月；不作全年限额评价', month: '月度强度；不与全年限额比较',
  annualReferenceRatio: '条带为年度强度占年度参考约束值的比例，不作正式达标认定',
  ytdReferenceRatio: '截至{month}月，条带为累计强度占年度参考约束值的比例；未年化，不代表全年达标',
  reasons: {
    NO_APPLICABLE_REFERENCE: '未收录该地区、类别和期间适用的标准', BUILDING_PROFILE_INCOMPLETE: '请补齐建筑地区或用途',
    BUILDING_CATEGORY_AMBIGUOUS: '请在建筑档案明确一次评价类别', BUILDING_SUBCATEGORY_REQUIRED: '标准需要建筑子类别，请补齐档案',
    MULTIPLE_APPLICABLE_REFERENCES: '存在多个可能适用的标准，请核对目录或建筑类别', PERIOD_VERSION_CHANGE: '期间内标准版本变化，不能直接作整年比较',
    APPLICABILITY_TIME_UNCLEAR: '标准适用时间未明确，仅供参考', MONTH_NOT_ANNUAL_COMPARISON: '月度不与全年限额比较',
    YTD_NOT_ANNUAL_COMPARISON: '年内累计不作全年限额评价', ACTUAL_UNAVAILABLE: '实际强度暂不可用', UNIT_MISMATCH: '单位不一致',
    GAS_BASIS_MISMATCH_OR_UNKNOWN: '核算气体口径不同或尚未明确', BOUNDARY_MISMATCH_OR_UNKNOWN: '计量边界不同或尚未明确',
    AREA_BASIS_MISMATCH_OR_UNKNOWN: '面积口径不同或尚未明确',
  },
}
