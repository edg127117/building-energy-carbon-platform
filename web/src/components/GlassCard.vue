<template>
  <div
    class="relative overflow-hidden rounded-2xl border border-white/10 bg-white/[0.04] shadow-[0_0_0_1px_rgba(255,255,255,0.04),0_18px_60px_rgba(0,0,0,0.45)] backdrop-blur-xl"
  >
    <div class="pointer-events-none absolute inset-0 opacity-70 [mask-image:radial-gradient(60%_60%_at_50%_20%,black,transparent)]">
      <div class="absolute -inset-24 bg-[conic-gradient(from_180deg_at_50%_50%,rgba(47,125,255,0.22),rgba(29,214,164,0.10),rgba(255,77,109,0.12),rgba(47,125,255,0.22))]" />
    </div>
    <div class="relative p-5">
      <slot />
    </div>
  </div>
</template>
