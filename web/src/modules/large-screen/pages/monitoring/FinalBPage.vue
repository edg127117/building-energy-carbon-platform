<script setup lang="ts">
import { computed } from 'vue'
import { ElButton } from '@/shared/ui'
import { t } from '@/locales'
import IndexPage from './IndexPage.vue'
import { useFinalBData } from '../../composables/use-final-b-data'
import { useFinalBAnnual } from '../../composables/use-final-b-annual'
import { useFinalBDaily } from '../../composables/use-final-b-daily'
import { withApprovedScreenDemo } from '../../models/final-b-demo'
import { useScreenCarbonPlan } from '../../composables/use-screen-carbon-plan'
import { useJointFlow } from '../../composables/use-joint-flow'
import { useOutdoorTemperature } from '../../composables/use-outdoor-temperature'
import { withOutdoorTemperature } from '../../models/outdoor-temperature'
import { useCarbonIntensity } from '../../composables/use-carbon-intensity'
import { intensityQuery } from '../../models/carbon-intensity'

const code = String(import.meta.env.VITE_SCREEN_BUILDING_CODE ?? '')
const state = useFinalBData(code)
const annual = useFinalBAnnual(state.buildingId, computed(() => state.periods.value.composition))
const daily = useFinalBDaily(state.buildingId, computed(() => state.periods.value.usage), computed(() => state.periods.value.mirror))
const plan = useScreenCarbonPlan(() => state.buildingId.value, () => Number(state.periods.value.plan.value.slice(0, 4)), () => state.periods.value.plan.kind === 'month' ? state.periods.value.plan.value : undefined)
const flow = useJointFlow(state.buildingId, computed(() => state.periods.value.flow))
const temperature = useOutdoorTemperature(state.buildingId, computed(() => state.periods.value.usage))
const intensity = useCarbonIntensity(computed(() => intensityQuery(state.buildingId.value, state.periods.value.intensity)), computed(() => state.periods.value.intensity))
const data = computed(() => withOutdoorTemperature(withApprovedScreenDemo({ ...state.data.value, ...annual.data.value, ...daily.data.value, ...flow.data.value, plan: plan.data.value ? { period: { kind: 'year' as const, value: state.periods.value.plan.value.slice(0, 4) }, data: plan.data.value } : undefined, intensity: intensity.data.value?.intensity, intensityBenchmarks: intensity.data.value?.intensityBenchmarks }, state.periods.value.mirror), state.periods.value.usage, temperature.values.value))
const building = computed(() => state.buildings.value.find(item => item.buildingId === state.buildingId.value))
const renderData = computed(() => ({ ...data.value, loading: {
  usage: state.periods.value.usage.kind === 'month' && daily.loading.value && !daily.data.value.usage,
  mirror: state.periods.value.mirror.kind === 'month' && daily.loading.value && !daily.data.value.mirror,
}, failed: {
  usage: state.periods.value.usage.kind === 'month' && daily.failedMonths.value.includes(state.periods.value.usage.value),
  mirror: state.periods.value.mirror.kind === 'month' && daily.failedMonths.value.includes(state.periods.value.mirror.value),
} }))
const s = (key: string, params?: Record<string, string | number>) => t('largeScreen.' + key, params)
</script>

<template>
  <IndexPage layout-variant="b" :data="renderData" @period-change="state.periods.value = $event">
    <template #data-context>
      <p>{{ s('finalBInterfaceNotice') }}</p>
      <p>{{ s('finalBBuildingContext', { value: building?.buildingName ?? s(code ? 'noBuildings' : 'finalBBuildingNotConfigured') }) }}</p>
      <p>{{ s('finalBReadContext', { ready: state.reports.value.size, failed: state.failedMonths.value.length }) }}</p>
      <p v-if="state.buildingError.value">{{ s('failed') }}</p>
      <p>{{ s('finalBPendingCards') }}</p>
      <p>{{ s('finalBDailyRule') }}</p>
      <p v-if="daily.failedMonths.value.length">{{ s('finalBDailyFailed', { months: daily.failedMonths.value.join('、') }) }}</p>
      <ElButton v-if="state.periods.value.usage.kind === 'month' || state.periods.value.mirror.kind === 'month'" :loading="daily.loading.value" @click="daily.reload()">{{ s('finalBDailyRetry') }}</ElButton>
      <p v-if="annual.result.value">{{ s('finalBAnnualContext', { year: annual.result.value.year, month: annual.result.value.throughMonth }) }}</p>
      <p v-if="annual.failed.value">{{ s('failed') }}</p>
      <ElButton :loading="annual.loading.value" @click="annual.reload()">{{ s('finalBAnnualRetry') }}</ElButton>
      <p v-if="plan.response.value">{{ t('energyClassification.carbonPlan.cutoff', { month: plan.response.value.throughMonth ?? t('energyClassification.carbonPlan.noCompleted') }) + ' · ' + t('energyClassification.carbonPlan.readStatus.' + plan.response.value.status) }}</p>
      <p v-if="plan.error.value">{{ plan.error.value }}</p>
      <ElButton :loading="plan.loading.value" @click="plan.refresh()">{{ s('retry') }}</ElButton>
      <p v-if="intensity.failed.value">{{ s('failed') }}</p>
      <ElButton :loading="intensity.loading.value" @click="intensity.reload()">{{ s('retry') }}</ElButton>
      <p>{{ s('finalBJointFlowContext', { state: flow.state.value, basis: flow.view.value?.classificationBasis ?? s('missing'), version: flow.view.value?.configurationVersionId ?? s('missing') }) }}</p>
      <p v-for="warning in flow.view.value?.warnings ?? []" :key="warning">{{ warning }}</p>
      <p>{{ s('finalBTemperatureContext', { state: s('finalBTemperatureState' + temperature.state.value), sources: temperature.view.value?.sources.length ?? 0 }) }}</p>
      <p>{{ s('finalBTemperatureRule') }}</p>
      <ElButton :loading="state.loading.value" @click="state.buildingError.value ? state.loadBuildings() : state.reload()">{{ s('retry') }}</ElButton>
      <ElButton :loading="flow.loading.value" @click="flow.reload()">{{ s('finalBJointFlowRetry') }}</ElButton>
      <ElButton :loading="temperature.loading.value" @click="temperature.reload()">{{ s('finalBTemperatureRetry') }}</ElButton>
    </template>
  </IndexPage>
</template>
