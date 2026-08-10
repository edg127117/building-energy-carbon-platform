<template>
  <div class="admin-page">
    <AdminPageHeader title="菜单管理" description="目录汇总全部子页面的实现情况；页面只有精确命中前端注册表才算已实现。导航显示和菜单启停只表示数据库配置。"><a-button type="primary" @click="openAdd">新增菜单</a-button></AdminPageHeader>
    <section class="admin-panel">
      <a-alert v-if="menus.error.value" class="admin-error" type="error" show-icon :message="menus.error.value" />
      <a-table v-model:expanded-row-keys="menus.expandedMenuIds.value" :columns="columns" :data-source="tableTree" row-key="id" :loading="menus.loading.value" :pagination="false">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'"><a-tag>{{ typeLabel(record.menuType) }}</a-tag></template>
          <template v-else-if="column.key === 'path'"><code>{{ record.path || '--' }}</code></template>
          <template v-else-if="column.key === 'implementation'"><a-tag :color="implementationColor(record)">{{ implementationLabel(record) }}</a-tag></template>
          <template v-else-if="column.key === 'state'"><a-space><a-tag :color="record.visible ? 'blue' : 'default'">{{ record.visible ? '导航显示' : '导航隐藏' }}</a-tag><a-tag :color="record.status ? 'green' : 'orange'">{{ record.status ? '菜单启用' : '菜单停用' }}</a-tag></a-space></template>
          <template v-else-if="column.key === 'actions'"><a-space><a-button type="link" @click="openEdit(record)">编辑</a-button><a-button type="link" :disabled="record.menuType === 'F'" :title="record.menuType === 'F' ? '按钮权限不能包含子菜单' : undefined" @click="openChild(record)">新增子级</a-button><a-button type="link" danger :disabled="(record.children?.length ?? 0) > 0" :title="(record.children?.length ?? 0) > 0 ? '请先删除子菜单' : undefined" @click="confirmDelete(record)">删除</a-button></a-space></template>
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
/** 页面显示全部隐藏/停用配置，但实现徽标只提供信息，绝不把未知路径变成链接。 */
const menus = useMenuManagement(); const drawerOpen = ref(false); const editing = ref<MenuNode | null>(null); const initialParentId = ref(0)
const tableTree = computed(() => buildMenuTableTree(menus.tree.value))
const columns = [{ title: '菜单名称', dataIndex: 'menuName', key: 'name' }, { title: '类型', key: 'type', width: 100 }, { title: '路径', key: 'path' },
  { title: '页面实现', key: 'implementation', width: 170 }, { title: '菜单配置', key: 'state', width: 190 }, { title: '操作', key: 'actions', width: 220 }]
function typeLabel(type: string) { return type === 'M' ? '目录 M' : type === 'C' ? '页面 C' : '按钮 F' }
function implementationLabel(menu: MenuNode) { const summary = summarizeMenuImplementation(menu); if (summary.kind === 'directory') return summary.totalPageCount ? `已实现子页面 ${summary.implementedPageCount}/${summary.totalPageCount}` : '无页面子项'; if (summary.kind === 'page') return summary.implemented ? '前端已实现' : '前端未实现'; return '不适用（按钮权限）' }
function implementationColor(menu: MenuNode) { const summary = summarizeMenuImplementation(menu); if (summary.kind === 'page') return summary.implemented ? 'green' : 'default'; if (summary.kind === 'directory') return summary.implementedPageCount > 0 ? 'blue' : 'default'; return 'default' }
function openAdd() { editing.value = null; initialParentId.value = 0; drawerOpen.value = true }
function openEdit(menu: MenuNode) { editing.value = menu; initialParentId.value = menu.parentId; drawerOpen.value = true }
function openChild(menu: MenuNode) { if (menu.menuType === 'F') return; editing.value = null; initialParentId.value = menu.id; drawerOpen.value = true }
async function save(request: MenuCreateRequest) { try { if (editing.value) await menus.update({ ...request, id: editing.value.id }); else await menus.add(request); drawerOpen.value = false; message.success('菜单树已更新') } catch { /* 后端层级校验消息由 Composable 展示，表单保持打开。 */ } }
function confirmDelete(menu: MenuNode) { Modal.confirm({ title: `删除“${menu.menuName}”？`, content: '存在子菜单时后端会拒绝删除。', okType: 'danger', onOk: () => menus.remove(menu.id) }) }
onMounted(() => { void menus.load().catch(() => undefined) })
</script>
<style scoped>code { color:#79b9e7; font-size:12px; }</style>
