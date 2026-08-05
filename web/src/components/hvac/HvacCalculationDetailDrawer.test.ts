import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { HvacCalculationDetail } from '@/types/hvac'
import type {
  HvacCalculationDetailError,
  HvacCalculationDetailTarget,
} from '@/composables/useHvacCalculationDetail'
import HvacCalculationDetailDrawer from './HvacCalculationDetailDrawer.vue'

const target: HvacCalculationDetailTarget = {
  indicatorId: 'IND-1',
  indicatorCode: 'WCR_COP',
  label: '冷水机组 COP',
  equipId: 'WCR-01',
  minuteStart: 1_754_208_000_000,
}

const successDetail: HvacCalculationDetail = {
  indicatorId: 'IND-1',
  indicatorCode: 'WCR_COP',
  equipId: 'WCR-01',
  minuteStart: target.minuteStart!,
  status: 'SUCCESS',
  value: 4.2,
  unit: null,
  dataQuality: 1,
  formulaVersion: 'WCR_COP_V1',
  inputs: [
    {
      key: 'WCR1/TWin',
      pointId: 'POINT-1',
      pointCode: 'WCR1_TWin',
      value: 12.4,
      unit: '℃',
      dataQuality: 0,
    },
  ],
  steps: [
    {
      code: 'COP',
      expression: 'Q0 / Ni with a deliberately long expression that must wrap safely',
      value: 4.2,
      unit: null,
    },
  ],
  reasonCode: null,
  missingInputs: [],
}

const stubs = {
  'a-drawer': {
    inheritAttrs: false,
    props: ['open'],
    emits: ['close'],
    template:
      '<section v-if="open"><slot name="title" /><button data-test="drawer-close" @click="$emit(\'close\')">关闭</button><slot /></section>',
  },
  'a-skeleton': { template: '<div>正在加载计算详情</div>' },
  'a-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'a-tag': { template: '<span><slot /></span>' },
  'a-button': {
    inheritAttrs: false,
    emits: ['click'],
    template: '<button data-test="retry" @click="$emit(\'click\')"><slot /></button>',
  },
}

function mountDrawer(options?: {
  detail?: HvacCalculationDetail | null
  loading?: boolean
  error?: HvacCalculationDetailError | null
  localNoData?: boolean
}) {
  return mount(HvacCalculationDetailDrawer, {
    props: {
      open: true,
      target,
      detail: options?.detail ?? successDetail,
      loading: options?.loading ?? false,
      error: options?.error ?? null,
      localNoData: options?.localNoData ?? false,
    },
    global: { stubs },
  })
}

describe('HvacCalculationDetailDrawer', () => {
  it('shows success evidence, quality, version, inputs and ordered steps', () => {
    const wrapper = mountDrawer()
    const text = wrapper.text()

    expect(text).toContain('冷水机组 COP')
    expect(text).toContain('WCR_COP')
    expect(text).toContain('WCR-01')
    expect(text).toContain('计算成功')
    expect(text).toContain('4.2')
    expect(text).toContain('Q1 线性插值')
    expect(text).toContain('WCR_COP_V1')
    expect(text).toContain('WCR1/TWin')
    expect(text).toContain('Q0 真实数据')
    expect(text).toContain(successDetail.steps[0].expression)
    expect(text).not.toContain('null')
    expect(text).not.toContain('undefined')
  })

  it('shows missing-input audit without inventing a success value', () => {
    const wrapper = mountDrawer({
      detail: {
        ...successDetail,
        status: 'MISSING_INPUT',
        value: null,
        dataQuality: null,
        formulaVersion: null,
        steps: [],
        reasonCode: 'MISSING_REQUIRED_INPUT',
        missingInputs: ['WCR1/Flow', 'WCR1/PPE'],
      },
    })

    expect(wrapper.text()).toContain('缺少必要输入')
    expect(wrapper.text()).toContain('本分钟未产生指标值')
    expect(wrapper.text()).toContain('异常详情')
    expect(wrapper.text()).toContain('原因码')
    expect(wrapper.text()).toContain('缺失语义键')
    expect(wrapper.text()).toContain('MISSING_REQUIRED_INPUT')
    expect(wrapper.text()).toContain('WCR1/Flow')
    expect(wrapper.text()).toContain('WCR1/PPE')
    expect(wrapper.text()).not.toContain('4.2')
  })

  it.each([
    ['INVALID_INPUT', '输入数据无效'],
    ['ENGINE_ERROR', '公式引擎计算失败'],
  ])('shows the %s failure state', (status, label) => {
    const wrapper = mountDrawer({
      detail: {
        ...successDetail,
        status,
        value: null,
        dataQuality: null,
        reasonCode: `${status}_REASON`,
      },
    })

    expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).toContain(`${status}_REASON`)
  })

  it('shows a distinct no-record state', () => {
    const wrapper = mountDrawer({
      detail: {
        ...successDetail,
        status: 'NO_DATA',
        value: null,
        dataQuality: null,
        formulaVersion: null,
        inputs: [],
        steps: [],
      },
    })

    expect(wrapper.text()).toContain('该分钟没有计算记录')
    expect(wrapper.find('[data-test="retry"]').exists()).toBe(false)
  })

  it('shows local no-data without treating it as an API error', () => {
    const wrapper = mountDrawer({ detail: null, localNoData: true })

    expect(wrapper.text()).toContain('当前指标还没有可查询的分钟记录')
    expect(wrapper.text()).not.toContain('加载失败')
  })

  it.each([
    [409, '历史公式版本暂不受当前服务支持，不能使用新公式解释旧结果'],
    [503, '计算详情暂不可用，请稍后重试'],
    [null, '加载失败，请稍后重试'],
  ])('shows recoverable request error %s', (status, message) => {
    const wrapper = mountDrawer({
      detail: null,
      error: { status, message },
    })

    expect(wrapper.text()).toContain(message)
    expect(wrapper.find('[data-test="retry"]').exists()).toBe(true)
  })

  it('emits close and retry actions', async () => {
    const wrapper = mountDrawer({
      detail: null,
      error: { status: 503, message: '计算详情暂不可用，请稍后重试' },
    })

    await wrapper.find('[data-test="drawer-close"]').trigger('click')
    await wrapper.find('[data-test="retry"]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
