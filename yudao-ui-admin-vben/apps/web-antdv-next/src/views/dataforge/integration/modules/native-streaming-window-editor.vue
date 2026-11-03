<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅由明确用户操作修改节点隔离草稿。 */
import { computed } from 'vue';

import { Button, InputNumber } from 'antdv-next';

const props = defineProps<{config: Record<string, any>; disabled?: boolean}>();
const window = computed(() => props.config.streamingWindow);
function enable() {
  if (!props.disabled && !window.value) props.config.streamingWindow = {
    version: 1, kind: 'TUMBLING', timeMode: 'PROCESSING_TIME', sizeMillis: 60_000,
    maxRows: 10_000, maxBytes: 16_777_216, emit: 'ON_CLOSE', latePolicy: 'PROCESS_ON_ARRIVAL',
  };
}
function set(key: string, value: unknown) { if (!props.disabled && window.value) window.value[key] = value; }
</script>

<template>
  <details class="qi-transform-advanced">
    <summary>实时窗口与状态配置</summary>
    <p class="qi-reference-notice">按数据到达算子的处理时间划分固定滚动窗口，在窗口关闭时输出。跨窗口相同键独立处理；这不是事件时间迟到补发。</p>
    <Button v-if="!window" :disabled="disabled" @click="enable">明确启用有限实时窗口</Button>
    <template v-else>
      <div class="qi-reference-form-grid">
        <label class="qi-reference-form-row">窗口毫秒<InputNumber :value="window.sizeMillis" :disabled="disabled" :min="1000" :max="86400000" :precision="0" aria-label="实时窗口毫秒" @update:value="(value) => set('sizeMillis', value)" /></label>
        <label class="qi-reference-form-row">每窗每组最大行数<InputNumber :value="window.maxRows" :disabled="disabled" :min="1" :max="10000" :precision="0" aria-label="实时窗口最大行数" @update:value="(value) => set('maxRows', value)" /></label>
        <label class="qi-reference-form-row">每窗每组最大字节<InputNumber :value="window.maxBytes" :disabled="disabled" :min="1024" :max="16777216" :precision="0" aria-label="实时窗口最大字节" @update:value="(value) => set('maxBytes', value)" /></label>
      </div>
      <p class="qi-reference-notice">状态保留至所属窗口关闭后由Flink清理，不依赖无限全局状态或提前TTL丢弃。窗口内积累状态随checkpoint恢复；提交新作业不会自动继承旧作业状态。超过每窗每组行数或字节预算会明确失败。</p>
      <p class="qi-reference-notice">到达较晚的数据进入到达时所在窗口，不重新打开已关闭窗口。输出采用窗口关闭后的结果；外部目标的重放与幂等仍须通过任务检查。</p>
    </template>
  </details>
</template>
