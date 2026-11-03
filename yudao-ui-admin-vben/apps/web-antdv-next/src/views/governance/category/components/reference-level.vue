<script setup lang="ts">
import { computed } from 'vue';

import { CATEGORY_LEVEL_COLORS } from '../reference-data';

const props = defineProps<{ color?: string; level?: string }>();
const shieldIcon = '/assets/qdata-safety-category/level-shield.png';
const color = computed(() =>
  props.color && /^#[0-9a-f]{3,8}$/iu.test(props.color)
    ? props.color
    : CATEGORY_LEVEL_COLORS[props.level || ''] || '#909399',
);
</script>

<template>
  <span v-if="level" class="category-level" :style="{ backgroundColor: color }">
    <img :src="shieldIcon" alt="" />
    <span>{{ level }}</span>
  </span>
  <span v-else>-</span>
</template>

<style scoped>
.category-level {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  height: 20px;
  padding: 0 5px;
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  color: #fff;
  border-radius: 10px;
}

.category-level img {
  width: 12px;
  height: 14px;
}
</style>
