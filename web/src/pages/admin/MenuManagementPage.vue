<template>
  <div class="admin-page">
    <AdminPageHeader title="菜单管理" description="维护后台入口及显示状态。未上线功能不会出现在导航中。"><a-button type="primary" @click="openAdd">新增菜单</a-button></AdminPageHeader>
    <section class="admin-panel">
      <a-alert v-if="menus.error.value" class="admin-error" type="error" show-icon :message="menus.error.value" />
      <a-table v-model:expanded-row-keys="menus.expandedMenuIds.value" :columns="columns" :data-source="tableTree" :scroll="{ x: 930 }" table-layout="fixed" row-key="id" :loading="menus.loading.value" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'"><span class="menu-name" :title="record.menuName">{{ record.menuName }}</span></template>
          <template v-else-if="column.key === 'rollout'"><a-tag :color="rolloutColor(record)">{{ rolloutLabel(record) }}</a-tag></template>
          <template v-else-if="column.key === 'navigation'"><span class="menu-navigation-state">{{ navigationLabel(record) }}</span></template>
          <template v-else-if="column.key === 'actions'">
            <a-space class="menu-actions" :size="0">
              <a-button type="link" @click="openEdit(record)">编辑</a-button>
              <a-dropdown placement="bottomRight" :trigger="['click']">
                <a-button type="link">更多</a-button>
                <template #overlay>
                  <a-menu>
                    <a-menu-item :disabled="record.menuType === 'F'" :title="record.menuType === 'F' ? '操作权限不能包含下级菜单' : undefined" @click="openChild(record)">新增下级</a-menu-item>
                    <a-menu-divider />
                    <a-menu-item danger :disabled="(record.children?.length ?? 0) > 0" :title="(record.children?.length ?? 0) > 0 ? '请先删除下级菜单' : undefined" @click="confirmDelete(record)">删除</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </a-space>
          </template>
        </template>
      </a-table>
    </section>
    <MenuFormDrawer :open="drawerOpen" :menu="editing" :initial-parent-id="initialParentId" :parent-options="menus.parentOptions(editing?.id)" :submitting="menus.pending.value" @close="drawerOpen=false" @save="save" />
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import MenuFormDrawer from '@/components/admin/MenuFormDrawer.vue'
import { useMenuManagement } from '@/composables/useMenuManagement'
import { buildMenuTableTree, summarizeMenuImplementation } from '@/domain/adminNavigation'
import type { MenuCreateRequest, MenuNode } from '@/types/admin'
/** 页面展示全部隐藏/停用配置，但上线状态只提供信息，绝不把未知路径变成链接。 */
const menus = useMenuManagement(); const drawerOpen = ref(false); const editing = ref<MenuNode | null>(null); const initialParentId = ref(0)
const tableTree = computed(() => buildMenuTableTree(menus.tree.value))
const columns = [
  { title: '菜单', dataIndex: 'menuName', key: 'name', width: 420, className: 'menu-name-column' },
  { title: '上线情况', key: 'rollout', width: 180 },
  { title: '导航状态', key: 'navigation', width: 180 },
  { title: '操作', key: 'actions', width: 150 },
]
function rolloutLabel(menu: MenuNode) {
  const summary = summarizeMenuImplementation(menu)
  if (summary.kind === 'page') return summary.implemented ? '已上线' : '未上线'
  if (summary.kind === 'permission') return '操作权限'
  if (summary.totalPageCount === 0) return '暂无页面'
  if (summary.implementedPageCount === 0) return `未上线 0/${summary.totalPageCount}`
  if (summary.implementedPageCount === summary.totalPageCount) return `全部上线 ${summary.totalPageCount}/${summary.totalPageCount}`
  return `已上线 ${summary.implementedPageCount}/${summary.totalPageCount}`
}
function rolloutColor(menu: MenuNode) {
  const summary = summarizeMenuImplementation(menu)
  if (summary.kind === 'page') return summary.implemented ? 'green' : 'orange'
  if (summary.kind === 'directory') return summary.implementedPageCount > 0 ? 'blue' : 'default'
  return 'default'
}
function navigationLabel(menu: MenuNode) {
  if (menu.menuType === 'F') return '不参与导航'
  if (!menu.visible && !menu.status) return '已隐藏 · 已停用'
  if (!menu.visible) return '已隐藏'
  if (!menu.status) return '已停用'
  return '显示中'
}
function openAdd() { editing.value = null; initialParentId.value = 0; drawerOpen.value = true }
function openEdit(menu: MenuNode) { editing.value = menu; initialParentId.value = menu.parentId; drawerOpen.value = true }
function openChild(menu: MenuNode) { if (menu.menuType === 'F') return; editing.value = null; initialParentId.value = menu.id; drawerOpen.value = true }
async function save(request: MenuCreateRequest) { try { if (editing.value) await menus.update({ ...request, id: editing.value.id }); else await menus.add(request); drawerOpen.value = false; message.success('菜单树已更新') } catch { /* 后端层级校验消息由 Composable 展示，表单保持打开。 */ } }
function confirmDelete(menu: MenuNode) { Modal.confirm({ title: `删除“${menu.menuName}”？`, content: '存在子菜单时后端会拒绝删除。', okText: '确认删除', cancelText: '取消', okType: 'danger', onOk: () => menus.remove(menu.id) }) }
onMounted(() => { void menus.load().catch(() => undefined) })
</script>
<style scoped>
.menu-name {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  word-break: keep-all;
}

.menu-navigation-state { color: #a9bfd1; }
.menu-actions { white-space: nowrap; }
</style>
