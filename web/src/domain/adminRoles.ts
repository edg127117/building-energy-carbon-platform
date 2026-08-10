import type { FormalRoleKey } from '@/types/admin'

export const ADMIN_ROLE_OPTIONS: ReadonlyArray<{ label: string; value: FormalRoleKey }> = [
  { label: '建筑业主', value: 'BUILDING_OWNER' },
  { label: '能效管理方', value: 'ENERGY_MANAGER' },
  { label: '接口调用方', value: 'THIRD_PARTY' },
  { label: '平台管理员', value: 'PLATFORM_ADMIN' },
]

const ADMIN_ROLE_LABELS = Object.fromEntries(
  ADMIN_ROLE_OPTIONS.map(({ label, value }) => [value, label]),
) as Record<FormalRoleKey, string>

export function adminRoleLabel(roleKey: FormalRoleKey): string {
  return ADMIN_ROLE_LABELS[roleKey]
}
