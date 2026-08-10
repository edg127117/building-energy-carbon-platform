<template>
  <a-drawer :open="open" :title="menu ? '编辑菜单' : '新增菜单'" width="520" @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item label="父级"><a-select v-model:value="form.parentId" :options="parentOptions" /></a-form-item>
      <a-form-item label="菜单名称" required><a-input v-model:value="form.menuName" /></a-form-item>
      <div class="menu-form-grid"><a-form-item label="用途"><a-select v-model:value="form.menuType" :options="typeOptions" /></a-form-item><a-form-item label="排序"><a-input-number v-model:value="form.sortOrder" :min="0" /></a-form-item></div>
      <div class="menu-form-grid"><a-form-item label="导航中显示"><a-switch v-model:checked="form.visible" /></a-form-item><a-form-item label="菜单可用"><a-switch v-model:checked="form.status" /></a-form-item></div>
      <details :key="drawerContentKey" class="menu-advanced">
        <summary><span class="menu-advanced-title"><strong>高级配置</strong><small>仅系统维护人员修改</small></span><ChevronDown class="menu-advanced-chevron" :size="17" aria-hidden="true" /></summary>
        <div class="menu-advanced-content">
          <a-form-item label="路由路径"><a-input v-model:value="form.path" placeholder="仅已注册的页面路径可以打开" /></a-form-item>
          <a-form-item label="组件标识"><a-input v-model:value="form.component" placeholder="仅用于配置记录，不会动态加载组件" /></a-form-item>
          <div class="menu-form-grid"><a-form-item label="权限标识"><a-input v-model:value="form.perms" /></a-form-item><a-form-item label="图标"><a-input v-model:value="form.icon" /></a-form-item></div>
        </div>
      </details>
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存菜单</a-button></div>
    </a-form>
  </a-drawer>
</template>
<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ChevronDown } from 'lucide-vue-next'
import type { MenuCreateRequest, MenuNode } from '@/types/admin'
/** 表单只暴露后端 DTO 接受字段；时间、children 和新增主键均不可提交。 */
const props = defineProps<{ open: boolean; menu?: MenuNode | null; initialParentId?: number; parentOptions: Array<{ label: string; value: number }>; submitting?: boolean }>()
const emit = defineEmits<{ close: []; save: [request: MenuCreateRequest] }>()
const drawerContentKey = computed(() => `${props.open}:${props.menu?.id ?? 'new'}:${props.initialParentId ?? 0}`)
const typeOptions = [{ label: '目录', value: 'M' }, { label: '页面入口', value: 'C' }, { label: '操作权限', value: 'F' }]
const form = reactive({ parentId: 0, menuName: '', menuType: 'C' as 'M' | 'C' | 'F', path: '', component: '', perms: '', icon: '', visible: true, status: true, sortOrder: 0 })
watch(() => [props.open, props.menu, props.initialParentId] as const, () => Object.assign(form, { parentId: props.menu?.parentId ?? props.initialParentId ?? 0, menuName: props.menu?.menuName ?? '', menuType: props.menu?.menuType ?? 'C',
  path: props.menu?.path ?? '', component: props.menu?.component ?? '', perms: props.menu?.perms ?? '', icon: props.menu?.icon ?? '', visible: (props.menu?.visible ?? 1) === 1, status: (props.menu?.status ?? 1) === 1, sortOrder: props.menu?.sortOrder ?? 0 }), { immediate: true })
function submit() { if (!form.menuName.trim()) return; emit('save', { parentId: form.parentId, menuName: form.menuName.trim(), menuType: form.menuType,
  path: form.path.trim() || null, component: form.component.trim() || null, perms: form.perms.trim() || null, icon: form.icon.trim() || null,
  visible: form.visible ? 1 : 0, status: form.status ? 1 : 0, sortOrder: form.sortOrder }) }
</script>
<style scoped>
.menu-advanced {
  margin-top: 4px;
  border-top: 1px solid rgba(120, 169, 207, .18);
}

.menu-advanced summary {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: #d4e1ed;
  cursor: pointer;
}

.menu-advanced summary::marker { content: ''; }
.menu-advanced-title { display: flex; flex-direction: column; gap: 2px; }
.menu-advanced-title strong { font-weight: 500; }
.menu-advanced-title small { color: #7f98ad; font-size: 12px; }
.menu-advanced-chevron { color: #79b9e7; transition: transform .18s ease-out; }
.menu-advanced[open] .menu-advanced-chevron { transform: rotate(180deg); }
.menu-advanced-content { padding-top: 6px; }
</style>
