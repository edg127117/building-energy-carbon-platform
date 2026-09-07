<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput, ElMessage, type FormInstance, type FormRules } from '@/shared/ui'
import BrandIdentity from '@/shared/components/BrandIdentity.vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const error = ref<string | null>(null)
const form = reactive({ username: '', password: '' })
const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('auth.validation.username'), trigger: 'blur' }],
  password: [{ required: true, message: t('auth.validation.password'), trigger: 'blur' }],
}))

function targetPath() {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/office'
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || submitting.value) return
  submitting.value = true
  error.value = null
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    ElMessage.success(t('auth.login.success'))
    await router.replace(targetPath())
  } catch (reason) {
    error.value = requestErrorMessage(reason)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page" :aria-label="t('auth.login.title')">
    <ElCard shadow="never" class="login-card">
      <BrandIdentity />
      <div class="heading">
        <h1>{{ t('auth.login.title') }}</h1>
        <p>{{ t('auth.login.description') }}</p>
      </div>
      <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <ElFormItem :label="t('auth.login.username')" prop="username">
          <ElInput v-model="form.username" :placeholder="t('auth.login.usernamePlaceholder')" autocomplete="username" />
        </ElFormItem>
        <ElFormItem :label="t('auth.login.password')" prop="password">
          <ElInput v-model="form.password" type="password" show-password :placeholder="t('auth.login.passwordPlaceholder')" autocomplete="current-password" />
        </ElFormItem>
        <div class="actions">
          <ElButton type="primary" native-type="submit" :loading="submitting">{{ t('auth.login.submit') }}</ElButton>
          <ElButton text @click="router.push('/password-setup')">{{ t('auth.login.setupPassword') }}</ElButton>
        </div>
      </ElForm>
    </ElCard>
  </main>
</template>

<style scoped>
.login-page { display: grid; min-height: 100%; place-items: center; padding: var(--bec-space-page); background: var(--bec-color-page); }
.login-card { width: min(100%, var(--bec-dialog-width)); display: grid; gap: var(--bec-space-section); }
.heading { display: grid; gap: var(--bec-space-tight); }
h1 { margin: 0; font-size: var(--bec-font-size-system); }
p { margin: 0; color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); }
</style>
