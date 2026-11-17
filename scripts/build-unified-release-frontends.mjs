import { mkdirSync, writeFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { spawnSync } from 'node:child_process'
import { verifyBuildingBinding } from './build-cxg-demo-frontend.mjs'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// 两个构建只改变部署绑定，不改变页面源码；输出限制在本任务的生成目录。
export function buildReleaseFrontend(environment, buildingCode, output) {
  if (!['shared', 'demo'].includes(environment) || !/^[A-Za-z0-9_-]{1,64}$/.test(buildingCode)) {
    throw new Error('UNIFIED_FRONTEND_TARGET_REQUIRED')
  }
  const destination = resolve(output)
  const generated = resolve(repository, 'target', 'unified-release-adaptation')
  if (!destination.startsWith(generated + '/') && !destination.startsWith(generated + '\\')) {
    throw new Error('UNIFIED_FRONTEND_OUTPUT_OUTSIDE_GENERATED')
  }
  if (existsSync(destination)) throw new Error('UNIFIED_FRONTEND_OUTPUT_EXISTS')
  mkdirSync(destination, { recursive: true })
  // 直接调用已安装的构建工具，避免 Windows shell 对输出路径再次解释。
  for (const [cli, args] of [
    ['vue-tsc/bin/vue-tsc.js', ['-b']],
    ['vite/bin/vite.js', ['build', '--outDir', destination]],
  ]) {
    const result = spawnSync(process.execPath, [resolve(repository, 'web/node_modules', cli), ...args], {
      cwd: resolve(repository, 'web'), stdio: 'inherit',
      env: { ...process.env, VITE_SCREEN_BUILDING_CODE: buildingCode },
    })
    if (result.error || result.status !== 0) throw new Error('UNIFIED_FRONTEND_BUILD_FAILED')
  }
  const verified = verifyBuildingBinding(destination, buildingCode)
  const info = { environment, buildingCode, page: verified.page }
  writeFileSync(resolve(destination, 'release-build.json'), JSON.stringify(info, null, 2))
  console.log('UNIFIED_FRONTEND_BUILD_OK ' + JSON.stringify(info))
  return info
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const args = process.argv.slice(2)
  if (args.length !== 3) throw new Error('Usage: node scripts/build-unified-release-frontends.mjs shared|demo BUILDING_CODE OUTPUT')
  buildReleaseFrontend(...args)
}
