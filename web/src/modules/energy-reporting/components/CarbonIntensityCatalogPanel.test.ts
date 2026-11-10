import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ElButton, ElSelect, ElDatePicker } from '@/shared/ui'
import { TransportError } from '@/infrastructure/http/public'
import { listIntensityCatalog, saveIntensityCatalog, type IntensityCatalogVersion } from '../api/intensity-catalog'
import CarbonIntensityCatalogPanel from './CarbonIntensityCatalogPanel.vue'
const session = vi.hoisted(() => ({ user: { roles: ['PLATFORM_ADMIN'] } }))
vi.mock('@/modules/auth/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/auth/public')>()), useSession: () => session }))
vi.mock('../api/intensity-catalog', () => ({ listIntensityCatalog: vi.fn(), saveIntensityCatalog: vi.fn(), parseIntensityRows: vi.fn() }))
describe('集中碳强度目录配置', () => {
  beforeEach(() => { session.user.roles = ['PLATFORM_ADMIN']; vi.mocked(listIntensityCatalog).mockReset().mockResolvedValue([]); vi.mocked(saveIntensityCatalog).mockReset() })
  it('未知适用时间可直接保存且没有审批操作；日期只在明确时间时显示', async () => {
    const wrapper = mount(CarbonIntensityCatalogPanel)
    await flushPromises()
    expect(wrapper.findAllComponents(ElDatePicker)).toHaveLength(1)
    for (const [field, value] of Object.entries({ 'standard-name': 'test source', 'standard-version': 'v1', 'standard-source': 'test://source', 'rule-region': '4501', 'rule-category': 'office', 'rule-constraint': '60', 'rule-baseline': '40', 'rule-guide': '20' })) {
      await wrapper.get(`[data-test="${field}"]`).setValue(value)
    }
    wrapper.findAllComponents(ElSelect)[0].vm.$emit('update:modelValue', 'CO2_ONLY')
    vi.mocked(saveIntensityCatalog).mockImplementation(async input => ({ ...input, catalogId: 'catalog', versionId: 'v1', revision: 1, createdBy: 7, createdAt: null, evidenceHash: 'test-hash' }))
    await wrapper.findAllComponents(ElButton).find(b => b.text() === '保存新版本')!.trigger('click')
    await flushPromises()
    expect(saveIntensityCatalog).toHaveBeenCalledWith(expect.objectContaining({ entries: [expect.objectContaining({ timeStatus: 'PENDING', applicableFrom: null, applicableTo: null, constraintValue: '60' })] }))
    expect(wrapper.text()).not.toContain('提交审核')
    wrapper.findAllComponents(ElSelect)[1].vm.$emit('update:modelValue', 'KNOWN')
    await flushPromises()
    expect(wrapper.findAllComponents(ElDatePicker)).toHaveLength(3)
    wrapper.unmount()
  })
  it('业主仅查阅目录，维护入口按角色收紧', async () => {
    session.user.roles = ['BUILDING_OWNER']
    const wrapper = mount(CarbonIntensityCatalogPanel)
    await flushPromises()
    expect(wrapper.find('[data-testid="intensity-catalog-form"]').exists()).toBe(false)
    expect(wrapper.findAllComponents(ElButton).map(b => b.text())).toEqual(['刷新目录'])
    wrapper.unmount()
  })
  it('目录异常显示具体恢复动作，不透传后端异常原文', async () => {
    vi.mocked(listIntensityCatalog).mockRejectedValueOnce(new TransportError('request', 409, 409, 'CARBON_INTENSITY_CATALOG_EVIDENCE_INVALID'))
    const wrapper = mount(CarbonIntensityCatalogPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('目录内容与版本摘要不一致，请联系平台维护人员核对。')
    wrapper.unmount()
  })
  it('目录可独立维护，不要求选择建筑、项目使用时间或选用依据', async () => {
    const source: IntensityCatalogVersion = { catalogId: 'source', versionId: 'source-v1', revision: 1, name: 'source', documentVersion: 'v1', sourceReference: 'test://source', publishedOn: null, createdBy: 7, createdAt: null, evidenceHash: 'h',
      entries: [{ regionPrefix: '4501', buildingCategory: 'office', subcategory: null, climateLabel: 'zone', constraintValue: '56.71', baselineValue: '35.56', guideValue: '20.72', gasBasis: 'CO2_ONLY', boundaryCode: 'STANDARD_SPECIFIC', areaBasis: 'BUILDING_AREA', boundaryNote: '', areaNote: '', timeStatus: 'PENDING', applicableFrom: null, applicableTo: null }] }
    vi.mocked(listIntensityCatalog).mockResolvedValue([source])
    const wrapper = mount(CarbonIntensityCatalogPanel)
    await flushPromises()
    expect(listIntensityCatalog).toHaveBeenCalledWith(expect.any(AbortSignal))
    expect(wrapper.text()).toContain('唯一匹配后自动展示参考比例')
    expect(wrapper.find('[data-testid="intensity-project-use"]').exists()).toBe(false)
    for (const label of ['本项目选用', '项目使用开始', '项目选用依据']) expect(wrapper.text()).not.toContain(label)
    expect(wrapper.findAllComponents(ElDatePicker)).toHaveLength(1)
    wrapper.unmount()
  })
})
