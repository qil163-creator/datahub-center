<script setup lang="ts">
defineProps<{
  name:
    | 'back'
    | 'check'
    | 'down'
    | 'edit'
    | 'eye'
    | 'field'
    | 'grid'
    | 'play'
    | 'plus'
    | 'refresh'
    | 'reset'
    | 'search';
}>();
</script>

<template>
  <span class="bf-icon" :class="`bf-icon-${name}`" aria-hidden="true"></span>
</template>
