<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue';

import { Tooltip } from 'antdv-next';

/** 单行标签截断：放不下的标签折叠为 “+N”，悬停展示全部（对应 qData TagClamp）。 */
const props = defineProps<{ tags: string[] }>();

const GAP = 5;
const container = ref<HTMLDivElement>();
const visibleCount = ref(props.tags.length);
const hiddenCount = ref(0);
const lastMaxWidth = ref('100%');
const visibleTags = computed(() => props.tags.slice(0, visibleCount.value));
let observer: ResizeObserver | undefined;

function measure() {
  const el = container.value;
  if (!el || el.clientWidth === 0) return;
  const tags = [...el.querySelectorAll<HTMLElement>('.qd-tag-measure')];
  const more = el.querySelector<HTMLElement>('.qd-more-measure');
  if (tags.length === 0) {
    visibleCount.value = 0;
    hiddenCount.value = 0;
    return;
  }
  const width = el.clientWidth;
  const moreWidth = more?.offsetWidth ?? 35;
  let shown = 0;
  let hidden = 0;
  let maxWidth = '100%';
  let used = 0;
  for (const [index, tag] of tags.entries()) {
    const last = index === tags.length - 1;
    const reserve = (index > 0 ? GAP : 0) + (last ? 0 : GAP + moreWidth);
    const space = width - used - reserve;
    if (space < 20) {
      if (index === 0) {
        shown = 1;
        maxWidth = `${Math.max(width - (last ? 0 : moreWidth + GAP), 10)}px`;
        hidden = tags.length - 1;
      } else {
        shown = index;
        hidden = tags.length - index;
      }
      break;
    }
    if (tag.offsetWidth <= space) {
      used += (index > 0 ? GAP : 0) + tag.offsetWidth;
      shown = index + 1;
      hidden = tags.length - shown;
    } else {
      shown = index + 1;
      maxWidth = `${space}px`;
      hidden = tags.length - shown;
      break;
    }
  }
  visibleCount.value = shown;
  hiddenCount.value = hidden;
  lastMaxWidth.value = maxWidth;
}

watch(
  () => props.tags,
  () => nextTick(measure),
  { deep: true },
);
onMounted(() => {
  void nextTick(measure);
  if (container.value) {
    observer = new ResizeObserver(() => measure());
    observer.observe(container.value);
  }
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <div ref="container" class="qd-tag-clamp">
    <Tooltip v-if="hiddenCount > 0" placement="top" color="#fff">
      <template #title>
        <div class="qd-tag-clamp__tooltip">
          <span
            v-for="(tag, index) in tags"
            :key="`full-${index}`"
            class="qd-tag qd-tag--primary qd-tag--small"
            >{{ tag }}</span>
        </div>
      </template>
      <div class="qd-tag-clamp__wrapper">
        <span
          v-for="(tag, index) in visibleTags"
          :key="`vis-${index}`"
          class="qd-tag qd-tag--primary qd-tag--small"
          :style="{
            maxWidth: index === visibleTags.length - 1 ? lastMaxWidth : '100%',
          }"
          ><span class="qd-tag__content">{{ tag }}</span></span>
        <span class="qd-tag qd-tag--primary qd-tag--small">
          +{{ hiddenCount }}
        </span>
      </div>
    </Tooltip>
    <div v-else class="qd-tag-clamp__wrapper">
      <span
        v-for="(tag, index) in visibleTags"
        :key="`all-${index}`"
        class="qd-tag qd-tag--primary qd-tag--small"
        :style="{
          maxWidth: index === visibleTags.length - 1 ? lastMaxWidth : '100%',
        }"
        ><span class="qd-tag__content">{{ tag }}</span></span>
    </div>
    <div class="qd-tag-clamp__measure" aria-hidden="true">
      <span
        v-for="(tag, index) in tags"
        :key="`measure-${index}`"
        class="qd-tag qd-tag--primary qd-tag--small qd-tag-measure"
        >{{ tag }}</span>
      <span class="qd-tag qd-tag--primary qd-tag--small qd-more-measure">
        +{{ tags.length }}
      </span>
    </div>
  </div>
</template>
