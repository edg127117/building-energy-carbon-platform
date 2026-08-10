<template>
  <header class="admin-page-header">
    <div>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="admin-page-actions"><slot /></div>
  </header>
</template>

<script setup lang="ts">
/** 统一管理页标题和主操作插槽，不读取任何业务 API 或管理状态。 */
defineProps<{
  title: string
  description: string
  eyebrow?: string
}>()
</script>
