<template>
  <!--
    HVAC V1 大屏展示后端真实建筑、最新分钟快照、最新指标状态、计算证据和历史趋势。
    useHvacDashboard 负责 HTTP 权威数据与 WebSocket 生命周期，历史面板与计算详情分别维护自己的请求和竞态隔离；
    缺失或失败数据保持明确状态，不生成随机测点、虚假趋势或前端公式结果。
  -->
  <div class="hvac-demo">
    <div class="ambient ambient-a" />
    <div class="ambient ambient-b" />

    <header class="topbar">
      <div class="brand">
        <div class="brand-mark"><Snowflake :size="20" /></div>
        <div>
          <div class="brand-title">中央空调经常性调适平台</div>
          <div class="brand-subtitle">CONTINUOUS COMMISSIONING CENTER</div>
        </div>
      </div>

      <div class="header-center">
        <Building2 :size="15" />
        <a-select
          :value="selectedBuildingId ?? undefined"
          :options="buildingOptions"
          :loading="initializing"
          class="building-select"
          placeholder="选择建筑"
          @change="handleBuildingChange"
        />
        <a-button
          size="small"
          :loading="refreshing"
          :disabled="!selectedBuildingId"
          @click="refresh"
        >
          刷新数据
        </a-button>
      </div>

      <div class="header-status">
        <RouterLink v-if="managementPath" class="management-entry" :to="managementPath">
          <Settings :size="14" />后台管理
        </RouterLink>
        <div class="status-item">
          <span class="status-dot" :class="realtimeStatus.tone" />
          {{ realtimeStatus.label }}
        </div>
        <div class="clock">{{ clock }}</div>
      </div>
    </header>

    <main class="dashboard-shell">
      <a-alert
        v-if="buildingError"
        type="error"
        show-icon
        :message="buildingError"
      />
      <a-empty
        v-else-if="!initializing && buildings.length === 0"
        description="当前账号尚未获得建筑访问权限"
      />
      <a-alert
        v-if="snapshotError"
        type="warning"
        show-icon
        :message="`测点快照刷新失败：${snapshotError}`"
      />
      <a-alert
        v-if="indicatorError"
        type="warning"
        show-icon
        :message="`性能指标刷新失败：${indicatorError}`"
      />

      <!-- 建筑选择、19 槽位完整率和最近一次前端刷新时间的汇总。 -->
      <section class="summary-strip">
        <div>
          <div class="eyebrow">SYSTEM OVERVIEW</div>
          <h1>中央空调系统实时能效总览</h1>
          <p>单体设备能效计算 · 19 个核心测点 · 分钟级更新</p>
        </div>
        <div class="summary-stats">
          <div class="summary-stat">
            <span>当前建筑</span><strong>{{ selectedBuilding?.buildingCode ?? '--' }}</strong><small>{{ selectedBuilding?.buildingName ?? '尚未选择' }}</small>
          </div>
          <div class="summary-stat">
            <span>固定测点</span><strong>19</strong><small>冻结展示槽位</small>
          </div>
          <div class="summary-stat">
            <span>当前有效测点完整率</span><strong>{{ coveragePercent }}%</strong><small>最近刷新 {{ lastUpdatedText }}</small>
          </div>
        </div>
      </section>

      <!-- 拓扑中的温度、流量、功率和环境值均来自 19 个领域映射槽位。 -->
      <section class="main-grid">
        <article class="panel topology-panel">
          <div class="panel-heading">
            <div>
              <span class="section-index">01</span>
              <span class="panel-title">系统运行拓扑</span>
              <span class="panel-subtitle">当前展示后端最新分钟快照</span>
            </div>
            <div class="legend">
              <span><i class="legend-line cooling" />冷却水</span>
              <span><i class="legend-line chilled" />冷冻水</span>
              <span><i class="legend-line air" />送回风</span>
            </div>
          </div>

          <div class="topology-canvas">
            <div class="zone-label zone-cooling">冷却水循环</div>
            <div class="zone-label zone-chilled">冷冻水循环</div>
            <div class="zone-label zone-air">VAV 风系统</div>

            <div class="pipe pipe-h cooling-pipe pipe-cooling-supply"><span /></div>
            <div class="pipe pipe-h cooling-pipe pipe-cooling-return reverse"><span /></div>

            <div class="pipe pipe-h chilled-pipe pipe-chilled-supply"><span /></div>
            <div class="pipe pipe-h chilled-pipe pipe-chilled-return reverse"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-drop"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-return-drop reverse"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-rise reverse"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-return-rise"><span /></div>

            <div class="pipe pipe-v air-pipe pipe-air-loop"><span /></div>
            <div class="pipe pipe-v air-pipe pipe-air-return-loop reverse"><span /></div>

            <div class="device-card tower">
              <span class="device-glow" />
              <span class="device-icon"><Waves :size="26" /></span>
              <span class="device-meta"><b>CT-01</b><em>冷却塔</em></span>
              <span class="device-value"><b>进水</b> {{ pointText('TOWER1_TCWin') }}{{ pointUnit('TOWER1_TCWin') }} · <b>出水</b> {{ pointText('TOWER1_TCWout') }}{{ pointUnit('TOWER1_TCWout') }}</span>
              <span class="device-state" :class="{ 'is-stale': pointViews.TOWER1_TCWin.status === 'STALE', 'is-empty': pointViews.TOWER1_TCWin.status === 'NO_DATA' }">
                <span class="device-state-label"><i />{{ pointStatus('TOWER1_TCWin') }}</span>
                <small v-if="pointMinuteDetail(pointViews.TOWER1_TCWin)" class="point-sample-minute">{{ pointMinuteDetail(pointViews.TOWER1_TCWin) }}</small>
                <small v-if="stalePointDetail(pointViews.TOWER1_TCWin)">{{ stalePointDetail(pointViews.TOWER1_TCWin) }}</small>
              </span>
            </div>

            <div class="device-card chiller">
              <span class="device-glow" />
              <span class="device-icon"><Snowflake :size="28" /></span>
              <span class="device-meta"><b>WCR-01</b><em>水冷冷水机组</em></span>
              <span class="device-value"><b>出水</b> {{ pointText('WCR1_TWout') }}{{ pointUnit('WCR1_TWout') }} · <b>功率</b> {{ pointText('WCR1_PPE') }} {{ pointUnit('WCR1_PPE') }}</span>
              <span class="device-state" :class="{ 'is-stale': pointViews.WCR1_TWout.status === 'STALE', 'is-empty': pointViews.WCR1_TWout.status === 'NO_DATA' }">
                <span class="device-state-label"><i />{{ pointStatus('WCR1_TWout') }}</span>
                <small v-if="pointMinuteDetail(pointViews.WCR1_TWout)" class="point-sample-minute">{{ pointMinuteDetail(pointViews.WCR1_TWout) }}</small>
                <small v-if="stalePointDetail(pointViews.WCR1_TWout)">{{ stalePointDetail(pointViews.WCR1_TWout) }}</small>
              </span>
            </div>

            <div class="device-card pump">
              <span class="device-glow" />
              <span class="device-icon"><Gauge :size="27" /></span>
              <span class="device-meta"><b>P-01</b><em>冷冻水泵</em></span>
              <span class="device-value">{{ pointText('PUMP1_Flow') }} {{ pointUnit('PUMP1_Flow') }}</span>
              <span class="device-state" :class="{ 'is-stale': pointViews.PUMP1_Flow.status === 'STALE', 'is-empty': pointViews.PUMP1_Flow.status === 'NO_DATA' }">
                <span class="device-state-label"><i />{{ pointStatus('PUMP1_Flow') }}</span>
                <small v-if="pointMinuteDetail(pointViews.PUMP1_Flow)" class="point-sample-minute">{{ pointMinuteDetail(pointViews.PUMP1_Flow) }}</small>
                <small v-if="stalePointDetail(pointViews.PUMP1_Flow)">{{ stalePointDetail(pointViews.PUMP1_Flow) }}</small>
              </span>
            </div>

            <div class="device-card ahu">
              <span class="device-glow" />
              <span class="device-icon fan-icon"><Fan :size="29" /></span>
              <span class="device-meta"><b>AHU-01</b><em>空气处理机组</em></span>
              <span class="device-value">全压 {{ pointText('AHU1_TotalPress') }} {{ pointUnit('AHU1_TotalPress') }}</span>
              <span class="device-state" :class="{ 'is-stale': pointViews.AHU1_TotalPress.status === 'STALE', 'is-empty': pointViews.AHU1_TotalPress.status === 'NO_DATA' }">
                <span class="device-state-label"><i />{{ pointStatus('AHU1_TotalPress') }}</span>
                <small v-if="pointMinuteDetail(pointViews.AHU1_TotalPress)" class="point-sample-minute">{{ pointMinuteDetail(pointViews.AHU1_TotalPress) }}</small>
                <small v-if="stalePointDetail(pointViews.AHU1_TotalPress)">{{ stalePointDetail(pointViews.AHU1_TotalPress) }}</small>
              </span>
            </div>

            <div class="vav-terminal">
              <div class="vav-icon"><Wind :size="22" /></div>
              <div><b>VAV 末端</b><span>风量自适应</span></div>
              <div class="air-dots"><i /><i /><i /></div>
            </div>

            <div class="flow-label label-supply">冷冻水供水<br /><b>{{ pointText('WCR1_TWout') }}{{ pointUnit('WCR1_TWout') }}</b></div>
            <div class="flow-label label-return">冷冻水回水<br /><b>{{ pointText('WCR1_TWin') }}{{ pointUnit('WCR1_TWin') }}</b></div>

            <div class="outdoor-chip"><CloudSun :size="17" /><span>室外 {{ pointText('DBO_TDB') }}{{ pointUnit('DBO_TDB') }} · RH {{ pointText('DBO_RH') }}{{ pointUnit('DBO_RH') }}</span></div>
          </div>
        </article>

        <!-- 四张指标卡先展示业务结论；原因码与缺失语义键只在计算详情抽屉中展开。 -->
        <aside class="indicators">
          <div class="aside-heading">
            <div><span class="section-index">02</span><span class="panel-title">实时能效指标</span></div>
            <span class="minute-tag">每分钟更新</span>
          </div>

          <button
            v-for="card in indicatorCards"
            :key="card.indicatorCode"
            class="indicator-card"
            :class="[card.tone, { 'is-failed': card.status !== 'SUCCESS' }]"
            type="button"
            :aria-label="`${card.label}，${card.statusLabel}，查看计算详情`"
            @click="openCalculationDetail(card)"
          >
            <span class="indicator-card-head">
              <span class="indicator-identity">
                <span class="indicator-icon"><component :is="card.icon" :size="19" /></span>
                <span class="indicator-label">{{ card.label }}</span>
              </span>
              <span class="indicator-status">{{ card.statusLabel }}</span>
            </span>
            <span class="indicator-number">
              {{ card.displayValue }}<small>{{ card.unit }}</small>
            </span>
            <span class="indicator-message">
              <strong>{{ card.summaryText }}</strong>
              <small v-if="card.supportingText">{{ card.supportingText }}</small>
            </span>
            <span class="indicator-footer">
              <small>{{ formatIndicatorMinute(card.minuteStart) }}</small>
              <span class="indicator-detail-hint">
                查看计算详情
                <ChevronRight :size="14" aria-hidden="true" />
              </span>
            </span>
          </button>

          <div class="quality-card">
            <div class="quality-head"><span>当前有效测点完整率</span><strong>{{ coveragePercent }}%</strong></div>
            <div class="quality-bar"><i :style="{ width: `${coveragePercent}%` }" /></div>
            <div class="quality-items">
              <span><i class="q-real" />仅统计 NORMAL 当前值</span>
              <span><i class="q-default" />过期或缺失保持 --</span>
            </div>
          </div>
        </aside>
      </section>

      <!-- 公式区仍是冻结业务公式的静态目录；真实逐分钟证据只从四项指标卡进入。 -->
      <section class="panel formula-catalog">
        <div class="panel-heading formula-catalog-heading">
          <div>
            <span class="section-index">03</span>
            <span class="panel-title">本期公式清单</span>
            <span class="panel-subtitle">依据冻结书菜单公式表逐项展示</span>
          </div>
          <div class="formula-count"><strong>7</strong><span>条计算公式已覆盖</span><em>6 项公式概览 · 水泵包含两段串联计算</em></div>
        </div>
        <div class="formula-grid">
          <div
            v-for="item in formulaCards"
            :key="item.key"
            class="formula-card"
            :class="item.tone"
          >
            <span class="formula-card-icon"><component :is="item.icon" :size="19" /></span>
            <span class="formula-card-main">
              <small>{{ item.group }}</small>
              <b>{{ item.title }}</b>
              <code>{{ item.formula }}</code>
            </span>
            <span class="formula-card-result"><small>冻结业务公式口径</small></span>
          </div>
        </div>
      </section>

      <!-- 历史面板读取真实服务端趋势；测点列表继续按冻结顺序展示最新快照或明确空值。 -->
      <section class="bottom-grid">
        <article class="panel trend-panel">
          <HvacHistoryPanel
            :building-id="selectedBuildingId"
            :indicators="indicatorViews"
            :snapshot-points="snapshot?.points ?? []"
          />
        </article>

        <article class="panel point-panel">
          <div class="panel-heading compact">
            <div><span class="section-index">05</span><span class="panel-title">全部测点</span></div>
            <span class="text-button">固定展示 19 个测点槽位</span>
          </div>
          <div class="point-list">
            <div
              v-for="item in allPoints"
              :key="item.displayCode"
              class="point-row"
              :class="{ 'is-stale': item.status === 'STALE', 'is-empty': item.status === 'NO_DATA' }"
            >
              <span class="point-status" />
              <span class="point-name"><b>{{ item.label }}</b><small>{{ item.displayCode }}</small></span>
              <span class="point-reading">
                <span class="point-value">{{ item.displayValue }} <small>{{ item.unit }}</small></span>
                <small v-if="pointMinuteDetail(item)" class="point-sample-minute">{{ pointMinuteDetail(item) }}</small>
                <small v-if="item.lastDisplayValue !== null" class="point-last-value">{{ stalePointDetail(item) }}</small>
              </span>
              <span class="point-quality">{{ item.qualityLabel }} · {{ item.statusLabel }}</span>
            </div>
          </div>
        </article>
      </section>
    </main>

    <HvacCalculationDetailDrawer
      :open="calculationDetailVisible"
      :target="calculationDetailTarget"
      :detail="calculationDetail"
      :loading="calculationDetailLoading"
      :error="calculationDetailError"
      :local-no-data="calculationDetailLocalNoData"
      @close="closeCalculationDetail"
      @retry="retryCalculationDetail"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Activity,
  Building2,
  Calculator,
  ChevronRight,
  CloudSun,
  Fan,
  Gauge,
  Settings,
  Snowflake,
  Waves,
  Wind,
} from 'lucide-vue-next'
import HvacCalculationDetailDrawer from '@/components/hvac/HvacCalculationDetailDrawer.vue'
import HvacHistoryPanel from '@/components/hvac/HvacHistoryPanel.vue'
import { useHvacCalculationDetail } from '@/composables/useHvacCalculationDetail'
import {
  type HvacRealtimeState,
  useHvacDashboard,
} from '@/composables/useHvacDashboard'
import {
  type DashboardPointView,
  FROZEN_POINT_DEFINITIONS,
  type FrozenPointCode,
} from '@/domain/hvacDashboard'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import type { AdminNavigationItem } from '@/domain/adminNavigation'

