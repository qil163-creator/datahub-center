<script setup lang="ts">
import DevelopmentTasks from '../index.vue';

defineOptions({ name: 'DataforgeRealtimeTasks' });
</script>

<template>
  <DevelopmentTasks workload-mode="STREAMING" />
</template>
