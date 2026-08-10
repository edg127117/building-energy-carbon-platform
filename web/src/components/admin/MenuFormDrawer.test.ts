import { defineComponent, h, type PropType } from 'vue'
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MenuFormDrawer from './MenuFormDrawer.vue'

const SelectStub = defineComponent({
  name: 'ASelect',
  props: {
    options: { type: Array as PropType<Array<{ label: string; value: string | number }>>, default: () => [] },
  },
  setup(props) {
    return () => h('div', { class: 'select-stub' }, props.options.map((option) => option.label).join('|'))
  },
})

function mountDrawer() {
  return mount(MenuFormDrawer, {
    props: {
      open: true,
      parentOptions: [{ label: '顶级菜单', value: 0 }],
    },
    global: {
      stubs: {
        'a-drawer': { props: ['open', 'title'], template: '<section><h2>{{ title }}</h2><slot /></section>' },
        'a-form': { template: '<form><slot /></form>' },
        'a-form-item': { props: ['label'], template: '<label>{{ label }}<slot /></label>' },
        'a-select': SelectStub,
        'a-input': true,
        'a-input-number': true,
        'a-switch': true,
        'a-button': { template: '<button><slot /></button>' },
      },
    },
  })
}

describe('menu form drawer', () => {
  it('uses business purpose labels without exposing backend type codes', () => {
    const wrapper = mountDrawer()
    const purposeOptions = wrapper.findAllComponents(SelectStub)[1]

    expect(purposeOptions.text()).toBe('目录|页面入口|操作权限')
    expect(wrapper.text()).not.toMatch(/目录（M）|页面（C）|按钮（F）/)
  })

  it('keeps technical routing fields in collapsed advanced settings', () => {
    const wrapper = mountDrawer()
    const advanced = wrapper.find('details.menu-advanced')

    expect(advanced.exists()).toBe(true)
    expect(advanced.attributes('open')).toBeUndefined()
    expect(advanced.find('summary').text()).toContain('高级配置')
    expect(advanced.find('summary').text()).toContain('仅系统维护人员修改')
    expect(advanced.text()).toContain('路由路径')
    expect(advanced.text()).toContain('组件标识')
    expect(advanced.text()).toContain('权限标识')
    expect(advanced.text()).toContain('图标')
  })

  it('collapses advanced settings again when another menu is opened', async () => {
    const wrapper = mountDrawer()
    wrapper.find('details.menu-advanced').element.setAttribute('open', '')

    await wrapper.setProps({
      menu: {
        id: 12,
        parentId: 0,
        menuName: '菜单管理',
        menuType: 'C',
        path: '/system/menus',
        component: null,
        perms: null,
        icon: null,
        visible: 1,
        status: 1,
        sortOrder: 1,
        children: [],
      },
    })

    expect(wrapper.find('details.menu-advanced').attributes('open')).toBeUndefined()
  })
})
