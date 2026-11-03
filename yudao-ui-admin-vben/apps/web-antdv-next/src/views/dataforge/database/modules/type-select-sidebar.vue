<script lang="ts" setup>
import type { DatabaseTypeCategory } from '../composables/use-database-type-select';

defineOptions({ name: 'DatabaseTypeSelectSidebar' });

defineProps<{
  activeCode: string;
  categories: DatabaseTypeCategory[];
}>();

const emit = defineEmits<{
  select: [code: string];
}>();
</script>

<template>
  <aside class="type-select-sidebar">
    <button
      v-for="item in categories"
      :key="item.code"
      type="button"
      class="type-select-sidebar__item"
      :class="{ 'type-select-sidebar__item--active': item.code === activeCode }"
      @click="emit('select', item.code)"
    >
      {{ item.name }}
    </button>
  </aside>
</template>

<style scoped>
.type-select-sidebar {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  width: 188px;
  padding: 8px 0;
  overflow: auto;
  background: hsl(var(--background));
  border-right: 1px solid hsl(var(--border));
}

.type-select-sidebar__item {
  width: 100%;
  padding: 10px 20px;
  font-size: 14px;
  line-height: 22px;
  color: hsl(var(--foreground) / 80%);
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: none;
}

.type-select-sidebar__item:hover {
  background: hsl(var(--accent));
}

.type-select-sidebar__item--active {
  font-weight: 600;
  color: hsl(var(--foreground));
  background: hsl(var(--accent));
}
</style>
