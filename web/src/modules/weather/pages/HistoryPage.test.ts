import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElDatePicker } from '@/shared/ui'
import HistoryPage from './HistoryPage.vue'

vi.mock('../api/weather', () => ({ weatherApi: { daily: vi.fn(async () => []), targets: vi.fn(async () => []) }, accessDenied: () => false, errorCode: () => undefined }))
vi.mock('../composables/useBuildings', async () => {
  const { ref } = await import('vue')
  return { useBuildings: () => ({ selected: ref(''), data: ref([{ buildingId: 'B1', buildingName: 'Building' }]), loading: ref(false), error: ref(''), reload: vi.fn() }) }
})
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.useRealTimers() })
async function render() {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-09-29T12:00:00Z'))
  const wrapper = mount(HistoryPage, { global: { stubs: {
    WeatherToolbar: { emits: ['update:modelValue'], template: '<button class="select-building" @click="$emit(\'update:modelValue\', \'B1\')">select</button>' },
    HourlyChart: { props: ['start'], template: '<section class="hourly" :data-day="start"><slot name="date-controls" /></section>' },
    ChartView: true,
  } } })
  wrappers.push(wrapper)
  await wrapper.find('.select-building').trigger('click'); await flushPromises()
  return wrapper
}
describe('历史小时日期选择', () => {
  it('默认昨天，前后一天切换，禁止进入今天', async () => {
    const wrapper = await render()
    expect(wrapper.find('.hourly').attributes('data-day')).toBe('2026-09-28')
    const buttons = wrapper.find('.hourly').findAll('button')
    const previous = buttons.find(b => b.text() === '前一天')!
    const next = buttons.find(b => b.text() === '后一天')!
    expect(next.attributes('disabled')).toBeDefined()
    await previous.trigger('click')
    expect(wrapper.find('.hourly').attributes('data-day')).toBe('2026-09-27')
    await next.trigger('click')
    expect(wrapper.find('.hourly').attributes('data-day')).toBe('2026-09-28')
  })
  it('可独立选择更早日期，拒绝今天、未来和无效值', async () => {
    const wrapper = await render()
    const picker = wrapper.findAllComponents(ElDatePicker).at(-1)!
    picker.vm.$emit('update:modelValue', '2024-01-01'); await flushPromises()
    expect(wrapper.find('.hourly').attributes('data-day')).toBe('2024-01-01')
    expect(wrapper.find('.hourly').findAll('button').find(b => b.text() === '前一天')!.attributes('disabled')).toBeDefined()
    for (const invalid of ['2026-09-29', '2026-09-30', '2023-12-31', null]) {
      picker.vm.$emit('update:modelValue', invalid); await flushPromises()
      expect(wrapper.find('.hourly').attributes('data-day')).toBe('2024-01-01')
    }
  })
})
