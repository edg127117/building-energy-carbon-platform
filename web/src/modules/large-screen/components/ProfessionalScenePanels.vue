<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowLeft, Wind, ShieldCheck, Zap, Layers, ChevronDown, Building2 } from '@/shared/ui'
import { t } from '@/locales'
import ScreenPlot from './ScreenPlot.vue'
export interface SceneObject { id: string; name: string; system: string; floor: string | null }
const props = defineProps<{ topic: string; assets: readonly SceneObject[]; selected: string | null; floor: string; buildingName: string; playing: boolean }>()
const emit = defineEmits<{ system: [topic: string | null]; floor: [floor: string]; select: [id: string | null]; playing: [playing: boolean]; menu: [] }>()
const s = (key: string, params?: Record<string, string | number>) => t('largeScreen.' + key, params)
const systems = [{ id: 'elevator', icon: Layers }, { id: 'hvac', icon: Wind }, { id: 'lighting', icon: Zap }, { id: 'fire', icon: ShieldCheck }]
const floors = ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'Roof']
const kind = ref<'outdoor' | 'indoor'>('outdoor')
const objectsOpen = ref(false)
const systemName = computed(() => s(props.topic))
const selectedObject = computed(() => props.assets.find(asset => asset.id === props.selected))
const visibleObjects = computed(() => props.assets.filter(asset => props.floor === s('all') || asset.floor === props.floor))
const floorName = (floor: string) => floor === 'Roof' ? s('roof') : s('floorName', { floor })
function setKind(value: 'outdoor' | 'indoor') {
  kind.value = value
  const candidate = props.assets.find(asset => value === 'indoor' ? asset.id === 'HVAC-INDOOR-DEMO' : asset.id === 'E5_F5_DRAWN_001')
  if (candidate) emit('select', candidate.id)
}
</script>
<template>
  <section class="professional-ui" :aria-label="s('systemTitle', { system: systemName })">
    <header class="professional-header"><div><button @click="emit('system', null)"><ArrowLeft :size="16" />{{ s('back') }}</button><span>{{ s('modelName') }}</span><span>{{ '›' }}</span><strong>{{ s('systemTitle', { system: systemName }) }}</strong></div><div><span class="mapping-note">{{ s('noBinding') }}</span><button class="outlined" @click="emit('menu')">{{ s('switchPage') }}</button></div></header>
    <nav class="professional-tabs" :aria-label="s('modelSystem')"><button v-for="system in systems" :key="system.id" :aria-pressed="topic === system.id" @click="emit('system', system.id)"><component :is="system.icon" :size="17" />{{ s(system.id) }}</button></nav>
    <aside class="asset-list">
      <h2>{{ s('floorDistribution', { system: systemName }) }}</h2><p>{{ s('installation') }}</p>
      <div v-if="topic === 'hvac'" class="kind-tabs"><button :aria-pressed="kind === 'outdoor'" @click="setKind('outdoor')">{{ s('outdoor') }}</button><button :aria-pressed="kind === 'indoor'" @click="setKind('indoor')">{{ s('indoor') }}</button></div>
      <div class="model-total"><span>{{ s('allBuilding') }} {{ '·' }} {{ s('modelObjects') }}</span><strong>{{ assets.length }}<small>{{ s('modelUnit') }}</small></strong></div>
      <button class="all-assets" :aria-pressed="floor === s('all')" @click="emit('floor', s('all'))">{{ s('viewBuilding') }}</button>
      <div class="floor-heading"><span>{{ s('floor') }}</span><span>{{ s('modelObjects') }}</span></div>
      <button v-for="item in floors" :key="item" class="floor-row" :aria-pressed="floor === item" @click="emit('floor', item)"><strong>{{ floorName(item) }}</strong><span>{{ assets.filter(asset => asset.floor === item).length }} {{ s('modelUnit') }}</span></button>
      <h3>{{ s('selectedModel') }}</h3><p>{{ selectedObject?.name ?? s('allBuilding') }}</p>
      <dl><div><dt>{{ s('formalState') }}</dt><dd>{{ s('missing') }}</dd></div><div><dt>{{ s('communication') }}</dt><dd>{{ s('missing') }}</dd></div></dl>
      <p class="footnote">{{ s('geometryOnly') }}</p>
      <button class="model-list-toggle" :aria-expanded="objectsOpen" @click="objectsOpen = !objectsOpen">{{ s('modelAssets') }}<ChevronDown :size="14" /></button>
      <div v-if="objectsOpen" class="model-list"><button v-for="asset in visibleObjects" :key="asset.id" :aria-pressed="selected === asset.id" @click="emit('select', asset.id)">{{ asset.name }}</button><p v-if="!visibleObjects.length">{{ s('modelEmpty') }}</p></div>
    </aside>
    <aside class="asset-details">
      <h2>{{ s('energySituation', { system: systemName }) }}</h2><label class="floor-label"><span>{{ s('modelScope') }}</span><select :value="floor" @change="emit('floor', ($event.target as HTMLSelectElement).value)"><option :value="s('all')">{{ s('allBuilding') }}</option><option v-for="item in floors" :key="item" :value="item">{{ floorName(item) }}</option></select></label>
      <div class="energy-total"><span>{{ s('todayElectricity') }}</span><strong>{{ s('missing') }}<small>{{ s('kwh') }}</small></strong><p>{{ s('noEnergy') }}</p></div>
      <dl><div v-for="key in ['currentPower', 'monthlyElectricity', 'yesterdayComparison', 'updatedAt']" :key="key"><dt>{{ s(key) }}</dt><dd>{{ s('missing') }}</dd></div></dl>
      <h3>{{ s('energyTrend') }}</h3><div class="professional-plot"><ScreenPlot kind="trend" :label="s('energyTrend')" /></div>
      <section class="model-caliber"><h3>{{ s('dataInfo') }}</h3><p>{{ s('mappingPending') }}</p><p>{{ s('selectedBuilding') }}{{ '：' }}{{ buildingName || s('missing') }}</p><p v-if="selectedObject">{{ s('modelId') }}{{ '：' }}{{ selectedObject.id }}</p><p>{{ s('sourceNotice') }}</p></section>
    </aside>
    <nav class="professional-bottom" :aria-label="s('modelSystem')"><span><Building2 :size="16" />{{ s('modelSystem') }}</span><template v-if="topic === 'hvac'"><button :aria-pressed="selected === 'E5_F5_DRAWN_001'" @click="setKind('outdoor')">{{ s('typicalOutdoor') }}</button><button :aria-pressed="selected === 'HVAC-INDOOR-DEMO'" @click="setKind('indoor')">{{ s('typicalIndoor') }}</button></template><button :aria-pressed="!selected" @click="emit('select', null)">{{ s('combination') }}</button><button :aria-pressed="playing" @click="emit('playing', !playing)">{{ s(playing ? 'modelPaused' : 'modelPlaying') }}</button></nav>
  </section>
