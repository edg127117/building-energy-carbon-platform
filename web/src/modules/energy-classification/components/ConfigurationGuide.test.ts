import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { t } from '@/locales'
import { configurationGuideApi, type ConfigurationGuide as GuideResponse } from '../api/configuration-guide'
import ConfigurationGuide from './ConfigurationGuide.vue'

vi.mock('../api/configuration-guide', () => ({ configurationGuideApi: { get: vi.fn() } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElDescriptions: { template: '<dl><slot /></dl>' },
  ElDescriptionsItem: { props: ['label'], template: '<div><dt>{{ label }}</dt><dd><slot /></dd></div>' },
  ElEmpty: { props: ['description'], template: '<div data-test="guide-empty">{{ description }}</div>' },
  ElSkeleton: { template: '<div data-test="guide-loading" />' },
  ElTag: { template: '<span><slot /></span>' },
}))

const props = { buildingId: 'B-1', versionId: 'P-1', revision: 3, dirty: false }

function guide(overrides: Partial<GuideResponse> = {}): GuideResponse {
  return {
    ...props,
    calculationStatus: 'NOT_CHECKED',
    steps: [
      {
        domain: 'RELATION',
        reference: 'relation/1',
        revision: 7,
        state: 'REVIEW_REQUIRED',
        responsibility: 'PLATFORM_ADMIN',
        action: 'REVIEW',
        issues: [{ code: 'RELATION_REVIEW_REQUIRED', reference: 'relation/1', message: '关系版本需要审核。' }],
      },
      {
        domain: 'FACTORS',
        reference: null,
        revision: null,
        state: 'NOT_CHECKED',
        responsibility: 'CARBON_RULE_MAINTAINER',
        action: 'VERIFY_FACTORS',
        issues: [],
      },
    ],
    ...overrides,
  }
}

describe('配置引导', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('只在手动操作时读取服务端引导，映射中文状态并仅为可处理域发出定位事件', async () => {
    vi.mocked(configurationGuideApi.get).mockResolvedValue(guide())
    const wrapper = mount(ConfigurationGuide, { props })

    expect(configurationGuideApi.get).not.toHaveBeenCalled()
    await wrapper.find('[data-test="guide-load"]').trigger('click')
    await flushPromises()

    expect(configurationGuideApi.get).toHaveBeenCalledWith('B-1', 'P-1', expect.any(AbortSignal))
    expect(wrapper.text()).toContain('需要审核')
    expect(wrapper.text()).toContain('平台管理员')
    expect(wrapper.text()).toContain('审核')
    expect(wrapper.text()).toContain('关系版本需要审核。')
    expect(wrapper.find('.step-heading').text()).not.toContain('REVIEW_REQUIRED')
    expect(wrapper.find('[data-test="guide-reference-RELATION"]').text()).toBe('relation/1')
    expect(wrapper.find('[data-test="guide-technical-details"]').attributes('open')).toBeUndefined()
    expect(wrapper.find('[data-test="guide-step-technical-RELATION"]').attributes('open')).toBeUndefined()
    expect(wrapper.find('[data-test="guide-issue-message-RELATION-0"]').text()).toBe('关系版本需要审核。')
    expect(wrapper.find('[data-test="guide-issue-code-RELATION-0"]').text()).toBe('RELATION_REVIEW_REQUIRED')
    await wrapper.find('[data-test="guide-navigate-RELATION"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['RELATION']])
    expect(wrapper.find('[data-test="guide-navigate-FACTORS"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('有未保存修改时禁用读取，不把浏览器中的草稿传给服务端', async () => {
    const wrapper = mount(ConfigurationGuide, { props: { ...props, dirty: true } })

    expect(wrapper.find('[data-test="guide-load"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="guide-load"]').trigger('click')
    expect(configurationGuideApi.get).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each([
    ['建筑', { buildingId: 'B-2' }],
    ['版本', { versionId: 'P-2' }],
    ['修订', { revision: 4 }],
    ['未保存状态', { dirty: true }],
  ])('在%s变化后忽略迟到的旧引导结果', async (_name, changedProps) => {
    let resolve!: (value: GuideResponse) => void
    vi.mocked(configurationGuideApi.get).mockImplementation(() => new Promise<GuideResponse>(done => { resolve = done }))
    const wrapper = mount(ConfigurationGuide, { props })

    await wrapper.find('[data-test="guide-load"]').trigger('click')
    await wrapper.setProps(changedProps)
    resolve(guide())
    await flushPromises()

    expect(wrapper.find('[data-test="guide-reference-RELATION"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('服务端返回的身份或修订不匹配时不展示旧引导', async () => {
    vi.mocked(configurationGuideApi.get).mockResolvedValue(guide({ revision: 4 }))
    const wrapper = mount(ConfigurationGuide, { props })

    await wrapper.find('[data-test="guide-load"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('引导返回的建筑、版本或修订已变化')
    expect(wrapper.find('[data-test="guide-reference-RELATION"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('为后端状态、责任人和操作提供中文映射', () => {
    expect([
      'MISSING', 'BLOCKED', 'REVIEW_REQUIRED', 'ACTIVATION_REQUIRED', 'CONFIGURED', 'NOT_CHECKED', 'RESERVED',
    ].map(state => t('energyClassification.configurationGuide.states.' + state))).toEqual([
      '缺少配置', '受阻', '需要审核', '需要激活', '已配置', '尚未检查', '预留',
    ])
    expect([
      'ENERGY_MANAGER', 'INDEPENDENT_REVIEWER', 'RULE_ACTIVATOR', 'CARBON_RULE_MAINTAINER', 'PLATFORM_ADMIN',
    ].map(responsibility => t('energyClassification.configurationGuide.responsibilities.' + responsibility))).toEqual([
      '能源管理员', '独立审核人', '规则激活人', '碳规则维护人', '平台管理员',
    ])
    expect([
      'CONFIGURE', 'RESOLVE_ISSUES', 'SUBMIT', 'REVIEW', 'ACTIVATE', 'VIEW', 'VERIFY_SEALED_MONTH', 'VERIFY_FACTORS',
    ].map(action => t('energyClassification.configurationGuide.actions.' + action))).toEqual([
      '配置', '处理问题', '提交审核', '审核', '激活', '查看', '核验封账月份', '核验排放因子',
    ])
  })
})
