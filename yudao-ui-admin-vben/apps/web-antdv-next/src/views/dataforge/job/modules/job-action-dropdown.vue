<script setup lang="ts">
import { ref } from 'vue';

import { Dropdown } from 'antdv-next';

const open = ref(false);
</script>

<template>
  <Dropdown v-model:open="open" :trigger="['click']">
    <slot></slot>
    <template #popupRender>
      <div class="qj-popup" @click="open = false">
        <slot name="actions"></slot>
      </div>
    </template>
  </Dropdown>
</template>
