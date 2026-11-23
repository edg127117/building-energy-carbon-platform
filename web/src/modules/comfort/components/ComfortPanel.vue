<script setup lang="ts">
defineProps<{ heading: string }>()
</script>

<template>
  <section class="comfort-panel">
    <header class="comfort-panel-heading">
      <div class="comfort-panel-title">
        <h2>{{ heading }}</h2>
        <slot name="status" />
      </div>
      <slot name="actions" />
    </header>
    <slot />
  </section>
</template>

<style scoped>
.comfort-panel {
  display: flex;
  flex-direction: column;
  gap: var(--bec-ref-comfort-heading-gap);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding: var(--bec-comfort-card-padding);
  background: var(--bec-color-surface);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-management-radius);
  box-shadow: var(--bec-shadow-card);
}
.comfort-panel-heading {
  display: flex;
  flex-wrap: nowrap;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-8);
  min-width: 0;
}
.comfort-panel-title { display: flex; align-items: center; gap: var(--bec-ref-space-6); min-width: 0; }
.comfort-panel-heading h2 {
  margin: 0;
  color: var(--bec-color-text-primary);
  font-size: var(--bec-ref-comfort-font-card);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
