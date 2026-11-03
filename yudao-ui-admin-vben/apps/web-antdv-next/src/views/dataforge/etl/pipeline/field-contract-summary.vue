<script setup lang="ts">
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed } from 'vue';

const props = defineProps<{
  field: DataforgeEtlApi.FieldMeta & { type?: string };
}>();
const nullableLabel = computed(() => {
  if (props.field.nullable === true) return '允许空值';
  if (props.field.nullable === false) return '禁止空值';
  return '未知';
});
</script>

<template>
  <span class="field-contract-summary text-sm text-muted-foreground">
    类型 {{ field.dataType || field.type || '未知' }} · 可空性
    {{ nullableLabel }} · 精度 {{ field.precision ?? '未知' }} · scale
    {{ field.scale ?? '未知' }} · 时区 {{ field.timezone || '未知' }}
  </span>
</template>
