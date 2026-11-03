<script lang="ts" setup>
import type { ModelNavFilter } from './layer-navigation';

import LayerTree from './layer-tree.vue';

defineOptions({ name: 'ModelingLayerNavTree' });
const emit = defineEmits<{ select: [] }>();
const selectedId = defineModel<number | string | undefined>('selected');
const navFilter = defineModel<ModelNavFilter>('filter', {
  default: () => ({}),
});
</script>

<template>
  <LayerTree
    v-model:selected="selectedId"
    v-model:filter="navFilter"
    @select="emit('select')"
  />
</template>
