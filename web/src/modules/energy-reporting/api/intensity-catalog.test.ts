import { describe, it, expect, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { listIntensityCatalog, saveIntensityCatalog, parseIntensityRows, type IntensityRule } from './intensity-catalog'
vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))
describe('intensity catalog transport and table paste', () => {
  it('uses the shared client and preserves unknown dates without defaults', async () => {
    const signal = new AbortController().signal
    await listIntensityCatalog(signal)
    expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/carbon-intensity/catalog', signal })
    const input = { catalogId: null, expectedVersionId: null, name: 'source', sourceReference: 'test://ref', documentVersion: 'v1', publishedOn: null, entries: [] }
    await saveIntensityCatalog(input)
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'post', url: '/v1/carbon-intensity/catalog', data: input })
  })
  it('imports region/category data without applying Jiangsu values or inventing years', () => {
    const defaults = { gasBasis: 'CO2_ONLY', timeStatus: 'PENDING', applicableFrom: null, applicableTo: null } as IntensityRule
    expect(parseIntensityRows('4501\tOFFICE\tzone\t60\t40\t20', defaults)[0]).toMatchObject({ regionPrefix: '4501', constraintValue: '60', timeStatus: 'PENDING', applicableFrom: null })
    expect(() => parseIntensityRows('4501\tOFFICE\tzone\tbad\t40\t20', defaults)).toThrow('IMPORT_INVALID')
  })
})
