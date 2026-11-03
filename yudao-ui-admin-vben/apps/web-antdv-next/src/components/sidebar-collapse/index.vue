<script setup lang="ts">
import { IconifyIcon } from '@vben/icons';

withDefaults(defineProps<{ collapsed: boolean; label?: string }>(), {
  label: '侧栏',
});
defineEmits<{ toggle: [] }>();
</script>

<template>
  <div class="sidebar-collapse" :class="{ 'is-collapsed': collapsed }">
    <button
      type="button"
      class="sidebar-collapse__button"
      :aria-label="`${collapsed ? '展开' : '收起'}${label}`"
      :title="`${collapsed ? '展开' : '收起'}${label}`"
      :aria-expanded="!collapsed"
      @click="$emit('toggle')"
    >
      <IconifyIcon
        :icon="collapsed ? 'lucide:chevron-right' : 'lucide:chevron-left'"
        :size="14"
      />
    </button>
  </div>
</template>

<style scoped>
.sidebar-collapse {
  position: relative;
  flex: 0 0 12px;
  align-self: stretch;
  width: 12px;
  min-width: 12px;
  min-height: 0;
}

.sidebar-collapse__button {
  position: absolute;
  top: 48%;
  left: -2px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 40px;
  padding: 0;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  background: hsl(var(--muted));
  border: 0;
  border-radius: 3px;
}

.sidebar-collapse__button:hover {
  color: hsl(var(--foreground));
}

.sidebar-collapse.is-collapsed .sidebar-collapse__button {
  left: 0;
}

.sidebar-collapse__button:focus-visible {
  outline: 2px solid hsl(var(--primary));
  outline-offset: -2px;
}

@media (width <= 640px) {
  .sidebar-collapse__button {
    top: -8px;
    right: 4px;
    left: auto;
    width: 24px;
    height: 24px;
  }

  .sidebar-collapse.is-collapsed .sidebar-collapse__button {
    left: auto;
  }
}
</style>
