<script setup lang="ts">
import type { ConnectorField } from './connector-schema';

import { Input, InputNumber, RadioGroup, Select, TextArea } from 'antdv-next';

import { connectorJsonFeedback } from './connector-schema';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  fields: ConnectorField[];
  issues?: { field: string; message: string }[];
  tableOptions?: { label: string; value: string }[];
  tablesLoading?: boolean;
}>();
const emit = defineEmits<{ change: [key: string, value: unknown] }>();
const error = (key: string) =>
  props.issues?.find((issue) => issue.field === key)?.message;
function text(field: ConnectorField) {
  const value: unknown = props.config[field.key];
  if (value && typeof value === 'object') return JSON.stringify(value, null, 2);
  return typeof value === 'string' ? value : '';
}
function feedback(field: ConnectorField) {
  return connectorJsonFeedback(
    props.config[field.key],
    field.label,
    field.key === 'headerSecretRefs',
  );
}
const count = ({ count, maxLength }: { count: number; maxLength?: number }) =>
  `${count} / ${maxLength ?? 256}个字符`;
function change(field: ConnectorField, value: unknown) {
  if (!props.disabled) emit('change', field.key, value);
}
</script>

<template>
  <div class="qi-connector-reference-grid">
    <div
      v-for="field in fields"
      :key="field.key"
      class="qi-connector-reference-field"
      :class="{
        'is-full':
          field.full || field.kind === 'json' || field.kind === 'textarea',
        'is-half-row': field.halfRow,
      }"
    >
      <label><em v-if="field.required">*</em>{{ field.label }}</label>
      <div>
        <Select
          v-if="field.key === 'tableName' && tableOptions"
          :value="config[field.key]"
          :options="tableOptions"
          :loading="tablesLoading"
          :disabled="disabled || !config.databaseId"
          :status="error(field.key) ? 'error' : undefined"
          :aria-label="field.label"
          show-search
          option-filter-prop="label"
          allow-clear
          :placeholder="field.placeholder ?? '请选择表'"
          @update:value="(value) => change(field, value)"
        />
        <RadioGroup
          v-else-if="['ifSql', 'readMode', 'writeModeType', 'syncMode', 'syncDelete'].includes(field.key)"
          :value="config[field.key]"
          :options="field.options"
          :disabled="disabled"
          :aria-label="field.label"
          @update:value="(value) => change(field, value)"
        />
        <Select
          v-else-if="field.kind === 'select'"
          :value="config[field.key]"
          :options="field.options"
          :suffix-icon="field.plain ? null : undefined"
          :disabled="disabled"
          :allow-clear="field.clearable"
          :class="{ 'qi-flip-arrow': field.key === 'dataType' }"
          :classes="
            field.key === 'dataType'
              ? { popup: { root: 'qi-file-field-popup' } }
              : undefined
          "
          :placeholder="`请选择${field.label}`"
          option-filter-prop="label"
          :status="error(field.key) ? 'error' : undefined"
          :aria-label="field.label"
          @update:value="(value) => change(field, value)"
        />
        <div v-else-if="field.kind === 'number'" class="qi-connector-number">
          <InputNumber
            :value="
              typeof config[field.key] === 'number'
                ? config[field.key]
                : undefined
            "
            :min="field.min ?? 0"
            :max="field.max"
            :precision="0"
            :disabled="disabled"
            :status="error(field.key) ? 'error' : undefined"
            :aria-label="field.label"
            :placeholder="field.placeholder ?? `请输入${field.label}`"
            @update:value="(value) => change(field, value)"
          /><span v-if="field.suffix" class="qi-connector-addon">{{ field.suffix }}</span>
        </div>
        <TextArea
          v-else-if="field.kind === 'json' || field.kind === 'textarea'"
          :value="text(field)"
          :rows="field.kind === 'json' ? 4 : 3"
          :maxlength="field.maxLength"
          :show-count="field.maxLength ? { formatter: count } : false"
          :disabled="disabled"
          :status="
            error(field.key) ||
            (field.kind === 'json' &&
              !(field.quiet && !text(field)) &&
              !feedback(field).valid)
              ? 'error'
              : undefined
          "
          :aria-label="field.label"
          :placeholder="
            field.placeholder ??
            (field.kind === 'json' ? '例如 {}' : `请输入${field.label}`)
          "
          @update:value="(value) => change(field, value)"
        />
        <Input
          v-else
          :value="text(field)"
          :disabled="disabled"
          :status="error(field.key) ? 'error' : undefined"
          :aria-label="field.label"
          :placeholder="`请输入${field.label}`"
          @update:value="(value) => change(field, value)"
        />
        <small v-if="error(field.key)" role="alert">{{
          error(field.key)
        }}</small>
        <small
          v-else-if="
            field.kind === 'json' &&
            !(field.quiet && (!text(field) || feedback(field).valid))
          "
          :class="{ 'is-valid': feedback(field).valid }"
          role="status"
          >{{ feedback(field).message }}</small>
        <p v-if="field.hint" class="qi-connector-field-hint">{{ field.hint }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.qi-connector-reference-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 24px;
}

.qi-connector-reference-field {
  display: grid;
  grid-template-columns: var(--qi-connector-label-width, 110px) minmax(0, 1fr);
  align-items: start;
}

.is-full {
  grid-column: 1 / -1;
}

.is-half-row {
  grid-column: 1 / -1;
  width: calc((100% - 24px) / 2);
}

label {
  padding: 5px 12px 0 0;
  color: #606266;
  text-align: right;
}

em {
  margin-right: 4px;
  font-style: normal;
  color: #f56c6c;
}

.is-valid {
  color: #909399;
}

.qi-connector-number {
  display: flex;
  align-items: center;
}

.qi-connector-number :deep(.ant-input-number) {
  flex: 1;
}

.qi-connector-addon {
  padding: 0 16px;
  line-height: 30px;
  color: #909399;
  text-align: center;
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
  border-left: 0;
  border-radius: 0 2px 2px 0;
}

.qi-connector-number:has(.qi-connector-addon) :deep(.ant-input-number) {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
}

.qi-connector-field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: #909399;
}

small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
}

:deep(.ant-select),
:deep(.ant-input-number) {
  width: 100%;
}

@media (max-width: 768px) {
  .qi-connector-reference-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
