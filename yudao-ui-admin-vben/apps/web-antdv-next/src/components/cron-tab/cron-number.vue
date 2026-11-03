<script setup lang="ts">
import { Button, InputNumber } from 'antdv-next';

const props = defineProps<{
  disabled?: boolean;
  label: string;
  max: number;
  min: number;
  modelValue: number | string | undefined;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: number | string | undefined];
}>();

function step(offset: number) {
  const value = Number(props.modelValue ?? props.min);
  emit(
    'update:modelValue',
    Math.min(props.max, Math.max(props.min, value + offset)),
  );
}
</script>

<template>
  <div class="cron-number" :class="{ 'cron-number-disabled': disabled }">
    <Button
      type="text"
      :aria-label="`减小${label}`"
      :disabled="disabled || Number(modelValue) <= min"
      @click="step(-1)"
    >
      −
    </Button>
    <InputNumber
      :value="modelValue"
      :aria-label="label"
      :min="min"
      :max="max"
      :precision="0"
      :controls="false"
      :disabled="disabled"
      variant="borderless"
      @update:value="emit('update:modelValue', $event ?? undefined)"
    />
    <Button
      type="text"
      :aria-label="`增大${label}`"
      :disabled="disabled || Number(modelValue) >= max"
      @click="step(1)"
    >
      ＋
    </Button>
  </div>
</template>

<style scoped>
.cron-number {
  display: inline-flex;
  flex: 0 0 174px;
  width: 174px;
  height: 36px;
  overflow: hidden;
  vertical-align: middle;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.cron-number :deep(.ant-btn) {
  flex: 0 0 36px;
  height: 34px;
  padding: 0;
  font-size: 20px;
  line-height: 1;
  background: hsl(var(--muted) / 55%);
  border-radius: 0;
}

.cron-number :deep(.ant-btn:first-child) {
  border-right: 1px solid hsl(var(--border));
}

.cron-number :deep(.ant-btn:last-child) {
  border-left: 1px solid hsl(var(--border));
}

.cron-number :deep(.ant-input-number) {
  flex: 1;
  width: 100px;
  min-width: 0;
  border-radius: 0;
}

.cron-number :deep(.ant-input-number-input) {
  height: 34px;
  text-align: center;
}

.cron-number-disabled {
  opacity: 0.65;
}

@media (max-width: 640px) {
  .cron-number {
    flex-basis: 132px;
    width: 132px;
  }
}
</style>
