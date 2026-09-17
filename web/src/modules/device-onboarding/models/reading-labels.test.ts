import { describe, expect, it } from 'vitest'
import { identityTypeText, profileText, readingReasonText, readingStatusText, syncJobResultText, syncJobStatusText } from './reading-labels'

describe('读数的客户展示', () => {
  it('区分历史数据、质量使用策略与在线状态', () => {
    expect(readingStatusText('HAS_DATA')).toBe('已有原始数据')
    expect(readingReasonText('NO_ACTIVE_POLICY_DEFAULT')).toContain('质量使用策略')
    expect(readingReasonText('QUALITY_NOT_ALLOWED')).toContain('不允许展示')
    expect(identityTypeText('SN')).toBe('设备序列号')
  })
  it('未知状态不会直接泄露技术码或推断成功', () => {
    expect(readingStatusText('FUTURE_STATE')).toBe('状态待确认')
    expect(readingReasonText('FUTURE_REASON')).toContain('技术详情')
    expect(identityTypeText('FUTURE_TYPE')).toBe('其他身份类型')
    expect(syncJobStatusText('FUTURE_STATE')).toBe('同步状态待确认')
  })
  it('完整翻译目录同步任务状态', () => {
    expect(['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED'].map(syncJobStatusText))
      .toEqual(['待执行', '执行中', '等待重试', '已完成', '失败'])
  })
  it('目录同步结果和大金协议不直接暴露技术编码', () => {
    expect(syncJobResultText(null)).toBe('未发现异常')
    expect(syncJobResultText('DAIKIN_SYNC_TRANSPORT_FAILURE')).toContain('自动重试')
    expect(syncJobResultText('DAIKIN_SYNC_AUTHENTICATION_REQUIRED')).toContain('厂家授权')
    expect(syncJobResultText('FUTURE_ERROR')).toBe('同步未完成，请联系运维人员。')
    expect(profileText('DAIKIN_INDOOR_V2')).toBe('大金室内机 2.0')
    expect(profileText('DAIKIN_OUTDOOR_V2')).toBe('大金室外机 2.0')
    expect(profileText('FUTURE_PROFILE')).toBe('其他接入协议')
  })
})