</template>
<style scoped>
.professional-ui { position: absolute; inset: 0; pointer-events: none; color: var(--bec-screen-ink); font-size: var(--bec-screen-font-body); }
button,select { font: inherit; color: inherit; cursor: pointer; }
button { min-height: var(--bec-screen-floor-height); background: transparent; border: 0; display: inline-flex; align-items: center; justify-content: center; gap: var(--bec-screen-space-8); padding: var(--bec-screen-space-6) var(--bec-screen-space-12); }
button:hover { background: var(--bec-screen-neutral); }
button[aria-pressed=true],.all-assets { background: var(--bec-screen-selected); color: var(--bec-screen-border); }
button:focus-visible,select:focus-visible { outline: var(--bec-screen-space-2) solid var(--bec-screen-blue); outline-offset: var(--bec-screen-space-2); }
.professional-header { position: absolute; inset: 0 0 auto; height: var(--bec-screen-toolbar-height); background: var(--bec-screen-detail-panel); border-bottom: var(--bec-screen-border-width) solid var(--bec-screen-divider); display: flex; align-items: center; justify-content: space-between; padding: 0 var(--bec-screen-space-24); pointer-events: auto; }
.professional-header>div { display: flex; align-items: center; gap: var(--bec-screen-space-16); }
.mapping-note { font-size: var(--bec-screen-font-small); color: var(--bec-screen-muted); }
.outlined { border: var(--bec-screen-border-width) solid var(--bec-screen-divider); background: var(--bec-screen-border); border-radius: var(--bec-screen-radius); }
.professional-tabs { position: absolute; top: var(--bec-screen-professional-top); left: 50%; transform: translateX(-50%); display: flex; padding: var(--bec-screen-space-4); border: var(--bec-screen-border-width) solid var(--bec-screen-divider); background: var(--bec-screen-detail-panel); border-radius: var(--bec-screen-radius); pointer-events: auto; }
.asset-list,.asset-details { position: absolute; top: var(--bec-screen-professional-top); bottom: var(--bec-screen-professional-bottom); padding: var(--bec-screen-professional-padding); background: var(--bec-screen-detail-panel); border: var(--bec-screen-border-width) solid var(--bec-screen-divider); pointer-events: auto; overflow: auto; scrollbar-width: thin; }
.asset-list { left: var(--bec-screen-space-20); width: var(--bec-screen-professional-left); }
.asset-details { right: var(--bec-screen-space-20); width: var(--bec-screen-professional-right); }
h2 { font-size: var(--bec-screen-font-title); font-weight: var(--bec-screen-weight); margin: 0 0 var(--bec-screen-space-8); }
h3 { font-size: var(--bec-screen-font-body); margin: var(--bec-screen-space-20) 0 var(--bec-screen-space-12); }
p { font-size: var(--bec-screen-font-small); color: var(--bec-screen-muted); margin: var(--bec-screen-space-8) 0 var(--bec-screen-space-16); line-height: var(--bec-screen-line-height); }
.kind-tabs { display: flex; border: var(--bec-screen-border-width) solid var(--bec-screen-divider); padding: var(--bec-screen-space-4); margin: var(--bec-screen-space-16) 0; }
.kind-tabs button { flex: 1; }
.model-total,.energy-total { display: flex; flex-direction: column; gap: var(--bec-screen-space-8); padding: var(--bec-screen-space-20) 0; margin: var(--bec-screen-space-16) 0; border-block: var(--bec-screen-border-width) solid var(--bec-screen-divider); }
.model-total strong { font-size: var(--bec-screen-font-large); font-weight: var(--bec-screen-weight); }
small { font-size: var(--bec-screen-font-small); margin-left: var(--bec-screen-space-8); }
.all-assets { width: 100%; margin-bottom: var(--bec-screen-space-16); }
.floor-heading,.floor-row { width: 100%; display: flex; align-items: center; justify-content: space-between; font-size: var(--bec-screen-font-small); }
.floor-heading { color: var(--bec-screen-muted); margin-bottom: var(--bec-screen-space-8); }
.floor-row { padding-inline: var(--bec-screen-space-4); border-bottom: var(--bec-screen-border-width) solid var(--bec-screen-divider); }
dl { margin: 0; font-size: var(--bec-screen-font-small); }
dl>div { display: flex; justify-content: space-between; gap: var(--bec-screen-space-8); padding: var(--bec-screen-space-12) 0; border-bottom: var(--bec-screen-border-width) solid var(--bec-screen-divider); }
dt { color: var(--bec-screen-muted); } dd { margin: 0; }
.floor-label { display: flex; align-items: center; gap: var(--bec-screen-space-8); margin-top: var(--bec-screen-space-20); font-size: var(--bec-screen-font-small); }
select { flex: 1; min-width: 0; padding: var(--bec-screen-space-12); border: var(--bec-screen-border-width) solid var(--bec-screen-divider); background: var(--bec-screen-border); border-radius: var(--bec-screen-radius); }
.energy-total { border-top: 0; margin-top: var(--bec-screen-space-16); }
.energy-total strong { font-size: var(--bec-screen-font-large); font-weight: var(--bec-screen-weight); }
.energy-total p { margin: 0; }
.professional-plot { height: var(--bec-screen-plot-height); }
.model-caliber { border-top: var(--bec-screen-border-width) solid var(--bec-screen-divider); margin-top: var(--bec-screen-space-24); }
.professional-bottom { pointer-events: auto; position: absolute; bottom: var(--bec-screen-space-20); left: 50%; transform: translateX(-50%); background: var(--bec-screen-detail-panel); padding: var(--bec-screen-space-8); display: flex; align-items: center; }
.professional-bottom>span { display: flex; align-items: center; gap: var(--bec-screen-space-8); padding: var(--bec-screen-space-8); white-space: nowrap; }
.professional-bottom>button { white-space: nowrap; border-left: var(--bec-screen-border-width) solid var(--bec-screen-divider); }
.model-list-toggle { width: 100%; justify-content: space-between; font-size: var(--bec-screen-font-small); }
.model-list button { width: 100%; text-align: left; justify-content: flex-start; font-size: var(--bec-screen-font-small); }
</style>
