import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ElButton, ElRadioGroup, ElSelect } from '@/shared/ui'
import type { PendingDevice } from '../models/onboarding'
import TemperatureBatchDialog from './TemperatureBatchDialog.vue'

const rows: PendingDevice[] = [{
  pendingId: 'D1', status: 'BOUND', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2',
  identityStatus: 'ACTIVE', maskedIdentityValue: '****', lastProfileVersion: 2, reportCount: 1,
  firstSeenTime: 0, lastSeenTime: 0, sampleTruncated: false,
}]
const plans = [{
  pendingId: 'D1', buildingId: 'B1', mode: 'AUTO' as const, templateProductId: 'T1', templateName: '大金双温度模板',
  numericSourceId: 'S1', status: 'READY' as const, message: null, digest: 'digest-1', expiresAt: 1,
  points: [{ metricCode: 'roomTemp', semantic: '室温', unit: '°C', action: 'CREATE' as const, pointId: null, pointCode: null, pointName: null }],
}]

describe('温度补齐任务弹窗', () => {
  it('只能使用服务端预览的摘要提交批量任务', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: { open: true, rows, plans, options: { templates: [], numericSources: [{ sourceId: 'S1', sourceName: '来源一' }], rules: [] } },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('可提交')
    expect(wrapper.findAllComponents(ElSelect)).toHaveLength(0)
    const submit = wrapper.findAllComponents(ElButton).find(button => button.text() === '提交温度补齐任务')!
    expect(submit.attributes('disabled')).toBeUndefined()
    await submit.trigger('click')
    expect(wrapper.emitted('submit')).toEqual([[{ mode: 'AUTO' }]])
    wrapper.unmount()
  })

  it('重试仅保留提交失败、无审批请求的待重试项和缓存刷新项', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true,
        rows: ['D1', 'D2', 'D3', 'D4', 'D5', 'D6'].map(pendingId => ({ ...rows[0]!, pendingId })),
        plans,
        job: {
          jobId: 'JOB-1',
          items: [
            { pendingId: 'D1', requestId: null, configurationStatus: 'FAILED', message: null, samplingStatus: 'WAITING_CONFIGURATION' },
            { pendingId: 'D2', requestId: null, configurationStatus: 'PENDING', message: null, samplingStatus: 'WAITING_CONFIGURATION' },
            { pendingId: 'D3', requestId: 'R3', configurationStatus: 'CACHE_PENDING', message: null, samplingStatus: 'WAITING_CONFIGURATION' },
            { pendingId: 'D4', requestId: 'R4', configurationStatus: 'EXECUTION_FAILED', message: null, samplingStatus: 'WAITING_CONFIGURATION' },
            { pendingId: 'D5', requestId: 'R5', configurationStatus: 'PLAN_EXPIRED', message: null, samplingStatus: 'WAITING_CONFIGURATION' },
            { pendingId: 'D6', requestId: 'R6', configurationStatus: 'FAILED', message: null, samplingStatus: 'WAITING_CONFIGURATION' },
          ],
        },
      },
      attachTo: document.body,
    })
    await flushPromises()
    const retry = wrapper.findAllComponents(ElButton).find(button => button.text() === '重试失败项')!
    await retry.trigger('click')
    expect(wrapper.emitted('retry-job')).toEqual([[['D1', 'D2', 'D3']]])
    expect(wrapper.findAllComponents(ElButton).some(button => button.text() === '重新预览并新建任务')).toBe(true)
    wrapper.unmount()
  })

  it('恢复任务与当前选择不一致时要求明确新建任务', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true,
        rows: [...rows, { ...rows[0]!, pendingId: 'D2' }],
        plans,
        job: { jobId: 'JOB-1', items: [{ pendingId: 'D1', requestId: 'R1', configurationStatus: 'PENDING_REVIEW', message: null, samplingStatus: 'WAITING_CONFIGURATION' }] },
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('已恢复的任务与当前所选设备不一致')
    const create = wrapper.findAllComponents(ElButton).find(button => button.text() === '按当前选择新建任务')!
    await create.trigger('click')
    expect(wrapper.emitted('new-task')).toEqual([[]])
    wrapper.unmount()
  })

  it('无数值来源时自动选中唯一模板，先预览来源计划，再一次提交初始化审批', async () => {
    const initializationPreview = {
      buildingId: 'B1', sourceScope: 'DAIKIN', sourceId: 'SOURCE-NEW', sourceName: '大金来源-B1',
      templateProductId: 'T1', digest: 'batch-digest', expiresAt: 4_102_444_800_000,
      plans: [{ ...plans[0]!, numericSourceId: 'SOURCE-NEW' }],
    }
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true,
        rows,
        platformAdmin: true,
        options: { templates: [{ productId: 'T1', productName: '大金双温度模板' }], numericSources: [], rules: [] },
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('一次审批创建来源、首批双温度测点和大金来源匹配规则')
    expect(wrapper.text()).not.toContain('自动匹配规则审批申请')
    const template = wrapper.findComponent(ElSelect)
    expect(template.props('modelValue')).toBe('T1')
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '预览首次初始化')!.trigger('click')
    expect(wrapper.emitted('initialization-preview')).toEqual([['T1']])

    await wrapper.setProps({ initializationPreview })
    await flushPromises()
    expect(wrapper.text()).toContain('大金来源-B1 · SOURCE-NEW')
    const submit = wrapper.findAllComponents(ElButton).find(button => button.text() === '提交首次初始化审批')!
    expect(submit.attributes('disabled')).toBeUndefined()
    await submit.trigger('click')
    expect(wrapper.emitted('initialization-submit')).toEqual([[initializationPreview]])
    wrapper.unmount()
  })

  it('首次初始化预览过期后禁止提交并明确要求重新预览', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true,
        rows,
        platformAdmin: true,
        options: { templates: [{ productId: 'T1', productName: '大金双温度模板' }], numericSources: [], rules: [] },
        initializationPreview: {
          buildingId: 'B1', sourceScope: 'DAIKIN', sourceId: 'SOURCE-NEW', sourceName: '大金来源-B1',
          templateProductId: 'T1', digest: 'batch-digest', expiresAt: 1, plans,
        },
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('首次初始化预览已过期，请重新预览后提交')
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '提交首次初始化审批')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('初始化预览必须逐台覆盖当前所选设备', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true,
        rows: [...rows, { ...rows[0]!, pendingId: 'D2' }],
        platformAdmin: true,
        options: { templates: [{ productId: 'T1', productName: '大金双温度模板' }], numericSources: [], rules: [] },
        initializationPreview: {
          buildingId: 'B1', sourceScope: 'DAIKIN', sourceId: 'SOURCE-NEW', sourceName: '大金来源-B1',
          templateProductId: 'T1', digest: 'batch-digest', expiresAt: 4_102_444_800_000,
          plans: [{ ...plans[0]!, pendingId: 'D3' }, { ...plans[0]!, pendingId: 'D4' }],
        },
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '提交首次初始化审批')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('非平台管理员不能预览或提交首次初始化', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true, rows,
        options: { templates: [{ productId: 'T1', productName: '大金双温度模板' }], numericSources: [], rules: [] },
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('首次初始化需要平台管理员权限')
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '预览首次初始化')!.attributes('disabled')).toBeDefined()
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '提交首次初始化审批')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('选项仍在加载时不允许走普通批量温度提交', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: { open: true, rows, plans, optionsLoading: true },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '预览温度测点')!.attributes('disabled')).toBeDefined()
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '提交温度补齐任务')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('批量人工模式不共享已有测点映射', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true,
        rows: [...rows, { ...rows[0]!, pendingId: 'D2' }],
        plans: [...plans, { ...plans[0]!, pendingId: 'D2', digest: 'digest-2' }],
        options: { templates: [{ productId: 'T1', productName: '大金双温度模板' }], numericSources: [{ sourceId: 'S1', sourceName: '来源一' }], rules: [] },
      },
      attachTo: document.body,
    })
    await flushPromises()
    const mode = wrapper.findAllComponents(ElRadioGroup)[0]!
    mode.vm.$emit('update:modelValue', 'MANUAL')
    mode.vm.$emit('change', 'MANUAL')
    await flushPromises()
    expect(wrapper.text()).toContain('批量人工模式只能统一选择兼容模板和来源')
    wrapper.unmount()
  })

  it('选择已有规则时保留修订并可提交停用申请', async () => {
    const wrapper = mount(TemperatureBatchDialog, {
      props: {
        open: true,
        rows,
        plans,
        platformAdmin: true,
        ruleResult: { requestId: 'R-1', status: 'PENDING_REVIEW' },
        options: {
          templates: [{ productId: 'T1', productName: '大金双温度模板' }],
          numericSources: [{ sourceId: 'S1', sourceName: '来源一' }],
          rules: [{ ruleId: 'RULE-1', adapterId: 'DAIKIN_INDOOR_V2', buildingId: 'B1', sourceScope: 'SOURCE-A', model: '', templateProductId: 'T1', numericSourceId: 'S1', revision: 4, enabled: true }],
        },
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('待审核')
    expect(wrapper.text()).not.toContain('PENDING_REVIEW')
    const ruleSelect = wrapper.findAllComponents(ElSelect)[0]!
    ruleSelect.vm.$emit('update:modelValue', 'RULE-1')
    ruleSelect.vm.$emit('change', 'RULE-1')
    await flushPromises()
    const status = wrapper.findAllComponents(ElRadioGroup).at(-1)!
    status.vm.$emit('update:modelValue', false)
    status.vm.$emit('change', false)
    await flushPromises()
    const submit = wrapper.findAllComponents(ElButton).find(button => button.text() === '提交规则审批申请')!
    await submit.trigger('click')
    expect(wrapper.emitted('rule-submit')).toEqual([[{
      ruleId: 'RULE-1', adapterId: 'DAIKIN_INDOOR_V2', buildingId: 'B1', sourceScope: 'SOURCE-A', model: '',
      templateProductId: 'T1', numericSourceId: 'S1', revision: 4, enabled: false,
    }]])
    wrapper.unmount()
  })
})
