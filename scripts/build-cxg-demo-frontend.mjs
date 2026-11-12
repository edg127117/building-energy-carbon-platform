import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { spawnSync } from 'node:child_process'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')

export function readBuildConfig(filename) {
  const config = JSON.parse(readFileSync(filename, 'utf8'))
  if (config.target !== 'cxg-demo-19080' || typeof config.buildingCode !== 'string' || !/^[A-Za-z0-9_-]+$/.test(config.buildingCode)) {
    throw new Error('CXG_FRONTEND_CONFIG_INVALID: a non-empty buildingCode and the 19080 target are required')
  }
  return config
}

export function verifyBuildingBinding(dist, buildingCode) {
  if (!buildingCode || !existsSync(resolve(dist, 'index.html'))) throw new Error('CXG_FRONTEND_BUILD_MISSING')
  const assets = resolve(dist, 'assets')
  const pages = readdirSync(assets).filter(name => /^FinalBPage-.*\.js$/.test(name))
  if (pages.length !== 1) throw new Error('CXG_FRONTEND_PAGE_AMBIGUOUS')
  const source = readFileSync(resolve(assets, pages[0]), 'utf8')
  // 校验实际数据入口的编译常量，不接受文件其他位置偶然出现同一编码。
  // 若打包器改变此结构，需核对新产物后更新校验，不能跳过检查发布。
  const binding = source.match(/__name:"FinalBPage",setup\(\w+\)\{const \w+="([^"]*)"/)?.[1]
  if (binding !== buildingCode) throw new Error('CXG_FRONTEND_BINDING_MISMATCH: compiled building binding is missing or differs from deployment config')
  return { target: 'cxg-demo-19080', buildingCode, page: pages[0] }
}

export function buildFrontend({ verifyOnly = false } = {}) {
  const config = readBuildConfig(resolve(repository, 'src/env/cxg-demo/frontend-build.json'))
  const web = resolve(repository, 'web')
  if (!verifyOnly) {
    // 参数仅传入构建子进程，不依赖操作员终端残留变量，也不更改全局环境。
    const result = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'build'], {
      cwd: web, stdio: 'inherit', shell: process.platform === 'win32',
      env: { ...process.env, VITE_SCREEN_BUILDING_CODE: config.buildingCode },
    })
    if (result.error || result.status !== 0) throw new Error('CXG_FRONTEND_BUILD_FAILED', { cause: result.error })
  }
  const verified = verifyBuildingBinding(resolve(web, 'dist'), config.buildingCode)
  console.log('CXG_FRONTEND_BUILD_OK ' + JSON.stringify(verified))
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const arguments_ = process.argv.slice(2)
  if (arguments_.some(value => value !== '--verify-only')) throw new Error('Usage: node scripts/build-cxg-demo-frontend.mjs [--verify-only]')
  buildFrontend({ verifyOnly: arguments_.includes('--verify-only') })
}
