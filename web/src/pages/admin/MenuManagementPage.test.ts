/* eslint-disable vue/one-component-per-file */
import { defineComponent, h, type PropType } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { Modal } from 'ant-design-vue'
import MenuManagementPage from './MenuManagementPage.vue'
import type { MenuNode } from '@/types/admin'

type MenuTableRow = Omit<MenuNode, 'children'> & { children?: MenuTableRow[] }

const mocked = vi.hoisted(() => ({
  tree: { value: [] as MenuNode[] },
  expandedMenuIds: { value: [] as number[] },
  loading: { value: false },
  error: { value: null as string | null },
  pending: { value: false },
  load: vi.fn().mockResolvedValue(undefined),
  add: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  parentOptions: vi.fn(() => []),
}))

vi.mock('@/composables/useMenuManagement', () => ({ useMenuManagement: () => mocked }))

const node = (input: Partial<MenuNode> & Pick<MenuNode, 'id' | 'menuName'>): MenuNode => ({
  id: input.id,
  parentId: input.parentId ?? 0,
  menuName: input.menuName,
  menuType: input.menuType ?? 'M',
  path: input.path ?? null,
  component: input.component ?? null,
  perms: input.perms ?? null,
  icon: input.icon ?? null,
  visible: input.visible ?? 1,
  status: input.status ?? 1,
  sortOrder: input.sortOrder ?? 0,
  children: input.children ?? [],
})

const TableStub = defineComponent({
  name: 'ATable',
  props: {
    columns: { type: Array as PropType<Array<{ key: string; title: string }>>, required: true },
    dataSource: { type: Array as PropType<MenuTableRow[]>, required: true },
    expandedRowKeys: { type: Array as PropType<number[]>, required: true },
  },
  setup(props, { slots }) {
    const flatten = (items: MenuTableRow[]): MenuTableRow[] => items.flatMap((item) => [item, ...flatten(item.children ?? [])])
    return () => h('div', { class: 'table-stub' }, flatten(props.dataSource as MenuTableRow[]).flatMap((record) =>
      (props.columns as Array<{ key: string }>).map((column) => h('div', { 'data-row': record.id, 'data-column': column.key }, slots.bodyCell?.({ column, record })))))
  },
})

const ButtonStub = defineComponent({
  name: 'AButton',
  emits: ['click'],
  setup(_, { emit, slots, attrs }) {
    return () => h('button', { ...attrs, onClick: () => emit('click') }, slots.default?.())
  },
})

const DropdownStub = defineComponent({
  name: 'ADropdown',
  setup(_, { slots }) {
    return () => h('div', { class: 'dropdown-stub' }, [slots.default?.(), slots.overlay?.()])
  },
})

const MenuDrawerStub = defineComponent({
  name: 'MenuFormDrawer',
  props: {
    open: Boolean,
    menu: { type: Object as PropType<MenuNode | null>, default: null },
    parentOptions: { type: Array as PropType<Array<{ label: string; value: number }>>, required: true },
    submitting: Boolean,
    initialParentId: { type: Number, default: 0 },
  },
  template: '<div class="menu-drawer-stub" />',
})

function mountPage() {
  return mount(MenuManagementPage, {
    global: {
      stubs: {
        AdminPageHeader: { props: ['title', 'description'], template: '<header>{{ title }} {{ description }}<slot /></header>' },
        MenuFormDrawer: MenuDrawerStub,
        'a-button': ButtonStub,
        'a-dropdown': DropdownStub,
        'a-menu': { template: '<div><slot /></div>' },
        'a-menu-item': { props: ['disabled', 'danger'], template: '<button :disabled="disabled"><slot /></button>' },
        'a-menu-divider': true,
        'a-alert': true,
        'a-table': TableStub,
        'a-tag': { template: '<span><slot /></span>' },
        'a-space': { template: '<span><slot /></span>' },
      },
    },
  })
}

describe('menu management page', () => {
  beforeEach(() => {
    mocked.load.mockClear()
    mocked.remove.mockClear()
    mocked.expandedMenuIds.value = [100]
    mocked.tree.value = [
      node({
        id: 100,
        menuName: '中央空调调适',
        path: '/hvac',
        children: [
          node({ id: 101, parentId: 100, menuName: 'HVAC 能效大屏', menuType: 'C', path: '/hvac-demo' }),
          node({ id: 102, parentId: 100, menuName: '旧页面', menuType: 'C', path: '/old', visible: 0, status: 0 }),
          node({ id: 103, parentId: 100, menuName: '导出按钮', menuType: 'F', perms: 'hvac:export' }),
        ],
      }),
    ]
  })

  it('shows a business-facing four-column table without developer fields', () => {
    const wrapper = mountPage()
    const table = wrapper.findComponent(TableStub)

    expect(table.props('columns').map((column: { title: string }) => column.title)).toEqual(['菜单', '上线情况', '导航状态', '操作'])
    expect(wrapper.text()).toContain('已上线 1/2')
    expect(wrapper.text()).toContain('已上线')
    expect(wrapper.text()).toContain('未上线')
    expect(wrapper.text()).toContain('操作权限')
    expect(wrapper.text()).toContain('已隐藏 · 已停用')
    expect(wrapper.text()).not.toContain('前端')
    expect(wrapper.text()).not.toContain('/hvac-demo')
    expect(wrapper.text()).not.toMatch(/目录 M|页面 C|按钮 F/)
  })

  it('keeps every menu name on one line and preserves the full name for hover', () => {
    mocked.tree.value[0].children[1].menuName = '单位风量耗功值'

    const wrapper = mountPage()
    const nameCell = wrapper.find('[data-row="102"][data-column="name"] .menu-name')

    expect(nameCell.text()).toBe('单位风量耗功值')
    expect(nameCell.attributes('title')).toBe('单位风量耗功值')
  })

  it('passes the clicked parent into the child form instead of submitting a hidden override', async () => {
    const wrapper = mountPage()
    const childButton = wrapper.find('[data-row="100"][data-column="actions"]').findAll('button').find((button) => button.text() === '新增下级')

    await childButton?.trigger('click')

    expect(wrapper.findComponent(MenuDrawerStub).props('initialParentId')).toBe(100)
  })

  it('binds controlled expansion and disables deletion for nodes with children', () => {
    const wrapper = mountPage()
    const table = wrapper.findComponent(TableStub)
    const tableData = table.props('dataSource') as MenuTableRow[]
    const rootDelete = wrapper.find('[data-row="100"][data-column="actions"]').findAll('button').find((button) => button.text() === '删除')
    const permissionChild = wrapper.find('[data-row="103"][data-column="actions"]').findAll('button').find((button) => button.text() === '新增下级')

    expect(table.props('expandedRowKeys')).toEqual([100])
    expect(tableData[0].children).toHaveLength(3)
    expect(tableData[0].children?.[0].children).toBeUndefined()
    expect(rootDelete?.attributes('disabled')).toBeDefined()
    expect(permissionChild?.attributes('disabled')).toBeDefined()
  })

  it('uses Chinese action labels in menu deletion confirmation', async () => {
    const confirm = vi.spyOn(Modal, 'confirm').mockImplementation(vi.fn())
    const wrapper = mountPage()
    const leafDelete = wrapper.find('[data-row="102"][data-column="actions"]').findAll('button').find((button) => button.text() === '删除')

    await leafDelete?.trigger('click')

    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ okText: '确认删除', cancelText: '取消' }))
    confirm.mockRestore()
  })
})
