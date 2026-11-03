<script setup lang="ts">
defineProps<{ name: string }>();
</script>
<template>
  <img
    :src="`/assets/qdata-integration/icon-${name}.png`"
    alt=""
    aria-hidden="true"
    class="qi-icon"
    draggable="false"
  />
</template>
