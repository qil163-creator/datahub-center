<script lang="ts" setup>
import { InputNumber } from 'antdv-next';

/** 掩码范围：起始 至 截止（下标从1开始，截止必须大于起始） */
defineOptions({ name: 'SafetyRuleMaskRange' });

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    modelValue?: { end?: number; start?: number };
  }>(),
  {
    disabled: false,
    modelValue: () => ({}),
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: { end?: number; start?: number }];
}>();

function handleChange(key: 'end' | 'start', value: null | number | string) {
  emit('update:modelValue', {
    ...props.modelValue,
    [key]: typeof value === 'number' ? value : undefined,
  });
}
</script>

<template>
  <div class="flex items-center gap-2">
    <InputNumber
      class="flex-1"
      :disabled="disabled"
      :min="0"
      placeholder="起始"
      :precision="0"
      :value="modelValue?.start"
      @update:value="(val: any) => handleChange('start', val)"
    />
    <span>至</span>
    <InputNumber
      class="flex-1"
      :disabled="disabled"
      :min="1"
      placeholder="截止"
      :precision="0"
      :value="modelValue?.end"
      @update:value="(val: any) => handleChange('end', val)"
    />
  </div>
</template>
