<template>
  <a-drawer
    :open="open"
    width="min(620px, 100vw)"
    placement="right"
    root-class-name="calculation-detail-drawer"
    :destroy-on-close="true"
    @close="emit('close')"
  >
    <template #title>
      <div class="drawer-heading">
        <span class="drawer-title">{{ target?.label ?? '指标计算详情' }}</span>
        <span v-if="target?.indicatorCode" class="drawer-code">
          {{ target.indicatorCode }}
        </span>
      </div>
    </template>

    <!--
      抽屉只展示详情 API 返回的计算事实。请求、重试和竞态隔离由
      useHvacCalculationDetail 负责，本组件不解析表达式，也不在浏览器重算指标。
    -->
    <div class="calculation-detail">
      <div v-if="target" class="source-meta">
        <span><b>设备</b>{{ detail?.equipId || target.equipId || '未关联设备' }}</span>
        <span><b>来源分钟</b>{{ formatMinute(target.minuteStart) }}</span>
      </div>

      <div v-if="loading" class="state-panel" aria-live="polite">
        <a-skeleton active :paragraph="{ rows: 8 }" />
        <span class="state-caption">正在加载真实计算证据</span>
      </div>

      <div v-else-if="localNoData" class="state-panel empty-state">
        <a-empty description="当前指标还没有可查询的分钟记录" />
        <p>页面不会发送包含空指标或空分钟的请求。</p>
      </div>

      <div
        v-else-if="error"
        class="state-panel error-state"
        role="alert"
        aria-live="assertive"
      >
        <span class="state-kicker">计算详情请求失败</span>
        <strong>{{ error.message }}</strong>
        <p v-if="error.status">HTTP {{ error.status }}</p>
        <a-button
          v-if="error.status !== 403"
          data-test="retry"
          type="primary"
          @click="emit('retry')"
        >
          重试详情请求
        </a-button>
      </div>

      <div v-else-if="detail" class="detail-content">
        <div v-if="detail.status === 'NO_DATA'" class="state-panel empty-state">
          <a-empty description="该分钟没有计算记录" />
          <p>后端没有返回成功结果或失败审计，页面不会推测计算过程。</p>
        </div>

        <template v-else>
          <section class="summary-section" aria-labelledby="calculation-summary-title">
            <div class="section-heading">
              <h3 id="calculation-summary-title">计算摘要</h3>
              <a-tag :color="statusTone(detail.status)">
                {{ statusLabel(detail.status) }}
              </a-tag>
            </div>
            <dl class="summary-grid">
              <div class="summary-result">
                <dt>指标结果</dt>
                <dd
                  v-if="detail.status === 'SUCCESS' && detail.value !== null"
                  class="result-value"
                >
                  {{ formatNumber(detail.value) }}
                  <small v-if="detail.unit">{{ detail.unit }}</small>
                </dd>
                <dd v-else class="result-empty">未产生成功值</dd>
              </div>
              <div>
                <dt>数据质量</dt>
                <dd>{{ qualityLabel(detail.dataQuality) }}</dd>
              </div>
              <div>
                <dt>公式版本</dt>
                <dd>{{ detail.formulaVersion || '未提供' }}</dd>
              </div>
              <div>
                <dt>来源分钟</dt>
                <dd>{{ formatMinute(detail.minuteStart) }}</dd>
              </div>
            </dl>
          </section>

          <section v-if="detail.inputs.length" aria-labelledby="calculation-inputs-title">
            <div class="section-heading">
              <h3 id="calculation-inputs-title">输入证据</h3>
              <span>{{ detail.inputs.length }} 项</span>
            </div>
            <div class="evidence-list">
              <article
                v-for="input in detail.inputs"
                :key="`${input.key}-${input.pointId}`"
                class="evidence-row"
              >
                <div class="evidence-identity">
                  <strong>{{ input.key }}</strong>
                  <span>{{ input.pointCode }}</span>
                </div>
                <div class="evidence-value">
                  <b>{{ formatNumber(input.value) }}</b>
                  <small v-if="input.unit">{{ input.unit }}</small>
                </div>
                <span class="quality-badge">{{ qualityLabel(input.dataQuality) }}</span>
              </article>
            </div>
          </section>

          <section v-if="detail.steps.length" aria-labelledby="calculation-steps-title">
            <div class="section-heading">
              <h3 id="calculation-steps-title">计算步骤</h3>
              <span>按后端返回顺序</span>
            </div>
            <ol class="step-list">
              <li v-for="step in detail.steps" :key="step.code">
                <div class="step-index">{{ step.code }}</div>
                <code>{{ step.expression }}</code>
                <strong>
                  {{ formatNumber(step.value) }}
                  <small v-if="step.unit">{{ step.unit }}</small>
                </strong>
              </li>
            </ol>
          </section>

          <section
            v-if="detail.status !== 'SUCCESS'"
            class="audit-section"
            aria-labelledby="calculation-audit-title"
          >
            <div class="section-heading">
              <h3 id="calculation-audit-title">异常详情</h3>
              <span>{{ statusLabel(detail.status) }}</span>
            </div>
            <p class="audit-summary">
              本分钟未产生指标值，以下信息用于定位数据或公式问题。
            </p>
            <dl class="audit-grid">
              <div>
                <dt>原因码</dt>
                <dd>{{ detail.reasonCode || '后端未提供原因码' }}</dd>
              </div>
              <div v-if="detail.missingInputs.length">
                <dt>缺失语义键</dt>
                <dd class="missing-list">
                  <span v-for="item in detail.missingInputs" :key="item">
                    {{ item }}
                  </span>
                </dd>
              </div>
            </dl>
          </section>
        </template>
      </div>
    </div>
  </a-drawer>
