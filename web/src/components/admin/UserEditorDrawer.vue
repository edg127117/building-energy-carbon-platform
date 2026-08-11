<template>
  <a-drawer :open="open" :title="title" width="440" destroy-on-close @close="$emit('close')">
    <a-form layout="vertical" @submit.prevent="submit">
      <template v-if="mode === 'create'">
        <a-form-item label="用户名" required><a-input v-model:value="form.username" autocomplete="off" /></a-form-item>
      </template>
      <a-form-item v-if="mode !== 'edit'" label="密码" required>
        <a-input-password v-model:value="form.password" autocomplete="new-password" />
      </a-form-item>
      <template v-if="mode !== 'password'">
        <a-form-item label="昵称"><a-input v-model:value="form.nickname" /></a-form-item>
        <a-form-item label="手机号"><a-input v-model:value="form.phone" /></a-form-item>
      </template>
      <div class="drawer-actions"><a-button @click="$emit('close')">取消</a-button><a-button type="primary" html-type="submit" :loading="submitting">保存</a-button></div>
    </a-form>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { UserAdminView } from '@/types/admin'

/** 密码只存在于当前抽屉表单，关闭或模式切换即清空，不进入 Store 和持久化状态。 */
const props = defineProps<{ open: boolean; mode: 'create' | 'edit' | 'password'; user?: UserAdminView | null; submitting?: boolean }>()
const emit = defineEmits<{ close: []; save: [value: { username?: string; password?: string; nickname?: string | null; phone?: string | null }] }>()
const form = reactive({ username: '', password: '', nickname: '', phone: '' })
const title = computed(() => props.mode === 'create' ? '新建用户' : props.mode === 'password' ? '重置密码' : '编辑用户')
watch(() => [props.open, props.mode, props.user] as const, () => {
  form.username = props.mode === 'create' ? '' : (props.user?.username ?? '')
  form.password = ''
  form.nickname = props.user?.nickname ?? ''
  form.phone = props.user?.phone ?? ''
}, { immediate: true })
function submit() {
  if (props.mode === 'create' && (!form.username.trim() || form.password.length < 6)) return
  if (props.mode === 'password' && form.password.length < 6) return
  emit('save', { username: form.username.trim() || undefined, password: form.password || undefined,
    nickname: form.nickname.trim() || null, phone: form.phone.trim() || null })
}
</script>
