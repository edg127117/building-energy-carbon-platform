<script setup lang="ts">
import { t } from '@/locales'
import companyLogo from '@/shared/assets/tengcore-logo.png'

// 公司标识仅用于管理入口，保留品牌槽供后续定制，不影响登录页与大屏。
</script>
<template>
  <div class="workspace-brand">
    <span class="company-logo" role="img" :aria-label="t('workspaces.companyLogo')">
      <slot name="logo"><img :src="companyLogo" alt="" /></slot>
    </span>
    <strong>{{ t('terminology.systemName') }}</strong>
  </div>
</template>
<style scoped>
.workspace-brand { display: flex; align-items: center; gap: var(--bec-space-group); flex-shrink: 0; }
.company-logo { display: grid; place-items: center; width: var(--bec-company-logo-width); height: var(--bec-company-logo-height); overflow: hidden; }
.company-logo img, .company-logo :slotted(img) { display: block; width: 100%; height: 100%; object-fit: contain; }
strong { font-size: var(--bec-workspace-brand-title-size); font-weight: var(--bec-font-weight-normal); white-space: nowrap; }
</style>
