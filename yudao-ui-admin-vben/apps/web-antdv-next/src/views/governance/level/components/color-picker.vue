<script lang="ts" setup>
import { computed } from 'vue';

/** 安全等级颜色选择器：预设色板 + 自定义取色（antdv-next 暂未提供 ColorPicker，封装原生 input[type=color]） */
defineOptions({ name: 'SafetyLevelColorPicker' });

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    modelValue?: string;
  }>(),
  {
    disabled: false,
    modelValue: '',
  },
);

const emit = defineEmits<{
  change: [value: string];
  'update:modelValue': [value: string];
}>();

/** 预设色板：绿 / 蓝 / 金 / 红 / 青 / 紫 / 品红 / 灰 */
const PRESET_COLORS = [
  '#52c41a',
  '#1677ff',
  '#faad14',
  '#f5222d',
  '#13c2c2',
  '#722ed1',
  '#eb2f96',
  '#8c8c8c',
];

/** 用于 input[type=color] 的展示值，必须是合法 hex；非法时回退到 #000000 但不修改 modelValue */
const swatchValue = computed(() =>
  /^#[0-9a-f]{6}$/i.test(props.modelValue ?? '')
    ? (props.modelValue as string)
    : '#000000',
);

function selectColor(value: string) {
  if (props.disabled) {
    return;
  }
  emit('update:modelValue', value);
  emit('change', value);
}

function handleColorChange(event: Event) {
  const value = (event.target as HTMLInputElement).value;
  emit('update:modelValue', value);
  emit('change', value);
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2 p-1">
    <button
      v-for="preset in PRESET_COLORS"
      :key="preset"
      class="safety-color-picker__preset"
      :class="{
        'safety-color-picker__preset--active':
          preset.toLowerCase() === (modelValue || '').toLowerCase(),
      }"
      :disabled="disabled"
      :style="{ backgroundColor: preset }"
      type="button"
      @click="selectColor(preset)"
    ></button>
    <input
      class="safety-color-picker__swatch"
      :disabled="disabled"
      type="color"
      :value="swatchValue"
      @change="handleColorChange"
    />
    <span v-if="modelValue" class="text-xs">{{ modelValue }}</span>
  </div>
</template>

<style scoped>
.safety-color-picker__preset {
  inline-size: 20px;
  block-size: 20px;
  cursor: pointer;
  border: 2px solid transparent;
  border-radius: 50%;
}

.safety-color-picker__preset--active {
  box-shadow: 0 0 0 2px var(--ant-color-primary, #1677ff);
}

.safety-color-picker__preset:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.safety-color-picker__swatch {
  inline-size: 36px;
  block-size: 24px;
  padding: 2px;
  cursor: pointer;
  border: 1px solid var(--ant-color-border, #d9d9d9);
  border-radius: 4px;
}

.safety-color-picker__swatch:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
