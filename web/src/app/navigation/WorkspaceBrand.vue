<script setup lang="ts">
import { t } from '@/locales'

// 管理入口独立保留品牌槽，正式素材由父级提供，不改变登录页与大屏的品牌呈现。
</script>
<template>
  <div class="workspace-brand">
    <span class="company-logo" role="img" :aria-label="t('workspaces.companyLogo')">
      <slot name="logo">{{ t('workspaces.companyLogo') }}</slot>
    </span>
    <strong>{{ t('terminology.systemName') }}</strong>
  </div>
</template>
<style scoped>
.workspace-brand { display: flex; align-items: center; gap: var(--bec-space-group); flex-shrink: 0; }
.company-logo { display: grid; place-items: center; width: var(--bec-company-logo-width); height: var(--bec-control-height); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); overflow: hidden; }
.company-logo :slotted(img) { width: 100%; height: 100%; object-fit: contain; }
strong { padding-left: var(--bec-space-group); border-left: var(--bec-border-width) solid var(--bec-color-border); font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); white-space: nowrap; }
</style>