const clock = ref('')
let clockTimer: number | null = null
const authStore = useAuthStore()
const menuStore = useMenuStore()

/** 管理入口同时要求平台管理员角色和后端当前菜单中的已注册管理路径。 */
const managementPath = computed(() => {
  if (!authStore.roles.includes('PLATFORM_ADMIN')) return null
  const find = (items: AdminNavigationItem[]): string | null => {
    for (const item of items) {
      if (item.admin && item.path) return item.path
      const nested = find(item.children)
      if (nested) return nested
    }
    return null
  }
  return find(menuStore.navigation)
})

/**
 * 大屏数据状态由 Composable 提供：建筑来自 MySQL 授权范围，测点来自 MySQL 元数据与
 * TDengine 分钟行，指标优先 Redis 并回退 TDengine。页面只负责展示和生命周期绑定。
 */
const {
  buildings,
  selectedBuildingId,
  selectedBuilding,
  snapshot,
  pointViews,
  indicatorViews,
  coveragePercent,
  initializing,
  refreshing,
  buildingError,
  snapshotError,
  indicatorError,
  snapshotUpdatedAt,
  indicatorUpdatedAt,
  realtimeState,
  initialize,
  selectBuilding,
  refresh,
  startRealtime,
  stopRealtime,
} = useHvacDashboard()

