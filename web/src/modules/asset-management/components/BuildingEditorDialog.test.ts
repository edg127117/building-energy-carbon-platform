import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { ElButton, ElForm } from '@/shared/ui'
import type { AssetBuilding } from '../models/assets'
import BuildingEditorDialog from './BuildingEditorDialog.vue'

function createDialog(props: Record<string, unknown> = {}) {
  return mount(BuildingEditorDialog, {
    props: { open: true, ...props },
    global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } },
  })
}

function saveButton(wrapper: ReturnType<typeof createDialog>) {
  return wrapper.findAllComponents(ElButton).find(button => button.text() === '保存')!
}

async function setCreateValues(wrapper: ReturnType<typeof createDialog>, omitted?: string) {
  if (omitted !== 'building-name') await wrapper.get('[data-test="building-name"]').find('input').setValue(' 新建建筑 ')
  if (omitted !== 'building-type') await wrapper.get('[data-test="building-type"]').find('input').setValue('办公')
  if (omitted !== 'building-climate-zone') await wrapper.get('[data-test="building-climate-zone"]').find('input').setValue('夏热冬冷')
  if (omitted !== 'building-total-gfa') await wrapper.get('[data-test="building-total-gfa"]').find('input').setValue('0')
  if (omitted !== 'building-region-code') await wrapper.get('[data-test="building-region-code"]').find('input').setValue('110000')
}

function building(regionCode: string | null): AssetBuilding {
  return {
    buildingId: 'B-1',
    buildingName: '既有建筑',
    buildingCode: null,
    buildingType: null,
    constructionYear: null,
    totalGfa: null,
    climateZone: null,
    regionCode,
    status: 'ACTIVE',
    references: { spaces: 0, systemGroups: 0, equipment: 0, points: 0, authorizations: 0, children: 0, aliases: 0, identities: 0 },
    allowedActions: ['UPDATE'],
    updateTime: 0,
  }
}

describe('建筑编辑表单', () => {
  it('创建时使用真实 Element Plus 表单拒绝任一缺失的后端必填字段', async () => {
    for (const omitted of ['building-name', 'building-type', 'building-total-gfa', 'building-climate-zone', 'building-region-code']) {
      const wrapper = createDialog()

      await nextTick()
      expect(wrapper.findComponent(ElForm).exists()).toBe(true)
      await setCreateValues(wrapper, omitted)
      await saveButton(wrapper).trigger('click')
      await flushPromises()

      expect(wrapper.emitted('save')).toBeUndefined()
      wrapper.unmount()
    }
  })

  it('创建时提交行政区划代码并允许总建筑面积为零', async () => {
    const wrapper = createDialog()

    await setCreateValues(wrapper)
    await saveButton(wrapper).trigger('click')
    await vi.waitFor(() => expect(wrapper.emitted('save')).toHaveLength(1))

    expect(wrapper.emitted('save')?.[0]?.[0]).toEqual({
      buildingName: '新建建筑',
      buildingCode: null,
      buildingType: '办公',
      constructionYear: null,
      totalGfa: 0,
      climateZone: '夏热冬冷',
      regionCode: '110000',
      status: 'ACTIVE',
    })
    wrapper.unmount()
  })

  it('拒绝空白必填文本和含内部空白的区域编码', async () => {
    const wrapper = createDialog()
    await setCreateValues(wrapper)
    await wrapper.get('[data-test="building-type"] input').setValue('   ')
    await wrapper.get('[data-test="building-region-code"] input').setValue('11 0000')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('save')).toBeUndefined()
    wrapper.unmount()
  })

  it('异步校验期间不重复提交，切换编辑对象后不提交旧表单', async () => {
    const wrapper = createDialog()
    await setCreateValues(wrapper)
    let finish!: (result: boolean) => void
    const actualForm = (wrapper.vm as unknown as { formRef: { validate: () => Promise<boolean> } }).formRef
    // 仅控制这一用例的校验时序，必填规则由其余真实校验用例验证。
    const validate = vi.spyOn(actualForm, 'validate').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(validate).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ building: building('310100') })
    finish(true)
    await flushPromises()
    expect(wrapper.emitted('save')).toBeUndefined()
    validate.mockRestore()
    wrapper.unmount()
  })

  it('编辑时保留已有行政区划；旧建筑未提供该字段时提交 null', async () => {
    const current = createDialog({ building: building('110000') })
    await nextTick()
    await saveButton(current).trigger('click')
    await vi.waitFor(() => expect(current.emitted('save')).toHaveLength(1))
    expect(current.emitted('save')?.[0]?.[0]).toMatchObject({ regionCode: '110000' })
    current.unmount()

    const legacy = createDialog({ building: building(null) })
    await nextTick()
    await saveButton(legacy).trigger('click')
    await vi.waitFor(() => expect(legacy.emitted('save')).toHaveLength(1))
    expect(legacy.emitted('save')?.[0]?.[0]).toMatchObject({ regionCode: null })
    legacy.unmount()
  })

  it('父级提交中时禁用按钮，并忽略表单提交事件', async () => {
    const wrapper = createDialog({ submitting: true })
    await setCreateValues(wrapper)

    expect(saveButton(wrapper).props('disabled')).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.emitted('save')).toBeUndefined()
    wrapper.unmount()
  })
})
