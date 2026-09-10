<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput } from '@/shared/ui'
import BrandIdentity from '@/shared/components/BrandIdentity.vue'
import { t } from '@/locales'
import { useSession } from '../stores/session'
const session = useSession()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
async function submit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = t('workspaces.required'); return }
  busy.value = true
  try { await session.login(username.value.trim(), password.value); await router.replace('/systems') }
  catch { error.value = t('workspaces.loginFailed') }
  finally { password.value = ''; busy.value = false }
}
</script>
<template>
  <div class="login-page">
    <BrandIdentity />
    <main class="login-form">
      <h1>{{ t('workspaces.login') }}</h1>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" />
      <ElForm label-position="top" @submit.prevent="submit">
        <ElFormItem :label="t('workspaces.username')"><ElInput v-model="username" name="username" autocomplete="username" :aria-label="t('workspaces.username')" /></ElFormItem>
        <ElFormItem :label="t('workspaces.password')"><ElInput v-model="password" type="password" show-password name="password" autocomplete="current-password" :aria-label="t('workspaces.password')" /></ElFormItem>
        <ElButton type="primary" native-type="submit" :loading="busy">{{ t('workspaces.login') }}</ElButton>
      </ElForm>
    </main>
  </div>
</template>
<style scoped>
.login-page { min-height: 100%; padding: var(--bec-space-page); display: grid; grid-template-rows: auto 1fr; }
.login-form { align-self: center; justify-self: center; width: min(100%, var(--bec-login-width)); padding: var(--bec-space-page); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
h1 { margin: 0 0 var(--bec-space-section); font-size: var(--bec-font-size-system); }
.el-form { margin-top: var(--bec-space-section); }
</style>
