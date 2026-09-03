import { describe, expect, it } from 'vitest'
// 测试静态门禁本身，避免只证明当前代码恰好没有违规。
import { analyzeSource, checkFoundation } from '../../scripts/check-foundation.mjs'
describe('foundation architecture rules', () => {
  it('checks all new sources', () => expect(checkFoundation()).toEqual([]))
  it.each([
    ['modules/dashboard/pages/Test.vue', '<script setup>import x from "@/modules/auth/stores/internal"</script><template><div /></template>', 'MODULE_PUBLIC_ENTRY'],
    ['shared/test.ts', 'import x from "@/modules/auth/public"', 'SHARED_DEPENDENCY'],
    ['modules/auth/test.ts', 'import x from "@/app/main"', 'REVERSE_APP_DEPENDENCY'],
    ['modules/dashboard/api/test.ts', 'import x from "@/utils/request"', 'LEGACY_DEPENDENCY'],
    ['modules/dashboard/pages/Test.vue', '<template><button>确认</button></template>', 'HARDCODED_COPY'],
    ['shared/test.ts', 'const text = "请求失败"', 'HARDCODED_COPY'],
    ['styles/base/test.css', '.x { color: #fff; padding: 10px; }', 'HARDCODED_COLOR'],
    ['styles/themes/test.css', ':root { --bec-radius-card: var(--bec-ref-radius-4); }', 'THEME_SCOPE'],
    ['shared/Test.vue', '<template><div /></template><style>.x { color: red; }</style>', 'SCOPED_STYLE_REQUIRED'],
  ])('rejects %s %s', (file, source, rule) => expect(analyzeSource(file, source).join()).toContain(rule))
  it('allows public entry, localized template, and token styles', () => {
    expect(analyzeSource('modules/dashboard/pages/Test.vue', '<script setup>import { t } from "@/locales"; import x from "@/modules/auth/public"</script><template><div>{{ t("common.empty") }}</div></template><style scoped>.x { padding: var(--bec-space-group); }</style>')).toEqual([])
  })
})
