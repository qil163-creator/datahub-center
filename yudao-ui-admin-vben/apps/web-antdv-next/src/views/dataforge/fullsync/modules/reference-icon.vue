<script setup lang="ts">
defineProps<{ name: string }>();
</script>
<template>
  <img
    class="fs-reference-icon"
    :src="`/assets/qdata-fullsync/${name}.png`"
    alt=""
    aria-hidden="true"
    draggable="false"
  />
</template>
<style scoped>
.fs-reference-icon {
  display: inline-block;
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
  vertical-align: -3px;
  object-fit: contain;
}
</style>