/**
 * 计算详情状态独立于大屏轮询：页面只传入指标实例和来源分钟，API 错误不会阻断主体。
 * 关闭、切换指标和切换建筑时的迟到响应由 Composable 的请求版本统一失效。
 */
const {
  visible: calculationDetailVisible,
  selected: calculationDetailTarget,
  detail: calculationDetail,
  loading: calculationDetailLoading,
  error: calculationDetailError,
  localNoData: calculationDetailLocalNoData,
  open: openCalculationDetailState,
  retry: retryCalculationDetail,
  close: closeCalculationDetail,
} = useHvacCalculationDetail()

function pointText(code: FrozenPointCode): string {
  return pointViews.value[code].displayValue
}

function pointUnit(code: FrozenPointCode): string {
  return pointViews.value[code].unit
}

function pointStatus(code: FrozenPointCode): string {
  return pointViews.value[code].statusLabel
}

/** 格式化后端数据分钟；只展示数据采样时间，不用页面刷新时间补空值。 */
function formatPointMinute(minute: number | null): string {
  if (minute === null) return '--'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(minute))
}

/** 正常测点展示当前采样分钟；无记录和过期记录分别交由空状态与最后值证据表达。 */
function pointMinuteDetail(point: DashboardPointView): string | null {
  if (point.status !== 'NORMAL' || point.minute === null) return null
  return `采样 ${formatPointMinute(point.minute)}`
}

