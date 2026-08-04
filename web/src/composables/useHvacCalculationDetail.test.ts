import { describe, expect, it, vi } from 'vitest'
import type { HvacCalculationDetail } from '@/types/hvac'

vi.mock('@/api/hvac', () => ({
  getIndicatorCalculationDetail: vi.fn(),
}))

import {
  useHvacCalculationDetail,
  type HvacCalculationDetailTarget,
} from './useHvacCalculationDetail'

const firstTarget: HvacCalculationDetailTarget = {
  indicatorId: 'IND-1',
  indicatorCode: 'WCR_COP',
  label: '冷水机组 COP',
  equipId: 'WCR-01',
  minuteStart: 1_754_208_000_000,
}

const secondTarget: HvacCalculationDetailTarget = {
  indicatorId: 'IND-2',
  indicatorCode: 'PUMP_EFF',
  label: '水泵效率',
  equipId: 'P-01',
  minuteStart: 1_754_208_060_000,
}

function detail(
  target: HvacCalculationDetailTarget,
): HvacCalculationDetail {
  return {
    indicatorId: target.indicatorId!,
    indicatorCode: target.indicatorCode,
    equipId: target.equipId ?? '',
    minuteStart: target.minuteStart!,
    status: 'SUCCESS',
    value: 4.2,
    unit: null,
    dataQuality: 0,
    formulaVersion: 'V1',
    inputs: [],
    steps: [],
    reasonCode: null,
    missingInputs: [],
  }
}

describe('useHvacCalculationDetail', () => {
  it('loads and saves the selected calculation detail', async () => {
    const firstDetail = detail(firstTarget)
    const getDetail = vi.fn().mockResolvedValue(firstDetail)
    const state = useHvacCalculationDetail({ getDetail })

    await state.open(firstTarget)

    expect(getDetail).toHaveBeenCalledWith(
      firstTarget.indicatorId,
      firstTarget.minuteStart,
    )
    expect(state.visible.value).toBe(true)
    expect(state.selected.value).toEqual(firstTarget)
    expect(state.detail.value).toEqual(firstDetail)
    expect(state.loading.value).toBe(false)
    expect(state.error.value).toBeNull()
  })

  it.each([
    { ...firstTarget, indicatorId: null },
    { ...firstTarget, minuteStart: null },
  ])('opens local no-data without an invalid request', async (target) => {
    const getDetail = vi.fn()
    const state = useHvacCalculationDetail({ getDetail })

    await state.open(target)

    expect(getDetail).not.toHaveBeenCalled()
    expect(state.visible.value).toBe(true)
    expect(state.localNoData.value).toBe(true)
    expect(state.detail.value).toBeNull()
    expect(state.error.value).toBeNull()
    expect(state.loading.value).toBe(false)
  })

  it('clears old detail and maps an HTTP error', async () => {
    const getDetail = vi
      .fn()
      .mockResolvedValueOnce(detail(firstTarget))
      .mockRejectedValueOnce({
        response: { status: 409, data: { msg: '后端原始冲突信息' } },
      })
    const state = useHvacCalculationDetail({ getDetail })
    await state.open(firstTarget)

    await state.open(secondTarget)

    expect(state.detail.value).toBeNull()
    expect(state.error.value).toEqual({
      status: 409,
      message: '历史公式版本暂不受当前服务支持，不能使用新公式解释旧结果',
    })
  })

  it('retries the current target with a new request', async () => {
    const getDetail = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(detail(firstTarget))
    const state = useHvacCalculationDetail({ getDetail })
    await state.open(firstTarget)

    await state.retry()

    expect(getDetail).toHaveBeenCalledTimes(2)
    expect(state.detail.value).toEqual(detail(firstTarget))
    expect(state.error.value).toBeNull()
  })

  it('clears all state when closed', async () => {
    const state = useHvacCalculationDetail({
      getDetail: vi.fn().mockResolvedValue(detail(firstTarget)),
    })
    await state.open(firstTarget)

    state.close()

    expect(state.visible.value).toBe(false)
    expect(state.selected.value).toBeNull()
    expect(state.detail.value).toBeNull()
    expect(state.error.value).toBeNull()
    expect(state.loading.value).toBe(false)
    expect(state.localNoData.value).toBe(false)
  })

  it('ignores a stale response after another indicator is selected', async () => {
    let resolveFirst!: (value: HvacCalculationDetail) => void
    const getDetail = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<HvacCalculationDetail>((resolve) => {
            resolveFirst = resolve
          }),
      )
      .mockResolvedValueOnce(detail(secondTarget))
    const state = useHvacCalculationDetail({ getDetail })

    const first = state.open(firstTarget)
    await state.open(secondTarget)
    resolveFirst(detail(firstTarget))
    await first

    expect(state.selected.value).toEqual(secondTarget)
    expect(state.detail.value).toEqual(detail(secondTarget))
  })

  it('ignores a response that arrives after close', async () => {
    let resolveRequest!: (value: HvacCalculationDetail) => void
    const getDetail = vi.fn(
      () =>
        new Promise<HvacCalculationDetail>((resolve) => {
          resolveRequest = resolve
        }),
    )
    const state = useHvacCalculationDetail({ getDetail })

    const request = state.open(firstTarget)
    state.close()
    resolveRequest(detail(firstTarget))
    await request

    expect(state.visible.value).toBe(false)
    expect(state.selected.value).toBeNull()
    expect(state.detail.value).toBeNull()
  })
})
