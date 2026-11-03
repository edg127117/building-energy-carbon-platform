import { computed, type Ref } from 'vue'
import { useAnnualOverview } from '@/modules/energy-reporting/public'
import { annualScreenQuery, screenAnnualComposition, screenAnnualOverview } from '../models/final-b-annual'
import { currentScreenPeriod, type ScreenPeriod } from '../models/screen-period'
import type { ScreenRedesignData } from '../models/screen-redesign'

/** 顶栏固定当年，构成卡独立选年；同年复用一次读取，期间与迟到响应校验沿用年度查询。 */
export function useFinalBAnnual(buildingId: Ref<string>, compositionPeriod: Ref<ScreenPeriod>) {
  const currentYear = currentScreenPeriod().value
  const top = useAnnualOverview(() => annualScreenQuery(buildingId.value, currentYear))
  const selected = useAnnualOverview(() => compositionPeriod.value.kind === 'year' && compositionPeriod.value.value !== currentYear
    ? annualScreenQuery(buildingId.value, compositionPeriod.value.value) : null)
  const composition = computed(() => compositionPeriod.value.kind !== 'year' ? null
    : compositionPeriod.value.value === currentYear ? top.result.value : selected.result.value)
  const data = computed<ScreenRedesignData>(() => ({
    annual: top.result.value ? screenAnnualOverview(top.result.value) : undefined,
    ...(compositionPeriod.value.kind === 'year' ? {
      composition: composition.value ? screenAnnualComposition(composition.value, compositionPeriod.value) : undefined,
    } : {}),
  }))
  return {
    data, result: top.result, composition,
    loading: computed(() => top.loading.value || selected.loading.value),
    failed: computed(() => top.error.value !== null || selected.error.value !== null),
    reload: () => Promise.all([top.reload(), selected.reload()]),
  }
}
