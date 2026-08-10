<template>
  <a-drawer :open="open" :title="menu ? '编辑菜单' : '新增菜单'" width="520" @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item label="父级"><a-select v-model:value="form.parentId" :options="parentOptions" /></a-form-item>
      <a-form-item label="菜单名称" required><a-input v-model:value="form.menuName" /></a-form-item>
      <div class="menu-form-grid"><a-form-item label="类型"><a-select v-model:value="form.menuType" :options="typeOptions" /></a-form-item><a-form-item label="排序"><a-input-number v-model:value="form.sortOrder" :min="0" /></a-form-item></div>
      <a-form-item label="路由路径"><a-input v-model:value="form.path" placeholder="仅显式注册路径会成为前端链接" /></a-form-item>
      <a-form-item label="组件标识"><a-input v-model:value="form.component" placeholder="仅供维护展示，不用于动态加载" /></a-form-item>
      <div class="menu-form-grid"><a-form-item label="权限标识"><a-input v-model:value="form.perms" /></a-form-item><a-form-item label="图标"><a-input v-model:value="form.icon" /></a-form-item></div>
      <div class="menu-form-grid"><a-form-item label="导航可见"><a-switch v-model:checked="form.visible" /></a-form-item><a-form-item label="启用状态"><a-switch v-model:checked="form.status" /></a-form-item></div>
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存菜单</a-button></div>
    </a-form>
  </a-drawer>
</template>
<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { MenuCreateRequest, MenuNode } from '@/types/admin'
/** 表单只暴露后端 DTO 接受字段；时间、children 和新增主键均不可提交。 */
const props = defineProps<{ open: boolean; menu?: MenuNode | null; parentOptions: Array<{ label: string; value: number }>; submitting?: boolean }>()
const emit = defineEmits<{ close: []; save: [request: MenuCreateRequest] }>()
const typeOptions = [{ label: '目录（M）', value: 'M' }, { label: '页面（C）', value: 'C' }, { label: '按钮（F）', value: 'F' }]
const form = reactive({ parentId: 0, menuName: '', menuType: 'C' as 'M' | 'C' | 'F', path: '', component: '', perms: '', icon: '', visible: true, status: true, sortOrder: 0 })
watch(() => [props.open, props.menu] as const, () => Object.assign(form, { parentId: props.menu?.parentId ?? 0, menuName: props.menu?.menuName ?? '', menuType: props.menu?.menuType ?? 'C',
  path: props.menu?.path ?? '', component: props.menu?.component ?? '', perms: props.menu?.perms ?? '', icon: props.menu?.icon ?? '', visible: (props.menu?.visible ?? 1) === 1, status: (props.menu?.status ?? 1) === 1, sortOrder: props.menu?.sortOrder ?? 0 }), { immediate: true })
function submit() { if (!form.menuName.trim()) return; emit('save', { parentId: form.parentId, menuName: form.menuName.trim(), menuType: form.menuType,
  path: form.path.trim() || null, component: form.component.trim() || null, perms: form.perms.trim() || null, icon: form.icon.trim() || null,
  visible: form.visible ? 1 : 0, status: form.status ? 1 : 0, sortOrder: form.sortOrder }) }
</script>
