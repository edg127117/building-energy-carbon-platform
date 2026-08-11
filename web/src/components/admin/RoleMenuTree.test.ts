import { defineComponent, type PropType } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { MenuNode } from '@/types/admin'
import RoleMenuTree from './RoleMenuTree.vue'

const TreeStub = defineComponent({
  name: 'ATree',
  props: {
    defaultExpandAll: Boolean,
    defaultExpandedKeys: { type: Array as PropType<number[]>, default: undefined },
    checkedKeys: { type: Array as PropType<number[]>, default: () => [] },
  },
  template: '<div />',
})

const tree: MenuNode[] = [{
  id: 100,
  parentId: 0,
  menuName: '中央空调调适',
  menuType: 'M',
  path: '/hvac',
  component: null,
  perms: null,
  icon: null,
  visible: 1,
  status: 1,
  sortOrder: 1,
  children: [],
}]

describe('role menu tree', () => {
  it('starts collapsed while preserving checked menu ids', () => {
    const wrapper = mount(RoleMenuTree, {
      props: { tree, checkedIds: [100] },
      global: { stubs: { 'a-tree': TreeStub, 'a-tag': true } },
    })
    const renderedTree = wrapper.findComponent(TreeStub)

    expect(renderedTree.props('defaultExpandAll')).toBe(false)
    expect(renderedTree.props('defaultExpandedKeys')).toEqual([])
    expect(renderedTree.props('checkedKeys')).toEqual([100])
  })
})
