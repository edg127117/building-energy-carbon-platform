import { expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { carbonPlanApi, type CarbonPlanVersion, type CarbonPlanBaseline } from '../api/carbon-plan'
import CarbonPlanPanel from './CarbonPlanPanel.vue'

vi.mock('../api/carbon-plan', () => ({ carbonPlanApi: { versions: vi.fn(), baseline: vi.fn(), create: vi.fn(), save: vi.fn(), transition: vi.fn(), importPreview: vi.fn() } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElButton: { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElEmpty: { props: ['description'], template: '<p>{{ description }}</p>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElSelect: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  ElTag: { template: '<span><slot /></span>' },
  ElTable: { template: '<div><slot /></div>' }, ElTableColumn: { template: '<div />' },
}))
it('previews fixed baseline once, saves explicit reduction and never displays a production sample by default', async () => {
  const draft = { versionId: 'v', revision: 0, status: 'DRAFT', configuration: null } as CarbonPlanVersion
  vi.mocked(carbonPlanApi.versions).mockResolvedValue([draft])
  vi.mocked(carbonPlanApi.baseline).mockResolvedValue({ annualBaseline: '160', source: 'TEST ONLY', boundary: 'TEST', months: [], evidenceHash: 'test-hash' } as unknown as CarbonPlanBaseline)
  const wrapper = mount(CarbonPlanPanel, { props: { buildingId: 'b' } })
  await flushPromises()
  expect(wrapper.text()).not.toContain('160')
  await wrapper.findAll('button').find(b => b.text() === '读取全年基准')!.trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('160')
  const label = (name: string) => wrapper.findAll('label').find(l => l.text().startsWith(name))!
  await label('计划消减率').find('input').setValue('15')
  await label('操作说明').find('input').setValue('隔离模拟测试')
  vi.mocked(carbonPlanApi.save).mockResolvedValue({ ...draft, revision: 1 })
  await wrapper.findAll('button').find(b => b.text() === '保存计划')!.trigger('click')
  await flushPromises()
  expect(carbonPlanApi.save).toHaveBeenCalledWith('b', expect.objectContaining({ revision: 0 }), expect.objectContaining({ reductionPercent: '15', baseline: expect.objectContaining({ annualBaseline: '160' }) }), '隔离模拟测试')
  wrapper.unmount()
})
