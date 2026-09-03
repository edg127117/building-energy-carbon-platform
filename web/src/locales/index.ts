import common from './zh-CN/common'
import navigation from './zh-CN/navigation'
import validation from './zh-CN/validation'
import error from './zh-CN/error'
import terminology from './zh-CN/terminology'
import { messages as dashboard } from '@/modules/dashboard/public'
import { messages as drilldown } from '@/modules/drilldown/public'
import { messages as trendAnalysis } from '@/modules/trend-analysis/public'
import { messages as largeScreen } from '@/modules/large-screen/public'
import { messages as auth } from '@/modules/auth/public'
import { messages as accessControl } from '@/modules/access-control/public'
import { messages as assetManagement } from '@/modules/asset-management/public'
import { messages as deviceOnboarding } from '@/modules/device-onboarding/public'

export const messages = { common, navigation, validation, error, terminology, dashboard, drilldown, trendAnalysis,
  largeScreen, auth, accessControl, assetManagement, deviceOnboarding } as const

/** 第一版只注册中文；完整句子通过命名占位符替换，不在组件中拼接文案。 */
export function t(key: string, params: Record<string, string | number> = {}): string {
  let value: unknown = messages
  for (const segment of key.split('.')) {
    value = value && typeof value === 'object' ? (value as Record<string, unknown>)[segment] : undefined
  }
  if (typeof value !== 'string') throw new Error('Missing translation: ' + key)
  return value.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ''))
}
