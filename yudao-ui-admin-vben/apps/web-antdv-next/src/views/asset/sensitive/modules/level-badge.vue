<script setup lang="ts">
defineProps<{ color?: string; name?: string }>();
const shield = '/assets/qdata-market/level-shield.svg';
</script>

<template>
  <span
    v-if="name"
    class="sensitive-level"
    :style="{ backgroundColor: color || '#909399' }"
    ><img :src="shield" alt="" />{{ name }}</span>
  <span v-else>-</span>
</template>

<style scoped>
.sensitive-level {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  min-width: 38px;
  height: 18px;
  padding: 0 5px;
  font-size: 12px;
  line-height: 18px;
  color: hsl(var(--primary-foreground));
  white-space: nowrap;
  background: hsl(var(--muted-foreground));
  border-radius: 10px;
}

.sensitive-level img {
  width: 11px;
  height: 11px;
}
</style>
