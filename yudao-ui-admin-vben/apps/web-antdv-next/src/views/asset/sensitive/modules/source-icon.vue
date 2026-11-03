<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  name:
    | 'add'
    | 'delete'
    | 'edit'
    | 'grid'
    | 'query'
    | 'refresh'
    | 'reset'
    | 'search'
    | 'view';
}>();
const asset = computed(() => {
  if (['query', 'refresh', 'reset', 'search'].includes(props.name))
    return `/assets/qdata-project-category/${props.name}.png`;
  return `/assets/qdata-market/sensitive-${props.name}.svg`;
});
</script>

<template>
  <span
    class="sensitive-source-icon"
    :style="{ maskImage: `url(${asset})`, WebkitMaskImage: `url(${asset})` }"
    aria-hidden="true"
  ></span>
</template>

<style scoped>
.sensitive-source-icon {
  display: inline-block;
  flex: none;
  width: 12px;
  height: 12px;
  vertical-align: -1px;
  color: inherit;
  background: currentcolor;
  mask-repeat: no-repeat;
  mask-position: center;
  mask-size: contain;
}
</style>
