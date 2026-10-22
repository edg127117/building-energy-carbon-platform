import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { TransportError } from '@/infrastructure/http/public'
import ReadingBatchReviewPanel from './ReadingBatchReviewPanel.vue'
import { classificationApi } from '../api/classification'

vi.mock('../api/classification', () => ({
  classificationApi: { getReadingBatch: vi.fn(), approveReadingBatch: vi.fn() },
}))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElDescriptions: { template: '<dl><slot /></dl>' },
  ElDescriptionsItem: { props: ['label'], template: '<div><dt>{{ label }}</dt><dd><slot /></dd></div>' },
  ElDialog: { name: 'ElDialog', props: ['modelValue', 'title'], emits: ['close'], template: '<section v-if="modelValue"><h2>{{ title }}</h2><slot /><footer><slot name="footer" /></footer></section>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElSkeleton: { template: '<div />' },
  ElTag: { template: '<span><slot /></span>' },
}))

function detail(status = 'PENDING_REVIEW') {
  return { batchId: 'BATCH-1', buildingId: 'B-1', status, revision: 7, createdBy: 18, count: 2 }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function mountPanel() {
  return mount(ReadingBatchReviewPanel, { props: { buildingId: 'B-1', batchId: 'BATCH-1' } })
}

async function loadDetail() {
  await vi.waitFor(() => expect(classificationApi.getReadingBatch).toHaveBeenCalledWith('B-1', 'BATCH-1'))
  await flushPromises()
}

describe('已入库读数批次独立审核面板', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(classificationApi.getReadingBatch).mockResolvedValue(detail() as never)
    vi.mocked(classificationApi.approveReadingBatch).mockResolvedValue({ batchId: 'BATCH-1', status: 'APPROVED', accepted: 2, issues: [] } as never)
  })

  it('先读取服务端批次详情，再以实际修订号提交独立审核原因', async () => {
    const wrapper = mountPanel()
    await loadDetail()

    expect(wrapper.text()).toContain('PENDING_REVIEW')
    expect(wrapper.text()).toContain('18')
    expect(wrapper.text()).toContain('7')
    expect(wrapper.text()).toContain('2')
    expect(wrapper.find('[data-test="reading-batch-review-submit"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="reading-batch-review-reason"]').setValue('已由独立审核人核对来源与测点')
    await wrapper.find('[data-test="reading-batch-review-submit"]').trigger('click')
    await flushPromises()

    expect(classificationApi.approveReadingBatch).toHaveBeenCalledWith('B-1', 'BATCH-1', {
      expectedRevision: 7, reason: '已由独立审核人核对来源与测点', effectiveFrom: null,
    })
    expect(wrapper.emitted('approved')).toEqual([[{ batchId: 'BATCH-1', status: 'APPROVED', accepted: 2, issues: [] }]])
    expect(wrapper.text()).toContain('APPROVED')
    wrapper.unmount()
  })

  it('只有待审核批次提供操作入口', async () => {
    vi.mocked(classificationApi.getReadingBatch).mockResolvedValue(detail('APPROVED') as never)
    const wrapper = mountPanel()
    await loadDetail()

    expect(wrapper.text()).toContain('当前批次不是待审核状态')
    expect(wrapper.find('[data-test="reading-batch-review-reason"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="reading-batch-review-submit"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('服务端拒绝同人审核时保留待审核详情和失败提示', async () => {
    vi.mocked(classificationApi.approveReadingBatch).mockRejectedValue(new TransportError('forbidden', 403, 403, 'INDEPENDENT_REVIEW_REQUIRED'))
    const wrapper = mountPanel()
    await loadDetail()

    await wrapper.find('[data-test="reading-batch-review-reason"]').setValue('尝试审核')
    await wrapper.find('[data-test="reading-batch-review-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('当前账号无权访问此内容。')
    expect(wrapper.text()).toContain('PENDING_REVIEW')
    expect(wrapper.emitted('approved')).toBeUndefined()
    wrapper.unmount()
  })

  it('关闭迟到审核后重新打开会释放保存状态，并忽略旧响应', async () => {
    const delayed = deferred<{ batchId: string; status: string; accepted: number; issues: [] }>()
    vi.mocked(classificationApi.approveReadingBatch)
      .mockReturnValueOnce(delayed.promise as never)
      .mockResolvedValueOnce({ batchId: 'BATCH-1', status: 'APPROVED', accepted: 2, issues: [] } as never)
    const wrapper = mountPanel()
    await loadDetail()

    await wrapper.find('[data-test="reading-batch-review-reason"]').setValue('旧建筑审核')
    await wrapper.find('[data-test="reading-batch-review-submit"]').trigger('click')
    expect(wrapper.find('[data-test="reading-batch-review-submit"]').attributes('disabled')).toBeDefined()

    wrapper.findComponent({ name: 'ElDialog' }).vm.$emit('close')
    expect(wrapper.emitted('close')).toEqual([[]])
    await wrapper.setProps({ batchId: '' })
    await wrapper.setProps({ batchId: 'BATCH-1' })
    await vi.waitFor(() => expect(classificationApi.getReadingBatch).toHaveBeenCalledTimes(2))
    await flushPromises()

    await wrapper.find('[data-test="reading-batch-review-reason"]').setValue('新建筑独立审核')
    expect(wrapper.find('[data-test="reading-batch-review-submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-test="reading-batch-review-submit"]').trigger('click')
    await flushPromises()
    delayed.resolve({ batchId: 'BATCH-1', status: 'APPROVED', accepted: 2, issues: [] })
    await flushPromises()

    expect(classificationApi.approveReadingBatch).toHaveBeenLastCalledWith('B-1', 'BATCH-1', {
      expectedRevision: 7, reason: '新建筑独立审核', effectiveFrom: null,
    })
    expect(wrapper.emitted('approved')).toEqual([[{ batchId: 'BATCH-1', status: 'APPROVED', accepted: 2, issues: [] }]])
    wrapper.unmount()
  })
})
