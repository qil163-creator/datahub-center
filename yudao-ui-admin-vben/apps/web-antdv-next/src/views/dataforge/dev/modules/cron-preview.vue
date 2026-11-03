<script lang="ts" setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { previewDevCron } from '#/api/dataforge/dev';

const props = withDefaults(
  defineProps<{
    active?: boolean;
    compact?: boolean;
    expression?: string;
  }>(),
  { active: true, compact: false, expression: undefined },
);
const nextTimes = ref<number[]>([]);
const error = ref('');
const loading = ref(false);
const accessStore = useAccessStore();
const expressionFields = computed(() => {
  const values = props.expression?.trim().split(/\s+/) ?? [];
  return ['秒', '分钟', '小时', '日', '月', '周', '年'].map((label, index) => ({
    label,
    value: values[index] || '—',
  }));
});
let generation = 0;
let timer: ReturnType<typeof setTimeout> | undefined;

function refresh() {
  const seq = ++generation;
  if (timer) clearTimeout(timer);
  timer = undefined;
  nextTimes.value = [];
  error.value = '';
  loading.value = false;
  const expression = props.expression?.trim();
  if (!props.active || !expression) return;
  loading.value = true;
  timer = setTimeout(async () => {
    timer = undefined;
    try {
      const result = await previewDevCron(expression);
      if (seq === generation) nextTimes.value = result.slice(0, 5);
    } catch {
      if (seq === generation)
        error.value = '运行时间计算失败，请检查表达式或重试。';
    } finally {
      if (seq === generation) loading.value = false;
    }
  }, 350);
}

watch(
  () => [
    props.expression,
    props.active,
    accessStore.accessToken,
    accessStore.refreshToken,
  ],
  refresh,
  { immediate: true },
);
onBeforeUnmount(() => {
  ++generation;
  if (timer) clearTimeout(timer);
});
</script>

<template>
  <div class="cron-preview" :class="{ 'cron-preview-compact': compact }">
    <section v-if="!compact" class="cron-expression-section">
      <h3 class="cron-section-title">时间表达式</h3>
      <div class="cron-expression-grid">
        <div v-for="field in expressionFields" :key="field.label">
          <span class="cron-field-label">{{ field.label }}</span>
          <code>{{ field.value }}</code>
        </div>
        <div class="cron-expression-full">
          <span class="cron-field-label">Cron 表达式</span>
          <code>{{ expression?.trim() || '尚未配置' }}</code>
        </div>
      </div>
    </section>
    <section class="cron-times-section" aria-live="polite" :aria-busy="loading">
      <div class="cron-times-heading">
        <h3 class="cron-section-title">未来 5 次运行时间</h3>
        <span class="cron-hint">按任务调度配置预览</span>
      </div>
      <div v-if="loading" class="cron-preview-state" role="status">
        <span class="cron-loading-dot" aria-hidden="true"></span>
        正在计算运行时间…
      </div>
      <div v-else-if="error" class="cron-preview-state cron-error" role="alert">
        <span>{{ error }}</span>
        <button class="cron-retry" type="button" @click="refresh">
          <IconifyIcon
            icon="lucide:calculator"
            class="mr-1 inline-block size-4 align-middle"
          />
          重新计算
        </button>
      </div>
      <ol v-else-if="nextTimes.length" class="cron-next-times">
        <li v-for="(time, index) in nextTimes" :key="`${time}-${index}`">
          <span class="cron-time-order" aria-hidden="true">{{
            index + 1
          }}</span>
          <time>{{ formatDateTime(time) }}</time>
          <span v-if="index === 0" class="cron-next-label">下次运行</span>
        </li>
      </ol>
      <div v-else class="cron-preview-state">
        {{
          expression?.trim()
            ? '当前表达式没有后续运行时间，请检查日期和年份范围。'
            : '配置调度周期后，将在这里显示运行时间。'
        }}
      </div>
    </section>
  </div>
</template>

<style scoped>
.cron-preview {
  display: flex;
  flex-direction: column;
  gap: 18px;
  font-size: 13px;
  color: hsl(var(--foreground));
}

.cron-expression-section,
.cron-times-section {
  padding: 16px 18px;
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.cron-section-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.cron-expression-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr)) minmax(220px, 4fr);
  gap: 8px;
  margin-top: 14px;
  text-align: center;
}

.cron-field-label {
  display: block;
  margin-bottom: 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.cron-expression-grid code {
  display: block;
  padding: 8px 4px;
  overflow: auto;
  font-family: Consolas, SFMono-Regular, monospace;
  font-size: 12px;
  white-space: nowrap;
  background: hsl(var(--muted) / 35%);
  border: 1px solid hsl(var(--border));
  border-radius: 3px;
}

.cron-times-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.cron-hint {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.cron-next-times {
  padding: 0;
  margin: 14px 0 0;
  list-style: none;
}

.cron-next-times li {
  display: flex;
  gap: 12px;
  align-items: center;
  min-height: 34px;
}

.cron-next-times time {
  font-family: Consolas, SFMono-Regular, monospace;
  font-variant-numeric: tabular-nums;
}

.cron-time-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted));
  border-radius: 50%;
}

.cron-next-label {
  padding: 1px 6px;
  font-size: 11px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 8%);
  border-radius: 3px;
}

.cron-preview-state {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  min-height: 66px;
  line-height: 1.7;
  color: hsl(var(--muted-foreground));
}

.cron-loading-dot {
  width: 7px;
  height: 7px;
  background: hsl(var(--primary));
  border-radius: 50%;
}

.cron-error {
  color: hsl(var(--destructive));
}

.cron-retry {
  padding: 2px 0;
  color: hsl(var(--primary));
  cursor: pointer;
  background: none;
  border: 0;
}

.cron-retry:focus-visible {
  outline: 2px solid hsl(var(--primary));
  outline-offset: 3px;
}

.cron-preview-compact .cron-times-section {
  padding: 0;
  border: 0;
}

@media (width <= 760px) {
  .cron-expression-grid {
    grid-template-columns: repeat(7, minmax(0, 1fr));
    gap: 5px;
  }

  .cron-expression-full {
    grid-column: 1 / -1;
    margin-top: 8px;
  }

  .cron-expression-section,
  .cron-times-section {
    padding: 14px 12px;
  }
}
</style>
