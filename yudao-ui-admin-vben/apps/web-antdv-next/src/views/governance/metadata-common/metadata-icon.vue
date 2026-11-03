<script setup lang="ts">
import { IconifyIcon } from '@vben/icons';

import { metadataIcon } from './icons';

withDefaults(defineProps<{ name: string; size?: number }>(), { size: 14 });
</script>

<template>
  <IconifyIcon
    :icon="metadataIcon(name)"
    :width="size"
    :height="size"
    class="metadata-icon"
    aria-hidden="true"
  />
</template>

<style scoped>
.metadata-icon {
  display: inline-block;
  flex-shrink: 0;
  vertical-align: -0.15em;
}
</style>
