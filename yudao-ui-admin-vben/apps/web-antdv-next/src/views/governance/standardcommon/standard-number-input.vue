<script setup lang="ts">
import { InputNumber } from 'antdv-next';

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    max: number;
    min: number;
    placeholder?: string;
  }>(),
  { disabled: false, placeholder: '' },
);
const value = defineModel<null | number>();
function step(offset: number) {
  const initial = value.value ?? (offset > 0 ? props.min - 1 : props.min + 1);
  value.value = Math.min(props.max, Math.max(props.min, initial + offset));
}
</script>

<template>
  <div class="standard-number-input">
    <button
      type="button"
      aria-label="减少"
      :disabled="disabled || (value != null && value <= min)"
      @click="step(-1)"
    >
      −
    </button>
    <InputNumber
      v-model:value="value"
      :min="min"
      :max="max"
      :disabled="disabled"
      :placeholder="placeholder"
      :controls="false"
      :precision="0"
    />
    <button
      type="button"
      aria-label="增加"
      :disabled="disabled || (value != null && value >= max)"
      @click="step(1)"
    >
      ＋
    </button>
  </div>
</template>
