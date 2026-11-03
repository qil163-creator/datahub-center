<script lang="ts" setup>
import type { RankItem } from '../data';

import { computed } from 'vue';

import { Card, Empty } from 'antdv-next';

defineOptions({ name: 'SupplyRankChart' });

const props = defineProps<{
  list: RankItem[];
  title: string;
}>();

const maxValue = computed(() => Math.max(0, ...props.list.map((item) => item.value)));

function barWidth(value: number) {
  return `${maxValue.value > 0 ? (Math.max(0, value) / maxValue.value) * 100 : 0}%`;
}
</script>

<template>
  <Card :title="title">
    <div v-if="list.length === 0" class="rank-empty">
      <Empty :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无调用记录" />
    </div>
    <ol v-else class="rank-list">
      <li v-for="(item, index) in list" :key="`${item.apiId ?? 'unknown'}-${index}`" class="rank-item">
        <span class="rank-item__number">{{ index + 1 }}</span>
        <div class="rank-item__content">
          <div class="rank-item__heading">
            <span class="rank-item__name" :title="item.name">{{ item.name || '未命名 API' }}</span>
            <span class="rank-item__value">{{ item.value.toLocaleString() }} 次</span>
          </div>
          <div class="rank-item__track" aria-hidden="true">
            <div class="rank-item__bar" :style="{ width: barWidth(item.value) }"></div>
          </div>
        </div>
      </li>
    </ol>
  </Card>
</template>

<style scoped>
.rank-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}

.rank-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 240px;
  padding: 4px 0;
  margin: 0;
  list-style: none;
}

.rank-item {
  display: flex;
  gap: 12px;
  align-items: center;
}

.rank-item__number {
  flex: none;
  width: 24px;
  font-size: 13px;
  font-weight: 600;
  color: hsl(var(--muted-foreground));
  text-align: center;
}

.rank-item__content {
  flex: 1;
  min-width: 0;
}

.rank-item__heading {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.rank-item__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-item__value {
  flex: none;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--muted-foreground));
}

.rank-item__track {
  height: 6px;
  overflow: hidden;
  background: hsl(var(--muted));
  border-radius: 3px;
}

.rank-item__bar {
  height: 100%;
  background: #5ab1ef;
  border-radius: inherit;
}
</style>
