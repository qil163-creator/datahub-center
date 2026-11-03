<script lang="ts" setup>
import { ConnectionIcon } from '#/components/connection-icon';

defineOptions({ name: 'DatabaseTypeIconCell' });

withDefaults(
  defineProps<{
    iconPath?: string;
    typeCode?: string;
    typeName?: string;
  }>(),
  {
    iconPath: '',
    typeCode: '',
    typeName: '',
  },
);
</script>

<template>
  <div class="type-icon-cell" :title="typeName">
    <ConnectionIcon
      :icon-path="iconPath"
      :type-code="typeCode"
      :alt="typeName"
      class="size-10 shrink-0"
    />
  </div>
</template>

<style scoped>
.type-icon-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
}
</style>
