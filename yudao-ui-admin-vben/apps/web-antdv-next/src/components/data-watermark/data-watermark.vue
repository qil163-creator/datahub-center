<script lang="ts" setup>
import DataWatermarkLayer from './data-watermark-layer.vue';

/** 数据区水印包装：在展示业务数据的区域上叠加查看者与时间，便于截图外传后溯源。 */
defineOptions({ name: 'DataWatermark' });
</script>

<template>
  <div class="relative">
    <slot></slot>
    <DataWatermarkLayer />
  </div>
</template>
