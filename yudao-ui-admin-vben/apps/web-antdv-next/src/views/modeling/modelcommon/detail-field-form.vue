<script lang="ts" setup>
import { Input } from 'antdv-next';

export interface DetailFieldItem {
  key: string;
  label: string;
  value?: null | number | string;
  span?: 1 | 2;
  type?: 'text' | 'textarea';
  rows?: number;
}

withDefaults(
  defineProps<{
    fields: DetailFieldItem[];
    labelWidth?: string;
  }>(),
  {
    labelWidth: '130px',
  },
);

function displayValue(value?: null | number | string) {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  return String(value);
}
</script>

<template>
  <div
    class="detail-field-form"
    :style="{ '--detail-label-width': labelWidth }"
  >
    <div
      v-for="field in fields"
      :key="field.key"
      class="detail-field"
      :class="{
        'detail-field--full': field.span === 2,
        'detail-field--area': field.type === 'textarea',
      }"
    >
      <div class="detail-field__label">{{ field.label }}</div>
      <div class="detail-field__control">
        <slot :name="field.key" :field="field">
          <textarea
            v-if="field.type === 'textarea'"
            :rows="field.rows ?? 3"
            :value="displayValue(field.value)"
            class="detail-field__textarea"
            readonly
          ></textarea>
          <Input
            v-else
            :value="displayValue(field.value)"
            class="detail-field__input"
            readonly
          />
        </slot>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-field-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.detail-field {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.detail-field--full {
  grid-column: 1 / -1;
}

.detail-field--area {
  align-items: flex-start;
}

.detail-field__label {
  flex: 0 0 var(--detail-label-width, 96px);
  width: var(--detail-label-width, 96px);
  min-height: 32px;
  font-size: 14px;
  line-height: 32px;
  color: hsl(var(--foreground));
  text-align: right;
}

.detail-field--area .detail-field__label {
  padding-top: 5px;
  line-height: 22px;
}

.detail-field__control {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
}

.detail-field__input,
.detail-field-form :deep(.detail-field__input),
.detail-field-form :deep(.ant-input) {
  display: block;
  width: 100%;
  max-width: 100%;
  color: hsl(var(--foreground));
  cursor: default;
  background: hsl(var(--muted) / 35%);
  box-shadow: none;
}

.detail-field__textarea {
  box-sizing: border-box;
  display: block;
  width: 100%;
  min-height: 76px;
  padding: 5px 11px;
  font-size: 14px;
  line-height: 22px;
  color: hsl(var(--foreground));
  cursor: default;
  resize: none;
  outline: none;
  background: hsl(var(--muted) / 35%);
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.detail-field-form :deep(.ant-input:hover),
.detail-field-form :deep(.ant-input:focus),
.detail-field__textarea:hover,
.detail-field__textarea:focus {
  border-color: hsl(var(--border));
  box-shadow: none;
}

@media (max-width: 768px) {
  .detail-field-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
