import { expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LocationEditor from './LocationEditor.vue'
import { weatherApi } from '../api/weather'
import type { Binding } from '../models/weather'
import { ElForm, ElOption } from '@/shared/ui'
vi.mock('../api/weather', () => ({ weatherApi: { bind: vi.fn() }, errorCode: () => undefined }))
it('keeps the form opening version when a background refresh changes the latest binding', async () => {
  const binding: Binding = { id: 'v2', buildingId: 'b', version: 2, name: '测试位置', latitude: 32, longitude: 118, cityCode: null, coverage: 'CITY', effectiveFrom: '2024-01-01T16:00:00Z', effectiveTo: null, enabled: true }
  vi.mocked(weatherApi.bind).mockResolvedValue({ ...binding, version: 4 })
  const wrapper = mount(LocationEditor, { props: { open: false, building: 'b', binding }, global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } } })
  await wrapper.setProps({ open: true })
  await wrapper.setProps({ binding: { ...binding, version: 3 } })
  const save = wrapper.findAll('button').find(button => button.text() === '保存')!
  await save.trigger('click'); await flushPromises()
  expect(weatherApi.bind).toHaveBeenCalledWith('b', expect.objectContaining({ expectedVersion: 2, latitude: 32, longitude: 118 }), expect.any(String))
  const request = vi.mocked(weatherApi.bind).mock.calls[0][1]
  expect(new Date(request.effectiveFrom).getUTCHours()).toBe(16)
  expect(wrapper.emitted('saved')).toHaveLength(1)
  wrapper.unmount()
})

it('only offers supported location coverage and freezes inputs while saving', async () => {
  let finish!: (value: Binding) => void
  vi.mocked(weatherApi.bind).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const binding: Binding = { id: 'v1', buildingId: 'b', version: 1, name: '位置', latitude: 32, longitude: 118, cityCode: null, coverage: 'DISTRICT', effectiveFrom: '2024-01-01T16:00:00Z', effectiveTo: null, enabled: true }
  const wrapper = mount(LocationEditor, { props: { open: false, building: 'b', binding }, global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } } })
  await wrapper.setProps({ open: true })
  expect(wrapper.findAllComponents(ElOption).map(option => option.props('value'))).toEqual(['DISTRICT', 'CITY'])
  await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click')
  expect(wrapper.findComponent(ElForm).props('disabled')).toBe(true)
  finish(binding); await flushPromises()
  expect(wrapper.findComponent(ElForm).props('disabled')).toBe(false)
  wrapper.unmount()
})
