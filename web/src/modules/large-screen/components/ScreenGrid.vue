<template><div class="screen-grid"><slot /></div></template>
<style scoped>
.screen-grid { display: grid; grid-template-columns: repeat(8, minmax(0, 1fr)); grid-template-rows: repeat(4, minmax(0, 1fr)); gap: var(--bec-monitor-gap); min-width: 0; min-height: 0; height: 100%; }
</style>
