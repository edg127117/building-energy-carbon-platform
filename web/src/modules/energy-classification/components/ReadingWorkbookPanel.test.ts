import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { TransportError } from '@/infrastructure/http/public'
import ReadingWorkbookPanel from './ReadingWorkbookPanel.vue'
import { classificationApi } from '../api/classification'
import { readingWorkbookApi } from '../api/reading-workbook'

vi.mock('../api/classification', () => ({ classificationApi: { importReadings: vi.fn() } }))
vi.mock('../api/reading-workbook', () => ({ readingWorkbookApi: { template: vi.fn(), preview: vi.fn() } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElSpace: { template: '<div><slot /></div>' },
}))

const request = {
  idempotencyKey: 'PREVIEW-KEY', reason: '来源依据', readings: [{
    pointId: 'P-1', startInclusive: '2026-08-01T00:00:00Z', endExclusive: '2026-09-01T00:00:00Z', energyItemCode: 'ELEC', unit: 'kWh',
    semantics: 'PERIOD_TOTAL', startReading: null, endReading: null, quantity: '12', multiplier: '1', alreadyScaled: false,
    status: 'PENDING_EXPERT', sourceReference: '来源', timePrecision: 'EXACT', supersedesFactId: null,
  }],
}

function validPreview() {
  return { request, preview: { batchId: '', status: 'PREVIEW', accepted: 0, issues: [] } }
}

function mountPanel(buildingId = 'B-1') {
  return mount(ReadingWorkbookPanel, { props: { buildingId } })
}

async function selectFile(wrapper: ReturnType<typeof mount>, file: File) {
  const input = wrapper.find('[data-test="workbook-file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
}

async function preparePreview(wrapper: ReturnType<typeof mount>, file: File, reason = '来源依据') {
  await selectFile(wrapper, file)
  await wrapper.find('[data-test="workbook-reason"]').setValue(reason)
  await wrapper.find('[data-test="workbook-preview"]').trigger('click')
  await flushPromises()
}

afterEach(() => vi.unstubAllGlobals())

describe('Excel 人工来源预检面板', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.stubGlobal('crypto', { randomUUID: vi.fn(() => 'CLIENT-KEY') })
    vi.mocked(readingWorkbookApi.preview).mockResolvedValue(validPreview() as never)
    vi.mocked(classificationApi.importReadings).mockResolvedValue({ batchId: 'BATCH-1', status: 'PENDING_REVIEW', accepted: 1, issues: [] } as never)
  })

  it('HTTP 环境没有 randomUUID 时可初始化和切换建筑，新预检使用不同键', async () => {
    let sequence = 0
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => { bytes.fill(++sequence); return bytes } })
    const wrapper = mountPanel('B-1')
    await preparePreview(wrapper, new File(['fixture'], 'readings.xlsx'))
    const firstKey = vi.mocked(readingWorkbookApi.preview).mock.calls[0][2]
    expect(firstKey).toMatch(/^[0-9a-f-]{36}$/)
    await wrapper.setProps({ buildingId: 'B-2' })
    await preparePreview(wrapper, new File(['fixture'], 'readings.xlsx'))
    const secondKey = vi.mocked(readingWorkbookApi.preview).mock.calls[1][2]
    expect(secondKey).not.toBe(firstKey)
    expect(classificationApi.importReadings).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('预检后修改原因或重新选择同元数据文件都会作废导入资格', async () => {
    const first = new File(['fixture'], 'readings.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', lastModified: 1 })
    const sameMetadataReplacement = new File(['fixture'], 'readings.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', lastModified: 1 })
    const wrapper = mountPanel()

    await preparePreview(wrapper, first)
    expect(wrapper.find('[data-test="workbook-import"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('[data-test="workbook-reason"]').setValue('修订后的来源依据')
    expect(wrapper.find('[data-test="workbook-import"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="workbook-preview"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="workbook-import"]').attributes('disabled')).toBeUndefined()

    await selectFile(wrapper, sameMetadataReplacement)
    expect(wrapper.find('[data-test="workbook-import"]').attributes('disabled')).toBeDefined()
    expect(classificationApi.importReadings).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('后端返回 INVALID 时清除可导入请求，绝不调用导入接口', async () => {
    vi.mocked(readingWorkbookApi.preview).mockResolvedValue({
      request,
      preview: { batchId: '', status: 'INVALID', accepted: 0, issues: [{ severity: 'ERROR', code: 'INVALID', reference: '2', message: '无效行' }] },
    } as never)
    const wrapper = mountPanel()
    await preparePreview(wrapper, new File(['fixture'], 'readings.xlsx'))

    expect(wrapper.text()).toContain('INVALID')
    expect(wrapper.find('[data-test="workbook-import"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="workbook-import"]').trigger('click')
    expect(classificationApi.importReadings).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('导入成功将服务端批次结果作为事件载荷交给工作区', async () => {
    const wrapper = mountPanel()
    await preparePreview(wrapper, new File(['fixture'], 'readings.xlsx'))
    await wrapper.find('[data-test="workbook-import"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('imported')).toEqual([[{ batchId: 'BATCH-1', status: 'PENDING_REVIEW', accepted: 1, issues: [] }]])
    wrapper.unmount()
  })

  it('导入 409 会保留文件和原因、作废旧预检且不自动重试', async () => {
    vi.mocked(classificationApi.importReadings).mockRejectedValue(new TransportError('request', 409, 409, 'IDEMPOTENCY_CONFLICT'))
    const file = new File(['fixture'], 'readings.xlsx')
    const wrapper = mountPanel()
    await preparePreview(wrapper, file, '需保留的来源依据')
    await wrapper.find('[data-test="workbook-import"]').trigger('click')
    await flushPromises()

    expect(classificationApi.importReadings).toHaveBeenCalledTimes(1)
    expect((wrapper.find('[data-test="workbook-file"]').element as HTMLInputElement).files?.[0]).toBe(file)
    expect((wrapper.find('[data-test="workbook-reason"]').element as HTMLTextAreaElement).value).toBe('需保留的来源依据')
    expect(wrapper.find('[data-test="workbook-import"]').attributes('disabled')).toBeDefined()
    expect(wrapper.emitted('imported')).toBeUndefined()
    wrapper.unmount()
  })

  it('切换建筑后丢弃迟到预检响应，并释放旧命令的 busy 状态', async () => {
    let resolvePreview!: (value: ReturnType<typeof validPreview>) => void
    vi.mocked(readingWorkbookApi.preview).mockImplementationOnce(() => new Promise(resolve => { resolvePreview = resolve }) as never)
    const wrapper = mountPanel('B-1')
    await preparePreviewStart(wrapper, new File(['fixture'], 'readings.xlsx'))
    expect(wrapper.find('[data-test="workbook-file"]').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ buildingId: 'B-2' })
    await flushPromises()
    expect(wrapper.find('[data-test="workbook-file"]').attributes('disabled')).toBeUndefined()

    resolvePreview(validPreview())
    await flushPromises()
    expect((wrapper.find('[data-test="workbook-reason"]').element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.find('[data-test="workbook-import"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('预检状态 · PREVIEW')
    wrapper.unmount()
  })
})

async function preparePreviewStart(wrapper: ReturnType<typeof mount>, file: File) {
  await selectFile(wrapper, file)
  await wrapper.find('[data-test="workbook-reason"]').setValue('来源依据')
  await wrapper.find('[data-test="workbook-preview"]').trigger('click')
  await vi.waitFor(() => expect(readingWorkbookApi.preview).toHaveBeenCalledTimes(1))
}