</template>

<script setup lang="ts">
import type { HvacCalculationDetail } from '@/types/hvac'
import type {
  HvacCalculationDetailError,
  HvacCalculationDetailTarget,
} from '@/composables/useHvacCalculationDetail'

type Props = {
  open: boolean
  target: HvacCalculationDetailTarget | null
  detail: HvacCalculationDetail | null
  loading: boolean
  error: HvacCalculationDetailError | null
  localNoData: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  close: []
  retry: []
}>()

/** 把后端状态转换为直白文案；未知状态保留原值，避免被误报为成功。 */
function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    SUCCESS: '计算成功',
    MISSING_INPUT: '缺少必要输入',
    INVALID_INPUT: '输入数据无效',
    ENGINE_ERROR: '公式引擎计算失败',
    NO_DATA: '无计算记录',
  }
  return labels[status] ?? `未知状态：${status}`
}

/** 状态颜色只用于辅助扫描，页面同时保留完整文字状态。 */
function statusTone(status: string): string {
  if (status === 'SUCCESS') return 'success'
  if (status === 'MISSING_INPUT') return 'warning'
  return 'error'
}

/** 严格按 Q0/Q1/Q2 展示质量语义，未知等级保留原始数值。 */
function qualityLabel(quality: number | null): string {
  if (quality === 0) return 'Q0 真实数据'
  if (quality === 1) return 'Q1 线性插值'
  if (quality === 2) return 'Q2 典型值'
  if (quality === null) return '未提供质量'
  return `未知质量（Q${quality}）`
}

/** 数字只做本地化展示格式化，不参与任何公式求值。 */
function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: 6,
  }).format(value)
}

/** 时间戳来自后端分钟起点；空目标使用明确占位，不拼接非法请求路径。 */
function formatMinute(minuteStart: number | null): string {
  if (minuteStart === null) return '暂无来源分钟'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(minuteStart))
}
</script>

<style scoped>
:global(.calculation-detail-drawer .ant-drawer-content),
:global(.calculation-detail-drawer .ant-drawer-header) {
  background: #081727;
  color: #dce8f8;
}

:global(.calculation-detail-drawer .ant-drawer-content-wrapper) {
  width: min(620px, 100vw) !important;
  max-width: 100vw;
}

:global(.calculation-detail-drawer .ant-drawer-header) {
  border-bottom-color: rgba(137, 179, 215, 0.16);
}

:global(.calculation-detail-drawer .ant-drawer-close) {
  color: #9eb3c9;
}

.drawer-heading {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 10px;
}

.drawer-title {
  overflow: hidden;
  color: #f1f7ff;
  font-size: 16px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-code {
  color: #6f8ba6;
  font: 11px/1.4 'Cascadia Code', Consolas, monospace;
}

.calculation-detail {
  color: #c8d8e8;
}

.source-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 22px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(137, 179, 215, 0.12);
}