/** 过期测点保留最后值和来源分钟，主实时值仍保持 --。 */
function stalePointDetail(point: DashboardPointView): string | null {
  if (point.status !== 'STALE' || point.lastDisplayValue === null) return null
  const unit = point.unit ? ` ${point.unit}` : ''
  return `最后值 ${point.lastDisplayValue}${unit} · ${formatPointMinute(point.minute)}`
}

function updateClock(): void {
  clock.value = new Date()
    .toLocaleString('zh-CN', { hour12: false })
    .replace(/\//g, '-')
}

/**
 * 显示快照或指标最近一次成功完成前端刷新的时间。
 * 该时间不等于后端测点的分钟时间，也不把失败请求计为更新成功。
 */
const lastUpdatedText = computed(() => {
  const latest = Math.max(
    snapshotUpdatedAt.value ?? 0,
    indicatorUpdatedAt.value ?? 0,
  )
  return latest
    ? new Date(latest).toLocaleTimeString('zh-CN', { hour12: false })
    : '--'
})

/** HTTP 刷新时间与实时通道分别展示，避免把连通状态误解为全部测点已更新。 */
const realtimeStatePresentation: Record<
  HvacRealtimeState,
  { label: string; tone: 'blue' | 'green' | 'yellow' | 'red' }
> = {
  connecting: { label: '实时连接中', tone: 'blue' },
  realtime: { label: '实时连接正常', tone: 'green' },
  reconnecting_with_http: { label: '实时重连中，HTTP 保障', tone: 'yellow' },
  http_fallback: { label: 'HTTP 保障中', tone: 'yellow' },
  forbidden: { label: '无该建筑访问权限', tone: 'red' },
}

const realtimeStatus = computed(() =>
  realtimeStatePresentation[realtimeState.value],
)

const buildingOptions = computed(() =>
  buildings.value.map((building) => ({
    label: building.buildingName,
    value: building.buildingId,
  })),
)

const indicatorIcons = {
  WCR_COP: Snowflake,
  TOWER_EFF: Waves,
  PUMP_EFF: Gauge,
  AHU_POW_EFF: Fan,
} as const

/** 为四项指标视图补充展示图标；业务值、单位和失败状态仍来自领域映射。 */
const indicatorCards = computed(() =>
  indicatorViews.value.map((indicator) => ({
    ...indicator,
    icon:
      indicatorIcons[
        indicator.indicatorCode as keyof typeof indicatorIcons
      ] ?? Activity,
  })),
)

/**
 * 格式化后端指标记录的来源分钟，只说明该次计算对应的时间，不在此判断数据是否过期。
 * 新鲜度必须由明确的数据时间状态判断，不能把时间格式化结果误当成当前数据状态。
 */
function formatIndicatorMinute(minuteStart: number | null): string {
  if (minuteStart === null) return '暂无来源分钟'
  return `来源分钟 ${new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(minuteStart))}`
}

/** 将用户点击的指标实例和来源分钟交给详情状态层；空槽位会打开本地无记录状态。 */
function openCalculationDetail(
  card: (typeof indicatorCards.value)[number],
): void {
  void openCalculationDetailState({
    indicatorId: card.indicatorId,
    indicatorCode: card.indicatorCode,
    label: card.label,
    minuteStart: card.minuteStart,
  })
}

/** 切换建筑前关闭详情，使旧建筑证据和在途响应不能停留在新建筑页面。 */
async function handleBuildingChange(buildingId: string): Promise<void> {
  closeCalculationDetail()
  await selectBuilding(buildingId)
}

/** 按冻结书定义顺序展开全部 19 个槽位，接口缺项也不会改变列表结构。 */
const allPoints = computed(() =>
  FROZEN_POINT_DEFINITIONS.map((definition) => ({
    ...pointViews.value[definition.displayCode],
  })),
)

/**
 * 冻结书公式的静态概览文案，只用于说明业务口径。
 * 实际指标值由后端公式引擎产生，本数组不参与计算，也不展示历史计算步骤。
 */
const formulaCards = [
  { key: 'chillerCooling', group: '冷水机组', title: '制冷量计算', formula: 'Q₀ = V × ρ × c × ΔT / 3600', tone: 'blue', icon: Snowflake },
  { key: 'chillerPower', group: '冷水机组', title: '电功率计算', formula: 'Nᵢ = PPE ｜ U × I × cosφ × √3', tone: 'blue', icon: Activity },
  { key: 'chillerCop', group: '冷水机组', title: 'COP 计算', formula: 'COP = Q₀ / Nᵢ', tone: 'blue', icon: Calculator },
  { key: 'towerEfficiency', group: '冷却塔', title: '冷却塔效率', formula: 'η = ΔT / (Tᵢₙ − Tᵢw)', tone: 'green', icon: Waves },
  { key: 'pumpEfficiency', group: '水泵', title: '水泵效率', formula: '先算 ΔH，再代入 η = V̇ × ρ × g × (ΔH + Z) / (3.6 × W)', tone: 'orange', icon: Gauge },
  { key: 'ahuPower', group: '风系统', title: '单位风量耗功值', formula: 'Wₛ = P / (3600 × ηₜ)', tone: 'yellow', icon: Fan },
] as const

/** 页面挂载时先取得受保护 HTTP 首屏，再启动由 Composable 统一管理的实时生命周期。 */
onMounted(async () => {
  updateClock()
  clockTimer = window.setInterval(updateClock, 1000)
  if (authStore.roles.includes('PLATFORM_ADMIN')) {
    void menuStore.ensureLoaded().catch(() => undefined)
  }
  await initialize()
  startRealtime()
})

/** 页面卸载时清理时钟、实时资源和详情请求状态，避免后台刷新或迟到结果写回。 */
onBeforeUnmount(() => {
  if (clockTimer !== null) window.clearInterval(clockTimer)
  closeCalculationDetail()
  stopRealtime()
})
</script>

<style scoped>
.hvac-demo {
  min-height: 100vh;
  color: #dce8f8;
  background: #06101d;
  background-image:
    linear-gradient(rgba(67, 113, 158, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(67, 113, 158, 0.055) 1px, transparent 1px),
    radial-gradient(circle at 42% 30%, rgba(17, 85, 140, 0.18), transparent 32%);
  background-size: 42px 42px, 42px 42px, auto;
  overflow-x: hidden;
  position: relative;
}

.ambient { position: fixed; border-radius: 50%; filter: blur(100px); pointer-events: none; opacity: .24; }
.ambient-a { width: 420px; height: 420px; left: -180px; top: 28%; background: #0b68c7; }
.ambient-b { width: 360px; height: 360px; right: -160px; bottom: -80px; background: #087c6d; }

.topbar { height: 68px; padding: 0 28px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; border-bottom: 1px solid rgba(150, 190, 225, .13); background: rgba(4, 13, 24, .83); backdrop-filter: blur(18px); position: sticky; top: 0; z-index: 20; }
.brand { display: flex; align-items: center; gap: 12px; }
.brand-mark { width: 38px; height: 38px; display: grid; place-items: center; color: #dff2ff; border: 1px solid rgba(86, 180, 255, .45); background: linear-gradient(145deg, rgba(43, 143, 229, .25), rgba(17, 60, 104, .2)); box-shadow: inset 0 0 20px rgba(42, 151, 241, .13), 0 0 22px rgba(35, 139, 226, .12); }
.brand-title { font-weight: 650; font-size: 15px; letter-spacing: .06em; }
.brand-subtitle { margin-top: 2px; color: #5d718a; font-size: 8px; letter-spacing: .18em; }
.header-center { display: flex; gap: 8px; align-items: center; }
.building-select { min-width: 220px; }
.header-status { justify-self: end; display: flex; align-items: center; gap: 18px; color: #778ba3; font-size: 10px; }
.management-entry { display:flex; align-items:center; gap:6px; min-height:30px; padding:0 10px; color:#9fc5e2; text-decoration:none; border:1px solid rgba(104,169,217,.22); background:rgba(33,102,154,.1); border-radius:10px; }
.management-entry:hover { color:#e7f6ff; border-color:rgba(104,184,239,.4); }
.status-item { display: flex; gap: 7px; align-items: center; }
.status-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; }
.status-dot.blue { background: #4aa8ff; box-shadow: 0 0 9px #4aa8ff; }
.status-dot.green { background: #3fe0a8; box-shadow: 0 0 9px #3fe0a8; }
.status-dot.yellow { background: #e6c45d; box-shadow: 0 0 9px #e6c45d; }
.status-dot.red { background: #f06f73; box-shadow: 0 0 9px #f06f73; }
.clock { font-variant-numeric: tabular-nums; letter-spacing: .06em; }

.dashboard-shell { width: min(1640px, calc(100% - 44px)); margin: 0 auto; padding: 24px 0 30px; position: relative; z-index: 2; }
.dashboard-shell > :deep(.ant-alert), .dashboard-shell > :deep(.ant-empty) { margin-bottom: 12px; }
.summary-strip { display: flex; justify-content: space-between; align-items: flex-end; gap: 24px; margin-bottom: 18px; }
.eyebrow { color: #3d97e6; font-size: 9px; letter-spacing: .28em; font-weight: 700; }
h1 { margin: 5px 0 2px; font-size: clamp(22px, 2vw, 31px); line-height: 1.2; color: #eff7ff; font-weight: 560; letter-spacing: .03em; }
.summary-strip p { margin: 0; color: #62758d; font-size: 11px; letter-spacing: .06em; }
.summary-stats { display: flex; min-width: 450px; border: 1px solid rgba(140, 180, 215, .13); background: rgba(9, 24, 41, .62); }
.summary-stat { flex: 1; min-width: 140px; padding: 9px 15px; display: grid; grid-template-columns: 1fr auto; align-items: baseline; border-right: 1px solid rgba(140, 180, 215, .1); }
.summary-stat:last-child { border-right: 0; }
.summary-stat span, .summary-stat small { color: #62758c; font-size: 9px; }
.summary-stat strong { color: #dceeff; font-size: 21px; font-weight: 560; font-variant-numeric: tabular-nums; }
.summary-stat small { grid-column: 1 / -1; color: #3eae89; }

.main-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; }
.panel, .indicators { border: 1px solid rgba(137, 179, 215, .14); background: linear-gradient(145deg, rgba(10, 28, 48, .9), rgba(6, 19, 34, .88)); box-shadow: inset 0 1px 0 rgba(255,255,255,.025), 0 14px 40px rgba(0, 4, 11, .16); }
.panel-heading, .aside-heading { height: 48px; padding: 0 15px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgba(137, 179, 215, .1); }
.panel-heading > div, .aside-heading > div { display: flex; align-items: center; gap: 9px; }
.section-index { color: #318cd8; font-size: 9px; letter-spacing: .1em; }
.panel-title { color: #d6e5f5; font-size: 12px; font-weight: 600; }
.panel-subtitle { color: #4f647d; font-size: 9px; }
.legend { display: flex; gap: 14px; color: #657990; font-size: 9px; }
.legend span { display: flex; align-items: center; gap: 5px; }
.legend-line { display: inline-block; width: 16px; height: 2px; }
.legend-line.cooling { background: #36d7a7; }
.legend-line.chilled { background: #37a6ff; }
.legend-line.air { background: #e2b84e; }

.topology-panel { overflow-x: auto; }
.topology-canvas { min-width: 900px; height: 500px; position: relative; overflow: hidden; background: radial-gradient(circle at 50% 48%, rgba(34, 86, 131, .12), transparent 42%); }
.topology-canvas::after { content: ''; position: absolute; inset: 18px; border: 1px solid rgba(119, 164, 201, .045); pointer-events: none; }
.zone-label { position: absolute; top: 15px; color: #3d5269; font-size: 8px; letter-spacing: .14em; text-transform: uppercase; }
.zone-cooling { left: 7%; }
.zone-chilled { left: 44%; }
.zone-air { right: 9%; }

.device-card { position: absolute; z-index: 5; width: 155px; min-height: 138px; border: 1px solid rgba(100, 169, 219, .28); background: linear-gradient(145deg, rgba(14, 39, 64, .96), rgba(7, 24, 42, .96)); color: #dceafa; padding: 14px; text-align: left; overflow: hidden; }
.device-glow { position: absolute; width: 65px; height: 65px; border-radius: 50%; right: -20px; top: -20px; background: rgba(37, 151, 237, .16); filter: blur(14px); }
.device-icon { width: 38px; height: 38px; display: grid; place-items: center; color: #61bdff; border: 1px solid rgba(86, 183, 255, .24); background: rgba(29, 115, 183, .12); float: left; margin-right: 10px; }
.device-meta { display: flex; flex-direction: column; min-width: 0; padding-top: 1px; }
.device-meta b { font-size: 10px; letter-spacing: .13em; color: #7b95ae; }
.device-meta em { font-size: 12px; font-style: normal; font-weight: 550; color: #e1effc; white-space: nowrap; }
.device-value { display: block; clear: both; padding-top: 11px; font-size: 10px; color: #85a0b8; font-variant-numeric: tabular-nums; white-space: nowrap; }
.device-value b { color: #5f7b94; font-size: 8px; font-weight: 500; }
.device-state { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; margin-top: 7px; color: #41d9a5; font-size: 9px; }
.device-state-label { display: flex; align-items: center; gap: 6px; }
.device-state i { width: 5px; height: 5px; background: #3ce0a9; border-radius: 50%; box-shadow: 0 0 8px #3ce0a9; }
.device-state small { max-width: 100%; color: #c79850; font-size: 9px; line-height: 1.35; overflow-wrap: anywhere; }
.device-state small.point-sample-minute { color: #6f879e; }
.device-state.is-stale { color: #e3ad55; }
.device-state.is-stale i { background: #e3ad55; box-shadow: 0 0 8px rgba(227, 173, 85, .7); }
.device-state.is-empty { color: #687d91; }
.device-state.is-empty i { background: #52677b; box-shadow: none; }
.tower { left: 5%; top: 90px; width: 180px; border-color: rgba(54, 216, 168, .3); }
.tower .device-icon { color: #45ddb0; border-color: rgba(69, 221, 176, .25); background: rgba(30, 128, 100, .12); }
.tower .device-glow { background: rgba(43, 203, 155, .17); }
.chiller { left: 34%; top: 90px; width: 190px; }
.pump { left: 55%; top: 261px; width: 160px; border-color: rgba(255, 168, 82, .3); }
.pump .device-icon { color: #ffae5f; border-color: rgba(255, 174, 95, .25); background: rgba(151, 89, 27, .12); }
.pump .device-glow { background: rgba(239, 135, 42, .16); }
.ahu { right: 5%; top: 90px; width: 190px; border-color: rgba(233, 194, 82, .32); }
.ahu .device-icon { color: #f0ca64; border-color: rgba(240, 202, 100, .25); background: rgba(153, 122, 30, .12); }
.ahu .device-glow { background: rgba(231, 190, 66, .16); }
.fan-icon :deep(svg) { animation: rotateFan 3s linear infinite; }

.vav-terminal { position: absolute; right: 5%; bottom: 30px; z-index: 5; width: 190px; height: 58px; padding: 10px 12px; display: flex; align-items: center; gap: 9px; border: 1px solid rgba(224, 185, 73, .2); background: rgba(18, 30, 41, .96); }
.vav-icon { color: #dbb951; }
.vav-terminal div:nth-child(2) { display: flex; flex-direction: column; }
.vav-terminal b { color: #cedae7; font-size: 10px; }
.vav-terminal span { color: #5c7087; font-size: 8px; }
.air-dots { display: flex; gap: 3px; margin-left: auto; }
.air-dots i { width: 3px; height: 3px; border-radius: 50%; background: #e1bd55; animation: airPulse 1.2s ease-in-out infinite; }
.air-dots i:nth-child(2) { animation-delay: .2s; }
.air-dots i:nth-child(3) { animation-delay: .4s; }

.pipe { position: absolute; z-index: 1; overflow: hidden; background: rgba(62, 129, 183, .12); }
.pipe-h { height: 5px; }
.pipe-v { width: 5px; }
.pipe::before { content: ''; position: absolute; inset: 1px; background: currentColor; opacity: .32; }
.pipe span { position: absolute; width: 28px; height: 100%; background: linear-gradient(90deg, transparent, currentColor, transparent); filter: drop-shadow(0 0 5px currentColor); animation: flowH 2.1s linear infinite; }
.pipe-v span { width: 100%; height: 28px; background: linear-gradient(180deg, transparent, currentColor, transparent); animation-name: flowV; }
.pipe.reverse span { animation-direction: reverse; }
.cooling-pipe { color: #34d6a4; }
.chilled-pipe { color: #3da8ff; }
.air-pipe { color: #e0b947; }
.pipe-cooling-supply { left: calc(5% + 180px); top: 121px; width: calc(29% - 180px); }
.pipe-cooling-return { left: calc(5% + 180px); top: 169px; width: calc(29% - 180px); }
.pipe-chilled-supply { left: calc(34% + 72px); top: 319px; width: calc(61% - 234px); }
.pipe-chilled-return { left: calc(34% + 118px); top: 392px; width: calc(61% - 242px); }
.pipe-chilled-drop { left: calc(34% + 70px); top: 208px; height: 114px; }
.pipe-chilled-return-drop { left: calc(34% + 116px); top: 208px; height: 187px; }
.pipe-chilled-rise { left: calc(95% - 164px); top: 208px; height: 114px; }
.pipe-chilled-return-rise { left: calc(95% - 126px); top: 208px; height: 187px; }
.pipe-air-loop { left: calc(95% - 68px); top: 208px; height: 205px; }
.pipe-air-return-loop { left: calc(95% - 38px); top: 208px; height: 205px; }

.flow-label { position: absolute; z-index: 7; color: #7197b5; font-size: 9px; line-height: 1; white-space: nowrap; background: rgba(8, 23, 40, .96); border: 1px solid rgba(94, 151, 194, .15); padding: 5px 8px; box-shadow: 0 4px 12px rgba(0, 7, 15, .22); }
.flow-label b { color: #7cbced; font-weight: 500; }
.label-supply, .label-return { left: calc(34% + 124px); width: 58px; box-sizing: border-box; padding: 5px 4px; line-height: 1.35; text-align: center; white-space: normal; }
.label-supply { top: 282px; }
.label-return { top: 402px; }
.outdoor-chip { position: absolute; left: 5.5%; bottom: 32px; display: flex; gap: 7px; align-items: center; color: #7790a8; font-size: 9px; border: 1px solid rgba(133, 168, 197, .12); background: rgba(7, 20, 34, .8); padding: 8px 10px; }

.indicators { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; padding: 10px; }
.aside-heading { margin-bottom: 9px; }
.aside-heading, .quality-card { grid-column: 1 / -1; }
.minute-tag { color: #597089; font-size: 8px; }
.indicator-card { position: relative; width: 100%; min-width: 0; min-height: 132px; margin: 0; padding: 13px 14px 11px; display: grid; grid-template-rows: auto auto minmax(32px, 1fr) auto; gap: 8px; border: 1px solid rgba(127, 167, 201, .14); background: rgba(7, 21, 37, .78); color: #dce8f5; text-align: left; cursor: pointer; overflow: hidden; transition: border-color .2s ease, transform .2s ease, background-color .2s ease; }
.indicator-card.is-failed { background: linear-gradient(145deg, color-mix(in srgb, var(--tone) 6%, #071525), rgba(7, 21, 37, .82)); }
.indicator-card:hover { transform: translateY(-2px); border-color: color-mix(in srgb, var(--tone) 42%, transparent); }
.indicator-card:focus-visible { outline: 2px solid var(--tone); outline-offset: 2px; border-color: var(--tone); }
.indicator-card-head, .indicator-identity, .indicator-footer, .indicator-detail-hint { display: flex; align-items: center; }
.indicator-card-head { min-width: 0; justify-content: space-between; gap: 8px; }
.indicator-identity { min-width: 0; gap: 9px; }
.indicator-icon { width: 32px; height: 32px; flex: 0 0 32px; display: grid; place-items: center; color: var(--tone); background: color-mix(in srgb, var(--tone) 10%, transparent); border: 1px solid color-mix(in srgb, var(--tone) 25%, transparent); }
.indicator-label { min-width: 0; color: #b8cbe0; font-size: 12px; font-weight: 600; line-height: 1.35; overflow-wrap: anywhere; }
.indicator-status { flex: 0 0 auto; padding: 3px 6px; color: color-mix(in srgb, var(--tone) 78%, #fff); border: 1px solid color-mix(in srgb, var(--tone) 25%, transparent); background: color-mix(in srgb, var(--tone) 8%, transparent); font-size: 11px; line-height: 1.3; }
.indicator-number { min-width: 0; line-height: 1.05; color: #edf6ff; font-size: 27px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.indicator-number small { margin-left: 5px; color: #8298ae; font-size: 11px; font-weight: 400; }
.indicator-message { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.indicator-message strong { color: #c8d9e9; font-size: 12px; font-weight: 560; line-height: 1.45; overflow-wrap: anywhere; }
.indicator-message small { color: #849ab0; font-size: 11px; line-height: 1.4; overflow-wrap: anywhere; }
.indicator-footer { min-width: 0; justify-content: space-between; gap: 8px; padding-top: 8px; border-top: 1px solid rgba(127, 167, 201, .1); }
.indicator-footer > small { min-width: 0; color: #71889f; font-size: 11px; overflow-wrap: anywhere; }
.indicator-detail-hint { flex: 0 0 auto; gap: 2px; color: var(--tone); font-size: 11px; font-weight: 600; }
.indicator-card.blue { --tone: #42a5ff; }
.indicator-card.green { --tone: #37d4a2; }
.indicator-card.orange { --tone: #ffab55; }
.indicator-card.yellow { --tone: #e6c45d; }
.quality-card { margin: 4px 0 0; padding: 12px; border: 1px solid rgba(127, 167, 201, .1); background: rgba(5, 17, 30, .6); }
.quality-head { display: flex; justify-content: space-between; color: #778ca4; font-size: 9px; }
.quality-head strong { color: #4fdaa9; font-size: 12px; }
.quality-bar { height: 3px; margin: 8px 0 10px; background: rgba(91, 127, 154, .16); }
.quality-bar i { display: block; height: 100%; background: linear-gradient(90deg, #2582c7, #42d5a3); }
.quality-items { display: flex; flex-wrap: wrap; gap: 9px; color: #566b82; font-size: 7px; }
.quality-items span { display: flex; gap: 4px; align-items: center; }
.quality-items i { width: 4px; height: 4px; border-radius: 50%; }
.q-real { background: #42d6a5; }.q-fill { background: #e6bf51; }.q-default { background: #8796a8; }

.formula-catalog { margin-top: 14px; }
.formula-catalog-heading { height: 52px; }
.formula-count { display: flex; align-items: baseline; gap: 5px; color: #61768d; font-size: 9px; }
.formula-count strong { color: #65bcf7; font-size: 19px; font-weight: 560; }
.formula-count em { margin-left: 9px; padding-left: 12px; border-left: 1px solid rgba(125, 166, 200, .14); color: #586a7d; font-size: 8px; font-style: normal; }
.formula-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; padding: 12px; }
.formula-card { --tone: #42a5ff; position: relative; min-width: 0; min-height: 94px; padding: 13px 42px 12px 13px; display: grid; grid-template-columns: 32px minmax(0, 1fr) auto; grid-template-rows: 1fr auto; align-items: center; column-gap: 10px; border: 1px solid rgba(127, 167, 201, .12); background: linear-gradient(135deg, color-mix(in srgb, var(--tone) 7%, transparent), rgba(5, 17, 30, .72)); color: #dce8f5; text-align: left; overflow: hidden; }
.formula-card.green { --tone: #37d4a2; }.formula-card.orange { --tone: #ffab55; }.formula-card.yellow { --tone: #e6c45d; }
.formula-card-icon { grid-row: 1 / 3; width: 32px; height: 32px; display: grid; place-items: center; color: var(--tone); border: 1px solid color-mix(in srgb, var(--tone) 25%, transparent); background: color-mix(in srgb, var(--tone) 9%, transparent); }
.formula-card-main { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.formula-card-main small { color: #536b82; font-size: 8px; }
.formula-card-main b { color: #cfdfed; font-size: 11px; font-weight: 560; }
.formula-card-main code { overflow: hidden; color: #6591b2; font: 8px/1.4 'Cascadia Code', Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.formula-card-result { grid-column: 2 / 4; align-self: end; display: flex; align-items: baseline; gap: 5px; margin-top: 5px; }
.formula-card-result strong { color: #ecf6ff; font-size: 17px; line-height: 1; font-weight: 540; font-variant-numeric: tabular-nums; }
.formula-card-result small { color: #61768c; font-size: 7px; }
.formula-card-arrow { position: absolute; right: 11px; bottom: 13px; color: color-mix(in srgb, var(--tone) 65%, transparent); }

.bottom-grid { display: grid; grid-template-columns: minmax(0, 1fr) 370px; gap: 14px; margin-top: 14px; }
.panel-heading.compact { height: 42px; }
.text-button { display: flex; align-items: center; gap: 2px; color: #5e7890; }
.point-list { padding: 7px 13px 11px; }
.point-row { min-height: 46px; display: grid; grid-template-columns: 14px minmax(0, 1fr) minmax(82px, auto) 104px; align-items: center; gap: 6px; border-bottom: 1px solid rgba(129, 169, 202, .08); }
.point-row:last-child { border-bottom: 0; }
.point-status { width: 5px; height: 5px; border-radius: 50%; background: #3cd7a4; box-shadow: 0 0 7px rgba(60, 215, 164, .65); }
.point-name { display: flex; flex-direction: column; }
.point-name b { font-size: 9px; color: #9badbf; font-weight: 500; }
.point-name small { font-size: 7px; color: #40566d; }
.point-reading { min-width: 0; display: flex; flex-direction: column; align-items: flex-end; }
.point-value { color: #d2e1ed; font-size: 12px; font-variant-numeric: tabular-nums; }
.point-value small { color: #566b80; font-size: 7px; }
.point-reading .point-sample-minute { color: #6f879e; font-size: 8px; line-height: 1.35; }
.point-last-value { max-width: 128px; color: #c79850; font-size: 9px; line-height: 1.35; text-align: right; overflow-wrap: anywhere; }
.point-quality { color: #3db78f; font-size: 7px; text-align: right; }
.point-row.is-stale .point-status { background: #e3ad55; box-shadow: 0 0 7px rgba(227, 173, 85, .65); }
.point-row.is-stale .point-quality { color: #d3a052; }
.point-row.is-empty .point-status { background: #52677b; box-shadow: none; }
.point-row.is-empty .point-quality { color: #687d91; }

@keyframes flowH { from { left: -30px; } to { left: 100%; } }
@keyframes flowV { from { top: -30px; } to { top: 100%; } }
@keyframes rotateFan { to { transform: rotate(360deg); } }
@keyframes airPulse { 0%, 100% { opacity: .2; transform: translateX(0); } 50% { opacity: 1; transform: translateX(3px); } }

@media (max-width: 1450px) {
  .topbar { grid-template-columns: 1fr auto; }
  .header-center { display: none; }
}

@media (max-width: 900px) {
  .indicators { grid-template-columns: repeat(2, 1fr); }
  .formula-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 820px) {
  .topbar { height: auto; min-height: 64px; padding: 10px 16px; }
  .header-status .status-item { display: none; }
  .dashboard-shell { width: calc(100% - 24px); padding-top: 16px; }
  .summary-strip { align-items: flex-start; flex-direction: column; }
  .summary-stats { min-width: 0; width: 100%; overflow-x: auto; }
  .topology-canvas { min-width: 900px; }
  .topology-panel { overflow-x: auto; }
  .bottom-grid { grid-template-columns: minmax(0, 1fr); }
  .legend { display: none; }
  .formula-catalog-heading { height: auto; min-height: 52px; align-items: flex-start; gap: 8px; padding-top: 10px; padding-bottom: 10px; }
  .formula-count em { display: none; }
}

@media (max-width: 560px) {
  .indicators { grid-template-columns: 1fr; }
  .formula-grid { grid-template-columns: 1fr; }
  .point-row { grid-template-columns: 12px minmax(0, 1fr) minmax(86px, auto); }
  .point-quality { grid-column: 2 / 4; font-size: 9px; }
}
</style>
