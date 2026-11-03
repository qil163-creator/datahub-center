<script lang="ts" setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  onUpdated,
  ref,
} from 'vue';

import { Tooltip } from 'antdv-next';

const props = withDefaults(
  defineProps<{
    center?: boolean;
    content?: string;
    emptyText?: string;
    line?: number;
    lineHeight?: number;
    reserveLines?: boolean;
  }>(),
  {
    center: false,
    content: undefined,
    emptyText: '-',
    line: 2,
    lineHeight: 24,
    reserveLines: true,
  },
);

const textRef = ref<HTMLElement>();
const isEllipsis = ref(false);

const displayText = computed(() => props.content?.trim() || props.emptyText);
const hasContent = computed(() => displayText.value !== props.emptyText);

const textStyle = computed(() => {
  if (!hasContent.value) {
    return undefined;
  }
  return {
    display: '-webkit-box',
    WebkitBoxOrient: 'vertical',
    WebkitLineClamp: props.line,
    overflow: 'hidden',
    textAlign: 'left',
    width: '100%',
    wordBreak: 'break-word',
    lineHeight: `${props.lineHeight}px`,
    ...(!props.center && props.reserveLines
      ? { minHeight: `${props.line * props.lineHeight}px` }
      : {}),
  } as const;
});

const wrapperStyle = computed(() =>
  props.center && props.reserveLines
    ? { minHeight: `${props.line * props.lineHeight}px` }
    : undefined,
);

function checkEllipsis() {
  const el = textRef.value;
  if (!el || !hasContent.value) {
    isEllipsis.value = false;
    return;
  }
  isEllipsis.value = el.scrollHeight - el.clientHeight > 1;
}

let resizeObserver: ResizeObserver | undefined;

function bindResizeObserver() {
  resizeObserver?.disconnect();
  if (typeof ResizeObserver === 'undefined' || !textRef.value) {
    return;
  }
  resizeObserver = new ResizeObserver(() => checkEllipsis());
  resizeObserver.observe(textRef.value);
}

onMounted(() => {
  nextTick(() => {
    checkEllipsis();
    bindResizeObserver();
  });
});

onUpdated(() => {
  nextTick(() => {
    checkEllipsis();
    bindResizeObserver();
  });
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
});
</script>

<template>
  <div
    class="w-full"
    :class="{ 'flex items-center': center }"
    :style="wrapperStyle"
  >
    <Tooltip
      placement="top"
      :overlay-inner-style="{ maxWidth: '480px' }"
      :open="hasContent && isEllipsis ? undefined : false"
    >
      <template v-if="hasContent && isEllipsis" #title>
        <div class="whitespace-pre-wrap break-words text-left leading-6">
          {{ displayText }}
        </div>
      </template>
      <div
        ref="textRef"
        class="block w-full break-words text-left leading-6"
        :class="{ 'cursor-default': hasContent && isEllipsis }"
        :style="textStyle"
      >
        {{ displayText }}
      </div>
    </Tooltip>
  </div>
</template>

<style scoped>
.w-full :deep(> *) {
  display: block;
  width: 100%;
}
</style>
