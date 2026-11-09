<script setup lang="ts">
import { IconifyIcon } from '@vben/icons';

import { Button } from 'antdv-next';
</script>

<template>
  <Button class="detail-back-button shrink-0" size="small" :auto-insert-space="false">
    <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
    返回
  </Button>
</template>
