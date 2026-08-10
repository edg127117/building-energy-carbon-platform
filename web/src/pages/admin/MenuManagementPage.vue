<template>
  <div class="admin-page">
    <AdminPageHeader title="菜单管理" description="维护完整菜单树。数据库路径只有精确命中前端注册表时才会成为可执行导航。"><a-button type="primary" @click="openAdd">新增菜单</a-button></AdminPageHeader>
    <section class="admin-panel">
      <a-alert v-if="menus.error.value" class="admin-error" type="error" show-icon :message="menus.error.value" />
      <a-table :columns="columns" :data-source="menus.tree.value" row-key="id" :loading="menus.loading.value" :pagination="false" :default-expand-all-rows="true">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'"><a-tag>{{ typeLabel(record.menuType) }}</a-tag></template>
          <template v-else-if="column.key === 'path'"><code>{{ record.path || '--' }}</code></template>
          <template v-else-if="column.key === 'implementation'"><a-tag :color="isImplementedMenuPath(record.path) ? 'green' : 'default'">{{ isImplementedMenuPath(record.path) ? '已接入页面' : '未接入页面' }}</a-tag></template>
          <template v-else-if="column.key === 'state'"><a-space><a-tag :color="record.visible ? 'blue' : 'default'">{{ record.visible ? '可见' : '隐藏' }}</a-tag><a-tag :color="record.status ? 'green' : 'orange'">{{ record.status ? '启用' : '停用' }}</a-tag></a-space></template>
          <template v-else-if="column.key === 'actions'"><a-space><a-button type="link" @click="openEdit(record)">编辑</a-button><a-button type="link" @click="openChild(record)">新增子级</a-button><a-button type="link" danger @click="confirmDelete(record)">删除</a-button></a-space></template>
        </template>
      </a-table>
    </section>
    <MenuFormDrawer :open="drawerOpen" :menu="editing" :parent-options="menus.parentOptions(editing?.id)" :submitting="menus.pending.value" @close="drawerOpen=false" @save="save" />
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import MenuFormDrawer from '@/components/admin/MenuFormDrawer.vue'
import { useMenuManagement } from '@/composables/useMenuManagement'
import { isImplementedMenuPath } from '@/domain/adminNavigation'
import type { MenuCreateRequest, MenuNode } from '@/types/admin'
/** 页面显示全部隐藏/停用配置，但实现徽标只提供信息，绝不把未知路径变成链接。 */
const menus = useMenuManagement(); const drawerOpen = ref(false); const editing = ref<MenuNode | null>(null); const forcedParent = ref<number | null>(null)
const columns = [{ title: '菜单名称', dataIndex: 'menuName', key: 'name' }, { title: '类型', key: 'type', width: 100 }, { title: '路径', key: 'path' },
  { title: '接入状态', key: 'implementation', width: 120 }, { title: '配置状态', key: 'state', width: 150 }, { title: '操作', key: 'actions', width: 220 }]
function typeLabel(type: string) { return type === 'M' ? '目录 M' : type === 'C' ? '页面 C' : '按钮 F' }
function openAdd() { editing.value = null; forcedParent.value = null; drawerOpen.value = true }
function openEdit(menu: MenuNode) { editing.value = menu; forcedParent.value = null; drawerOpen.value = true }
function openChild(menu: MenuNode) { editing.value = null; forcedParent.value = menu.id; drawerOpen.value = true }
async function save(request: MenuCreateRequest) { try { const next = forcedParent.value ? { ...request, parentId: forcedParent.value } : request; if (editing.value) await menus.update({ ...next, id: editing.value.id }); else await menus.add(next); drawerOpen.value = false; message.success('菜单树已更新') } catch {} }
function confirmDelete(menu: MenuNode) { Modal.confirm({ title: `删除“${menu.menuName}”？`, content: '存在子菜单时后端会拒绝删除。', okType: 'danger', onOk: () => menus.remove(menu.id) }) }
onMounted(() => { void menus.load().catch(() => undefined) })
</script>
<style scoped>code { color:#79b9e7; font-size:12px; }</style>
