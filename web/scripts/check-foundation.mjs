import { readdirSync, readFileSync } from 'node:fs'
import { dirname, resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'
import postcss from 'postcss'

const src = resolve(dirname(fileURLToPath(import.meta.url)), '../src')
const roots = ['app', 'modules', 'shared', 'infrastructure', 'generated', 'locales', 'styles']
const legacyStyles = new Set(['styles/admin.css'])
const chinese = /[\u3400-\u9fff]/
const colors = /#[\da-fA-F]{3,8}\b|(?:rgba?|hsla?)\(/

// 只对新骨架执行边界，冻结继承目录不以白名单方式放行到新入口。
export function analyzeSource(file, source) {
  const issues = []
  const locale = /(?:^locales\/|\/locales\/)/.test(file)
  const module = file.match(/^modules\/([^/]+)/)?.[1]
  function fail(rule) { issues.push(file + ': ' + rule) }
  function dependency(specifier) {
    if (specifier === 'element-plus' || specifier === 'lucide-vue-next' || specifier.startsWith('element-plus/')) {
      if (file !== 'shared/ui/index.ts' && !(file === 'app/main.ts' && specifier.endsWith('.css'))) fail('UI_ENTRY')
    }
    if (/^echarts(?:\/|$)/.test(specifier) && file !== 'shared/charts/echarts.ts') fail('CHART_ENTRY')
    let target
    if (specifier.startsWith('@/')) target = specifier.slice(2)
    else if (specifier.startsWith('.')) target = relative(src, resolve(src, dirname(file), specifier)).replaceAll('\\', '/')
    else return
    if (!roots.includes(target.split('/')[0]) || legacyStyles.has(target)) fail('LEGACY_DEPENDENCY')
    const targetModule = target.match(/^modules\/([^/]+)\/(.*)/)
    if (targetModule && targetModule[1] !== module && !/^public(?:\.ts)?$/.test(targetModule[2])) fail('MODULE_PUBLIC_ENTRY')
    if (file.startsWith('modules/') && target.startsWith('app/')) fail('REVERSE_APP_DEPENDENCY')
    if (file.startsWith('shared/') && /^(app|modules)\//.test(target)) fail('SHARED_DEPENDENCY')
    if (file.startsWith('infrastructure/') && /^(app|modules|locales)\//.test(target)) fail('INFRASTRUCTURE_DEPENDENCY')
    if (file.startsWith('modules/') && target.startsWith('infrastructure/http/') && !/^modules\/[^/]+\/api\//.test(file)) fail('MODULE_API_ENTRY')
  }
  function script(code) {
    const ast = ts.createSourceFile(file + '.ts', code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
    function visit(node) {
      if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) dependency(node.moduleSpecifier.text)
      if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword && ts.isStringLiteral(node.arguments[0])) dependency(node.arguments[0].text)
      if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node))) {
        if (!locale && chinese.test(node.text)) fail('HARDCODED_COPY')
        if (colors.test(node.text)) fail('HARDCODED_COLOR')
      }
      ts.forEachChild(node, visit)
    }
    visit(ast)
  }
  function css(code) {
    postcss.parse(code).walkDecls(decl => {
      if (file === 'styles/tokens/reference.css') return
      if (colors.test(decl.value)) fail('HARDCODED_COLOR')
      if (/(?:^|[^\w-])\d*\.?\d+(?:px|rem|em|vh|vw)\b/.test(decl.value)) fail('HARDCODED_SIZE')
      if (/^(font-size|font-family|font-weight|line-height|box-shadow|border-radius)$/.test(decl.prop)
        && !/var\(--bec-|^(inherit|0)$/.test(decl.value)) fail('STYLE_TOKEN_REQUIRED')
      if (file.startsWith('styles/themes/') && !/^(color-scheme|--bec-(?:color|chart|shadow))/.test(decl.prop)) fail('THEME_SCOPE')
    })
  }
  if (file.endsWith('.vue')) {
    const { descriptor, errors } = parseSfc(source)
    if (errors.length) fail('INVALID_VUE')
    if (descriptor.script) fail('SCRIPT_SETUP_REQUIRED')
    if (descriptor.scriptSetup) script(descriptor.scriptSetup.content)
    for (const block of descriptor.styles) {
      if (!block.scoped) fail('SCOPED_STYLE_REQUIRED')
      css(block.content)
    }
    if (descriptor.template) {
      const tree = parseTemplate(descriptor.template.content)
      function template(node) {
        if (node.type === 2 && node.content.trim()) fail('HARDCODED_COPY')
        for (const prop of node.props ?? []) {
          if (prop.type === 6 && /^(title|alt|placeholder|aria-label|label|description)$/.test(prop.name) && prop.value?.content) fail('HARDCODED_COPY')
          if (prop.type === 7 && prop.exp) script(prop.exp.content)
          if (prop.type === 6 && prop.name === 'style' && prop.value) css('x{' + prop.value.content + '}')
        }
        if (node.type === 5) script(node.content.content)
        for (const child of node.children ?? []) template(child)
      }
      template(tree)
    }
  } else if (file.endsWith('.css')) css(source)
  else if (file.endsWith('.ts')) script(source)
  return [...new Set(issues)]
}

export function checkFoundation() {
  const issues = []
  function walk(folder) {
    for (const item of readdirSync(folder, { withFileTypes: true })) {
      const path = resolve(folder, item.name)
      if (item.isDirectory()) walk(path)
      else {
        const file = relative(src, path).replaceAll('\\', '/')
        if (/\.(vue|ts|css)$/.test(file) && !/\.(test|spec)\.ts$/.test(file) && !legacyStyles.has(file)) {
          issues.push(...analyzeSource(file, readFileSync(path, 'utf8')))
        }
      }
    }
  }
  for (const root of roots) walk(resolve(src, root))
  return issues
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const issues = checkFoundation()
  if (issues.length) { process.stderr.write(issues.join('\n') + '\n'); process.exitCode = 1 }
  else process.stdout.write('FRONTEND_FOUNDATION_CHECK_OK\n')
}
