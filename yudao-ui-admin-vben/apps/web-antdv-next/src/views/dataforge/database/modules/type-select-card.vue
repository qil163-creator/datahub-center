<script lang="ts" setup>
import type { DatabaseTypeViewMode } from '../composables/use-database-type-select';

import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { computed } from 'vue';

import { ConnectionIcon } from '#/components/connection-icon';

import {
  connectionCapability,
  isConnectionTypeSelectable,
} from '../connection-capabilities';

defineOptions({ name: 'DatabaseTypeSelectCard' });

const props = defineProps<{
  item: FoundationDatabaseTypeApi.DatabaseType;
  selected?: boolean;
  viewMode?: DatabaseTypeViewMode;
}>();

const emit = defineEmits<{
  select: [item: FoundationDatabaseTypeApi.DatabaseType];
}>();

const capability = computed(() => connectionCapability(props.item));
const unavailable = computed(() => !isConnectionTypeSelectable(props.item));

function handleSelect() {
  emit('select', props.item);
}
</script>

<template>
  <button
    type="button"
    :disabled="unavailable"
    :title="
      unavailable
        ? capability?.reason || '当前部署未提供此产品的连接能力'
        : undefined
    "
    class="type-select-card"
    :class="[
      viewMode === 'list' ? 'type-select-card--list' : 'type-select-card--grid',
      { 'type-select-card--selected': selected },
    ]"
    @click="handleSelect"
  >
    <div class="type-select-card__icon">
      <ConnectionIcon
        :icon-path="item.iconPath"
        :type-code="item.typeCode || item.productId"
        :alt="item.typeName"
        class="shrink-0"
        :class="viewMode === 'list' ? 'size-8' : 'size-11'"
      />
    </div>
    <span class="type-select-card__name">{{ item.typeName }}</span>
    <span
      v-if="item.description"
      class="type-select-card__description text-muted-foreground"
      :title="item.description"
      >{{ item.description }}</span>
    <span class="text-xs text-muted-foreground">{{
      unavailable
        ? '暂不可用'
        : capability?.status === 'VERIFIED'
          ? '已验证'
          : '待兼容验证'
    }}</span>
  </button>
</template>

<style scoped>
.type-select-card {
  display: flex;
  color: hsl(var(--foreground));
  cursor: pointer;
  background: hsl(var(--background));
  border: 1px solid hsl(var(--border));
  border-radius: 12px;
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease;
}

.type-select-card:hover {
  border-color: hsl(var(--foreground) / 35%);
}

.type-select-card:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.type-select-card--selected {
  background: hsl(var(--accent));
  border-color: hsl(var(--foreground) / 55%);
}

.type-select-card--grid {
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  min-height: 118px;
  padding: 16px 8px 12px;
}

.type-select-card--list {
  flex-direction: row;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 10px 14px;
}

.type-select-card__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
}

.type-select-card--list .type-select-card__icon {
  width: 32px;
  height: 32px;
}

.type-select-card__name {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  line-height: 20px;
  text-align: center;
  white-space: nowrap;
}

.type-select-card--list .type-select-card__name {
  text-align: left;
}

.type-select-card__description {
  display: -webkit-box;
  max-width: 100%;
  overflow: hidden;
  -webkit-line-clamp: 2;
  font-size: 12px;
  line-height: 18px;
  text-align: left;
  -webkit-box-orient: vertical;
}

.type-select-card--list .type-select-card__description {
  flex: 1;
}
</style>
