<script setup lang="ts">
import { computed } from 'vue';

import expandArrow from '../assets/expand-arrow.png';

const props = defineProps<{ name: string }>();
const local = new Set([
  'download',
  'failure',
  'idle',
  'pause',
  'running',
  'sort',
  'stopped',
  'success',
]);
const source = computed(() => {
  if (props.name === 'row-expand') return expandArrow;
  if (local.has(props.name)) return `/assets/qdata-ops/${props.name}.png`;
  return `/assets/qdata-integration/icon-${props.name === 'waiting' ? 'clock' : props.name}.png`;
});
</script>

<template>
  <img
    :src="source"
    alt=""
    aria-hidden="true"
    class="qo-icon"
    draggable="false"
  />
</template>
