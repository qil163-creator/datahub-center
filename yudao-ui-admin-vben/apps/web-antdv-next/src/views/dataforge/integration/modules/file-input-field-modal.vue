<script setup lang="ts">
import type { ReferenceField } from './reference-node-schema';

import { computed } from 'vue';

import { Alert, Form, FormItem, Input, Modal, Select } from 'antdv-next';

import {
  fileInputDateFormats,
  fileInputFieldTypes,
  isFileInputDate,
} from './file-input-fields';

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    errors?: string[];
    field: ReferenceField;
    open: boolean;
  }>(),
  { disabled: false, errors: () => [] },
);
const emit = defineEmits<{
  cancel: [];
  save: [];
  'update:field': [value: ReferenceField];
}>();
const types = computed(() => {
  const result = [...fileInputFieldTypes];
  if (
    props.field.type &&
    !result.some((item) => item.value === props.field.type)
  )
    result.unshift({ label: props.field.type, value: props.field.type });
  return result;
});
const formats = computed(() => {
  const result = [...fileInputDateFormats];
  if (
    props.field.format &&
    !result.some((item) => item.value === props.field.format)
  )
    result.unshift({ label: props.field.format, value: props.field.format });
  return result;
});
const fieldIssue = (key: 'format' | 'name' | 'type') =>
  props.errors.find((issue) =>
    issue.includes({ format: '日期格式', name: '名称', type: '类型' }[key]),
  );
function update(key: keyof ReferenceField, value: unknown) {
  if (props.open && !props.disabled)
    emit('update:field', { ...props.field, [key]: value });
}
function save() {
  if (props.open && !props.disabled) emit('save');
}
</script>

<template>
  <Modal
    :open="open"
    title="属性字段编辑"
    :width="500"
    ok-text="保存"
    cancel-text="关闭"
    :ok-button-props="{ disabled }"
    :destroy-on-hidden="true"
    wrap-class-name="qi-reference-field-modal qi-file-input-field-modal"
    @ok="save"
    @cancel="emit('cancel')"
  >
    <Alert
      v-if="
        errors.some(
          (issue) =>
            !['名称', '类型', '日期格式'].some((key) => issue.includes(key)),
        )
      "
      type="error"
      :message="errors.join('；')"
    />
    <Form
      :model="field"
      layout="horizontal"
      :label-col="{ flex: '90px' }"
      :wrapper-col="{ flex: 1 }"
      class="qi-file-input-field-form"
    >
      <FormItem
        label="字段名称"
        required
        :validate-status="fieldIssue('name') ? 'error' : undefined"
        :help="fieldIssue('name')"
      >
        <Input
          :value="field.name"
          :disabled="disabled"
          aria-label="属性字段编辑名称"
          placeholder="请输入字段名称"
          @update:value="(value) => update('name', value)"
        />
      </FormItem>
      <FormItem
        label="字段类型"
        required
        :validate-status="fieldIssue('type') ? 'error' : undefined"
        :help="fieldIssue('type')"
      >
        <Select
          :value="field.type"
          :options="types"
          :disabled="disabled"
          aria-label="属性字段编辑类型"
          placeholder="请选择字段类型"
          @update:value="(value) => update('type', value)"
        />
      </FormItem>
      <FormItem
        v-if="isFileInputDate(field)"
        label="日期格式"
        required
        :validate-status="fieldIssue('format') ? 'error' : undefined"
        :help="fieldIssue('format')"
      >
        <Select
          :value="field.format"
          :options="formats"
          :disabled="disabled"
          aria-label="属性字段编辑格式"
          placeholder="请选择日期格式"
          @update:value="(value) => update('format', value)"
        />
      </FormItem>
    </Form>
  </Modal>
</template>

<style>
.qi-file-input-field-modal .qi-file-input-field-form {
  padding: 14px 16px 28px 0;
}

.qi-file-input-field-modal .ant-select {
  width: 100%;
}
</style>
