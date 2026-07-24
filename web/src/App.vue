<template>
  <a-config-provider :theme="antdTheme">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { theme } from 'ant-design-vue'

const antdTheme = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#2F7DFF',
    colorInfo: '#2F7DFF',
    colorSuccess: '#1DD6A4',
    colorWarning: '#FFB020',
    colorError: '#FF4D6D',
    borderRadius: 10,
  },
}
</script>
