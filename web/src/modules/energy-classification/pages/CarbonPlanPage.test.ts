import { expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import CarbonPlanPage from './CarbonPlanPage.vue'

const search = vi.hoisted(() => vi.fn())
vi.mock('../composables/use-classification-buildings', () => ({ useClassificationBuildings: () => ({
  records: ref([{ buildingId: 'authorized-building', buildingName: '已授权建筑' }]), total: ref(1), page: ref(1), loading: ref(false), error: ref(''), search,
}) }))
vi.mock('../components/CarbonPlanPanel.vue', () => ({ default: { name: 'CarbonPlanPanel', props: ['buildingId'], emits: ['dirtyChange'], template: '<div data-test="plan">{{ buildingId }}</div>' } }))
vi.mock('@/shared/ui', () => ({
  ElCard: { template: '<section><slot /></section>' }, ElFormItem: { template: '<label><slot /></label>' },
  ElSelect: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElPagination: { template: '<div />' }, ElAlert: { template: '<div />' },
}))
it('uses existing authorized building choices and prevents switching while plan is dirty', async () => {
  const wrapper = mount(CarbonPlanPage)
  await flushPromises()
  expect(search).toHaveBeenCalledOnce()
  expect(wrapper.get('[data-test="plan"]').text()).toBe('')
  await wrapper.get('select').setValue('authorized-building')
  expect(wrapper.get('[data-test="plan"]').text()).toBe('authorized-building')
  wrapper.findComponent({ name: 'CarbonPlanPanel' }).vm.$emit('dirtyChange', true)
  await flushPromises()
  expect(wrapper.get('select').attributes()).toHaveProperty('disabled')
  wrapper.unmount()
})
