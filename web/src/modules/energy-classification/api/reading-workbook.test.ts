import { describe, expect, it, vi } from 'vitest'
import { requestApi, requestFile } from '@/infrastructure/http/public'
import { readingWorkbookApi } from './reading-workbook'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn(), requestFile: vi.fn() }))
describe('工作簿接口', () => {
  it('用已有认证下载模板，原工作簿上传后端预检，不在浏览器求差或乘倍率', async () => {
    await readingWorkbookApi.template('B/1')
    const file = new File(['fixture'], 'readings.xlsx')
    await readingWorkbookApi.preview('B/1', file, 'key', '依据')
    expect(requestFile).toHaveBeenCalledWith({ method: 'get', url: '/v1/energy-classification/buildings/B%2F1/readings/workbook/template' })
    const call = vi.mocked(requestApi).mock.calls.at(-1)![0]
    expect(call.url).toBe('/v1/energy-classification/buildings/B%2F1/readings/workbook/preview')
    const data = call.data as FormData
    expect(data.get('file')).toEqual(file); expect(data.get('idempotencyKey')).toBe('key'); expect(data.get('reason')).toBe('依据')
  })
})
