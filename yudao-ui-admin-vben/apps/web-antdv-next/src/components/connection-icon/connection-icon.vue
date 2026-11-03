<script lang="ts" setup>
import { computed, ref, watch } from 'vue';

import { resolveConnectionIconCandidates } from './path';

defineOptions({ inheritAttrs: false });
const props = withDefaults(
  defineProps<{ alt?: string; iconPath?: string; typeCode?: string }>(),
  {
    alt: '连接图标',
    iconPath: '',
    typeCode: '',
  },
);
const candidates = computed(() =>
  resolveConnectionIconCandidates(props.iconPath, props.typeCode),
);
const sourceIndex = ref(0);
const requestVersion = ref(0);
const source = computed(() => candidates.value[sourceIndex.value]);
const imageElement = ref<HTMLImageElement>();

watch(
  candidates,
  () => {
    sourceIndex.value = 0;
    requestVersion.value += 1;
  },
  { flush: 'sync' },
);

const handleError = computed(() => {
  const requestedCandidates = candidates.value;
  const requestedSource = source.value;
  return (event: Event) => {
    if (
      requestedCandidates !== candidates.value ||
      requestedSource !== source.value ||
      event.currentTarget !== imageElement.value
    )
      return;
    sourceIndex.value += 1;
  };
});
</script>

<template>
  <img
    v-if="sourceIndex === 0 && source"
    v-bind="$attrs"
    :key="`${requestVersion}:${source}`"
    ref="imageElement"
    :src="source"
    :alt="alt"
    class="object-contain"
    @error="handleError"
  />
  <slot v-else name="fallback">
    <img
      v-if="source"
      v-bind="$attrs"
      :key="`${requestVersion}:${source}`"
      ref="imageElement"
      :src="source"
      :alt="alt"
      class="object-contain"
      @error="handleError"
    />
    <svg
      v-else
      v-bind="$attrs"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      role="img"
      :aria-label="alt"
    >
      <ellipse cx="12" cy="5" rx="8" ry="3" />
      <path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0" />
    </svg>
  </slot>
</template>
