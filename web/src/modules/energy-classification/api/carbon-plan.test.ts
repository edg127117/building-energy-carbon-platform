import { beforeEach, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { carbonPlanApi, type CarbonPlanVersion } from './carbon-plan'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
beforeEach(() => vi.mocked(requestApi).mockReset().mockResolvedValue({} as never))
it('encodes building and version and sends exact month plus server revision without client totals', async () => {
  await carbonPlanApi.query('B/1', 2026, '2026-02')
  expect(requestApi).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/v1/carbon-management/buildings/B%2F1/plan', params: { year: 2026, throughMonth: '2026-02' } }))
  await carbonPlanApi.transition('B/1', { versionId: 'V/1', revision: 7 } as CarbonPlanVersion, 'activate', '审核后生效')
  expect(requestApi).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/v1/carbon-management/buildings/B%2F1/plan/versions/V%2F1/activate', data: { expectedRevision: 7, reason: '审核后生效' } }))
})
it('uploads one workbook with explicit source nature and boundary to preview only', async () => {
  await carbonPlanApi.importPreview('b', new File(['test'], 'baseline.xlsx'), 'TEST_FIXTURE', '自动化测试', '测试边界')
  const config = vi.mocked(requestApi).mock.calls[0]![0]
  expect(config.url).toContain('/import-preview')
  const form = config.data as FormData
  expect(form.get('nature')).toBe('TEST_FIXTURE')
  expect(form.get('boundary')).toBe('测试边界')
})
