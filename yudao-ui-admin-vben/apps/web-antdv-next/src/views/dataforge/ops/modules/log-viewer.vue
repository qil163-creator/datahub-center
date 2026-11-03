<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';

import { highlightLog, logRows } from '../model';

const props = defineProps<{
  followTail?: boolean;
  keyword: string;
  message: string;
  modal?: boolean;
}>();
const rows = computed(() => logRows(props.message, props.keyword));
const viewport = ref<HTMLDivElement>();
let atEnd = true;
function trackScroll() {
  const element = viewport.value;
  if (element)
    atEnd =
      element.scrollHeight - element.scrollTop - element.clientHeight < 40;
}
watch(
  () => props.message,
  async (_value, previous) => {
    if (!previous || !props.followTail || !atEnd) return;
    await nextTick();
    if (viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight;
  },
);
</script>

<template>
  <div
    ref="viewport"
    class="qo-log-viewer"
    :class="{ 'qo-modal-log': modal }"
    role="log"
    tabindex="0"
    aria-label="任务日志"
    @scroll="trackScroll"
  >
    <div v-if="rows.length" class="qo-log-lines">
      <div v-for="row in rows" :key="row.line" class="qo-log-line">
        <span class="qo-line-number">{{ row.line }}</span>
        <pre
          :class="{
            error: /\bERROR\b/u.test(row.text),
            warning: /\bWARN\b/u.test(row.text),
          }"
        ><span v-for="(part, index) in highlightLog(row.text, keyword)" :key="index" :class="{ 'qo-log-highlight': part.highlight, 'qo-log-level': part.level }">{{ part.text }}</span></pre>
      </div>
    </div>
    <div v-else class="qo-log-empty">
      {{ keyword ? '暂无匹配日志' : modal ? '暂无日志记录' : '暂无数据' }}
    </div>
  </div>
</template>