.source-meta span {
  min-width: 0;
  overflow-wrap: anywhere;
  color: #a8bdd1;
  font-size: 12px;
}

.source-meta b {
  display: block;
  margin-bottom: 4px;
  color: #5f7891;
  font-size: 10px;
  font-weight: 500;
}

.state-panel {
  display: grid;
  min-height: 280px;
  place-content: center;
  gap: 12px;
  text-align: center;
}

.state-caption,
.state-panel p {
  margin: 0;
  color: #70889f;
  font-size: 12px;
}

.state-kicker {
  color: #e09d64;
  font-size: 11px;
}

.error-state strong {
  max-width: 42ch;
  color: #f2d8c5;
  font-size: 15px;
  line-height: 1.6;
}

.error-state :deep(.ant-btn) {
  justify-self: center;
  margin-top: 8px;
}

.detail-content section + section {
  margin-top: 28px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-heading h3 {
  margin: 0;
  color: #e4effa;
  font-size: 13px;
  font-weight: 620;
}

.section-heading > span {
  color: #668099;
  font-size: 10px;
}

.summary-grid,
.audit-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid rgba(137, 179, 215, 0.14);
  background: rgba(137, 179, 215, 0.14);
}

.audit-summary {
  margin: 0 0 14px;
  color: #c6d7e7;
  font-size: 12px;
  line-height: 1.6;
}

.summary-grid > div,
.audit-grid > div {
  min-width: 0;
  padding: 13px;
  background: #0a1b2d;
}

.summary-grid dt,
.audit-grid dt {
  margin-bottom: 5px;
  color: #617990;
  font-size: 10px;
}

.summary-grid dd,
.audit-grid dd {
  margin: 0;
  overflow-wrap: anywhere;
  color: #c9d9e8;
  font-size: 12px;
}

.summary-result .result-value {
  color: #f1f8ff;
  font-size: 24px;
  font-variant-numeric: tabular-nums;
}

.summary-result .result-empty {
  color: #91a8bd;
}

.summary-result small,
.evidence-value small,
.step-list small {
  margin-left: 4px;
  color: #7890a7;
  font-size: 10px;
  font-weight: 400;
}

.evidence-list,
.step-list {
  display: grid;
  gap: 8px;
}

.evidence-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 12px 13px;
  border: 1px solid rgba(137, 179, 215, 0.12);
  background: rgba(10, 27, 45, 0.82);
}

.evidence-identity {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.evidence-identity strong,
.step-index {
  overflow-wrap: anywhere;
  color: #d6e6f4;
  font: 11px/1.5 'Cascadia Code', Consolas, monospace;
}

.evidence-identity span {
  overflow-wrap: anywhere;
  color: #617b93;
  font-size: 10px;
}

.evidence-value {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.quality-badge {
  color: #84bca9;
  font-size: 10px;
  white-space: nowrap;
}

.step-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.step-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px 16px;
  padding: 13px;
  border: 1px solid rgba(137, 179, 215, 0.12);
  background: rgba(10, 27, 45, 0.82);
}

.step-list code {
  grid-column: 1 / -1;
  overflow-wrap: anywhere;
  color: #86a8c5;
  font: 11px/1.65 'Cascadia Code', Consolas, monospace;
  white-space: normal;
}

.step-list strong {
  grid-column: 2;
  grid-row: 1;
  color: #eaf4fd;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.audit-section {
  padding: 14px;
  border: 1px solid rgba(224, 157, 100, 0.28);
  background: rgba(96, 49, 22, 0.16);
}

.audit-grid {
  grid-template-columns: 1fr;
  border: 0;
  background: transparent;
}

.audit-grid > div {
  padding: 0;
  background: transparent;
}

.audit-grid > div + div {
  margin-top: 14px;
}

.missing-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.missing-list span {
  padding: 4px 7px;
  border: 1px solid rgba(224, 157, 100, 0.24);
  color: #e7b48d;
  font: 10px/1.4 'Cascadia Code', Consolas, monospace;
  overflow-wrap: anywhere;
}

@media (max-width: 560px) {
  .source-meta,
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .evidence-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .quality-badge {
    grid-column: 1 / -1;
  }
}
</style>
