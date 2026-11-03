<script setup lang="ts">
defineProps<{ name: string }>();
</script>

<template>
  <span
    :style="{ maskImage: `url('/assets/qdata-project-category/${name}.png')` }"
    aria-hidden="true"
    class="qpc-icon"
  />
</template>
