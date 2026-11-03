<script lang="ts" setup>
/**
 * 运行日志查看器：等宽字体、可滚动的日志区 + 可选的错误信息区。
 * 接入侧各模块的运行/交付日志弹窗统一使用，保证观感一致。
 */
withDefaults(
  defineProps<{
    content?: string;
    emptyText?: string;
    error?: string;
    errorTitle?: string;
    maxHeight?: string;
  }>(),
  {
    content: undefined,
    emptyText: '暂无日志',
    error: undefined,
    errorTitle: '错误信息',
    maxHeight: '420px',
  },
);
</script>

<template>
  <div class="w-full">
    <pre class="log-viewer" :style="{ maxHeight }">{{
      content || emptyText
    }}</pre>
    <div v-if="error" class="mt-2">
      <div class="text-destructive mb-1 text-xs font-semibold">
        {{ errorTitle }}
      </div>
      <pre class="log-viewer log-viewer--error">{{ error }}</pre>
    </div>
  </div>
</template>

<style scoped>
.log-viewer {
  padding: 12px;
  margin: 0;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: hsl(var(--foreground));
  word-break: break-all;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 50%);
  border-radius: 8px;
}

.log-viewer--error {
  max-height: 160px;
  color: hsl(var(--destructive));
}
</style>
