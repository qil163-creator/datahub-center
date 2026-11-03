<script setup lang="ts">
import { Spin } from 'antdv-next';

defineProps<{ busy?: boolean; title?: string }>();
</script>

<template>
  <section class="sync-form-page" :aria-label="title" :aria-busy="busy">
    <Spin v-if="busy" class="sync-form-page__loading" />
    <div class="sync-form-page__body" :inert="busy || undefined">
      <slot></slot>
    </div>
    <footer class="sync-form-page__footer"><slot name="footer"></slot></footer>
  </section>
</template>

<style scoped>
.sync-form-page {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.sync-form-page__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.sync-form-page__footer {
  flex-shrink: 0;
  padding: 18px 32px;
  background: hsl(var(--background));
  border-top: 1px solid hsl(var(--border));
}

.sync-form-page__loading {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: grid;
  place-items: center;
  background: hsl(var(--background) / 65%);
}
</style>
