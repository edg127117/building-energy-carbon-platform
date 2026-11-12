import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve, join, sep } from 'node:path'
import { readBuildConfig, verifyBuildingBinding } from '../build-cxg-demo-frontend.mjs'

function fixture(t) {
  const base = resolve(tmpdir()), dir = mkdtempSync(join(base, 'cxg-frontend-build-'))
  t.after(() => { assert.ok(resolve(dir).startsWith(base + sep)); rmSync(dir, { recursive: true, force: true }) })
  mkdirSync(join(dir, 'assets'))
  writeFileSync(join(dir, 'index.html'), '<html></html>')
  return dir
}

test('deployment config rejects missing, blank and invalid building bindings', t => {
  const dir = fixture(t), file = join(dir, 'config.json')
  for (const buildingCode of [undefined, '', ' ', 'CXG;command']) {
    writeFileSync(file, JSON.stringify({ target: 'cxg-demo-19080', buildingCode }))
    assert.throws(() => readBuildConfig(file), /CONFIG_INVALID/)
  }
  writeFileSync(file, JSON.stringify({ target: 'shared-18080', buildingCode: 'CXG' }))
  assert.throws(() => readBuildConfig(file), /CONFIG_INVALID/)
})

test('release binding matches the data-entry constant, not a decorative code string', t => {
  const dir = fixture(t), file = join(dir, 'assets/FinalBPage-test.js'), expected = 'CXG_F1_F6_HOURLY_V4'
  for (const binding of ['', 'OTHER']) {
    writeFileSync(file, `const banner="${expected}";const p={__name:"FinalBPage",setup(e){const a="${binding}",s=load(a)}};`)
    assert.throws(() => verifyBuildingBinding(dir, expected), /BINDING_MISMATCH/)
  }
  writeFileSync(file, `const p={__name:"FinalBPage",setup(e){const a="${expected}",s=load(a)}};`)
  assert.equal(verifyBuildingBinding(dir, expected).buildingCode, expected)
  writeFileSync(join(dir, 'assets/FinalBPage-stale.js'), 'stale bundle')
  assert.throws(() => verifyBuildingBinding(dir, expected), /PAGE_AMBIGUOUS/)
})

test('checked-in 19080 config preserves the accepted building code', () => {
  const config = readBuildConfig(new URL('../../src/env/cxg-demo/frontend-build.json', import.meta.url))
  assert.equal(config.buildingCode, 'CXG_F1_F6_HOURLY_V4')
})
