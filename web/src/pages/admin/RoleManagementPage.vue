<template>
  <div class="admin-page">
    <AdminPageHeader title="角色权限" description="四类正式角色只读展示；保存会全量替换所选角色的菜单授权。" />
    <a-alert v-if="roles.error.value" type="error" show-icon :message="roles.error.value" />
    <div class="role-management-grid">
      <section class="admin-panel role-list">
        <button v-for="role in roles.roles.value" :key="role.id" type="button" :class="{ 'is-active': roles.selectedRole.value?.id === role.id }" @click="roles.selectRole(role)">
          <strong>{{ role.roleName }}</strong><code>{{ role.roleKey }}</code><span>{{ role.dataScope }} · {{ role.status ? '启用' : '停用' }}</span>
        </button>
        <a-empty v-if="!roles.loading.value && roles.roles.value.length === 0" description="暂无固定角色" />
      </section>
      <section class="admin-panel">
        <div class="role-tree-heading"><div><h2>菜单授权</h2><p>父子菜单按显式 ID 集合提交；菜单不代替接口权限。</p></div><a-button type="primary" :loading="roles.saving.value" :disabled="!roles.selectedRole.value" @click="save">保存完整授权</a-button></div>
        <RoleMenuTree :tree="roles.tree.value" :checked-ids="roles.checkedIds.value" @update:checked-ids="roles.checkedIds.value = $event" />
      </section>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted } from 'vue'
import { message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import RoleMenuTree from '@/components/admin/RoleMenuTree.vue'
import { useRoleManagement } from '@/composables/useRoleManagement'
/** 页面不提供角色 CRUD，只编排固定角色选择、完整菜单树和全量保存。 */
const roles = useRoleManagement()
async function save() { try { await roles.save(); message.success('角色菜单授权已替换') } catch { /* 错误已展示在页面，保留当前勾选便于修正。 */ } }
onMounted(() => { void roles.load() })
</script>
<style scoped>
.role-management-grid { display:grid; grid-template-columns:300px minmax(0,1fr); gap:20px; }
.role-list { display:flex; flex-direction:column; gap:8px; }
.role-list button { padding:14px; display:flex; flex-direction:column; align-items:flex-start; gap:3px; color:#a9bfd2; border:1px solid transparent; background:transparent; border-radius:12px; cursor:pointer; text-align:left; }
.role-list button.is-active { color:#edf8ff; border-color:rgba(79,168,235,.28); background:rgba(52,137,201,.12); }
.role-list code { color:#69b6ed; font-size:11px; }.role-list span { color:#718ba3; font-size:11px; }
.role-tree-heading { display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:18px; }.role-tree-heading h2 { margin:0; font-size:18px; }.role-tree-heading p { margin:5px 0 0; color:#91a8bd; font-size:12px; }
</style>
