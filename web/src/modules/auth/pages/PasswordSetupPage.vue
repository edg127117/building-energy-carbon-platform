<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput, ElMessage, type FormInstance, type FormRules } from '@/shared/ui'
import BrandIdentity from '@/shared/components/BrandIdentity.vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import { setupPasswordApi } from '../api/auth'

const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const error = ref<string | null>(null)
const form = reactive({ token: '', password: '' })
const rules = computed<FormRules>(() => ({
  token: [{ required: true, message: t('auth.validation.token'), trigger: 'blur' }],
  password: [{ required: true, message: t('auth.validation.newPassword'), trigger: 'blur' }],
}))

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || submitting.value) return
  submitting.value = true
  error.value = null
  try {
    await setupPasswordApi({ token: form.token.trim(), password: form.password })
    ElMessage.success(t('auth.passwordSetup.success'))
    await router.replace('/login')
  } catch (reason) {
    error.value = requestErrorMessage(reason)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="password-page" :aria-label="t('auth.passwordSetup.title')">
    <ElCard shadow="never" class="password-card">
      <BrandIdentity />
      <div class="heading">
        <h1>{{ t('auth.passwordSetup.title') }}</h1>
        <p>{{ t('auth.passwordSetup.description') }}</p>
      </div>
      <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <ElFormItem :label="t('auth.passwordSetup.token')" prop="token">
          <ElInput v-model="form.token" :placeholder="t('auth.passwordSetup.tokenPlaceholder')" autocomplete="one-time-code" />
        </ElFormItem>
        <ElFormItem :label="t('auth.passwordSetup.password')" prop="password">
          <ElInput v-model="form.password" type="password" show-password :placeholder="t('auth.passwordSetup.passwordPlaceholder')" autocomplete="new-password" />
        </ElFormItem>
        <div class="actions">
          <ElButton type="primary" native-type="submit" :loading="submitting">{{ t('auth.passwordSetup.submit') }}</ElButton>
          <ElButton text @click="router.push('/login')">{{ t('auth.passwordSetup.returnLogin') }}</ElButton>
        </div>
      </ElForm>
    </ElCard>
  </main>
</template>

<style scoped>
.password-page { display: grid; min-height: 100%; place-items: center; padding: var(--bec-space-page); background: var(--bec-color-page); }
.password-card { width: min(100%, var(--bec-dialog-width)); display: grid; gap: var(--bec-space-section); }
.heading { display: grid; gap: var(--bec-space-tight); }
h1 { margin: 0; font-size: var(--bec-font-size-system); }
p { margin: 0; color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); }
</style>
