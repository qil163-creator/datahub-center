<script setup lang="ts">
import { IconifyIcon } from '@vben/icons';

import { Button, Tooltip } from 'antdv-next';

withDefaults(
  defineProps<{
    compact?: boolean;
    disabled?: boolean;
    expanded: boolean;
    loading?: boolean;
    refreshLabel?: string;
    showRefresh?: boolean;
  }>(),
  {
    compact: false,
    disabled: false,
    loading: false,
    refreshLabel: '刷新树',
    showRefresh: true,
  },
);

defineEmits<{ refresh: []; toggleExpand: [] }>();
</script>

<template>
  <div class="tree-toolbar" :class="{ 'tree-toolbar--compact': compact }">
    <Tooltip :title="expanded ? '全部收起' : '全部展开'">
      <Button
        size="middle"
        :title="expanded ? '全部收起' : '全部展开'"
        :aria-label="expanded ? '全部收起' : '全部展开'"
        :disabled="disabled"
        @click="$emit('toggleExpand')"
      >
        <template #icon>
          <IconifyIcon
            :icon="expanded ? 'lucide:fold-vertical' : 'lucide:unfold-vertical'"
          />
        </template>
      </Button>
    </Tooltip>
    <Tooltip v-if="showRefresh" :title="refreshLabel">
      <Button
        size="middle"
        :title="refreshLabel"
        :aria-label="refreshLabel"
        :disabled="disabled || loading"
        @click="$emit('refresh')"
      >
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
      </Button>
    </Tooltip>
  </div>
</template>

<style scoped>
.tree-toolbar {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
}

.tree-toolbar :deep(.ant-btn) {
  flex-shrink: 0;
}

.tree-toolbar--compact :deep(.ant-btn) {
  width: 28px;
  min-width: 28px;
  height: 28px;
  padding: 0;
}
</style>
