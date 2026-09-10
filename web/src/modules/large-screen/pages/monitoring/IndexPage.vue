<script setup lang="ts">
import PendingPage from '@/shared/components/PendingPage.vue'
import { t } from '@/locales'
defineProps<{ title?: string }>()
</script>
<template><PendingPage :title="title ?? t('largeScreen.monitoring')" /></template>
