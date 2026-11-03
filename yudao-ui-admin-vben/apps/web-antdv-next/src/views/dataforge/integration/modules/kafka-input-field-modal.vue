<script setup lang="ts">
import type { ReferenceField } from './reference-node-schema';

import { computed } from 'vue';

import {
  Alert,
  Form,
  FormItem,
  Input,
  Modal,
  Select,
  TextArea,
} from 'antdv-next';

import { kafkaInputFieldTypes } from './kafka-input-fields';

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    errors?: string[];
    field: ReferenceField;
    /** "json" edits the JSON parse path (Kafka); "index" edits the column index (FTP/HDFS). */
    keyMode?: 'index' | 'json';
    open: boolean;
    typeOptions?: { disabled?: boolean; label: string; value: string }[];
  }>(),
  {
    disabled: false,
    errors: () => [],
    keyMode: 'json',
    typeOptions: () => kafkaInputFieldTypes,
  },
);
const emit = defineEmits<{
  cancel: [];
  save: [];
  'update:field': [value: ReferenceField];
}>();
const types = computed(() => {
  const result = [...props.typeOptions];
  if (
    props.field.type &&
    !result.some((item) => item.value === props.field.type)
  )
    result.unshift({ label: props.field.type, value: props.field.type });
  return result;
});
const keyLabel = computed(() =>
  props.keyMode === 'index' ? '索引' : 'JSON解析值',
);
const fieldIssue = (key: 'key' | 'name' | 'type') => {
  const issue = props.errors.find((item) =>
    item.includes({ key: keyLabel.value, name: '名称', type: '类型' }[key]),
  );
  if (!issue) return undefined;
  // The shared "name and type" message only flags the control that is actually empty.
  if (issue.includes('名称与类型')) {
    if (key === 'name') return props.field.name?.trim() ? undefined : '请输入字段名称';
    if (key === 'type') return props.field.type ? undefined : '请选择字段类型';
  }
  if (
    key === 'key' &&
    props.keyMode === 'index' &&
    (props.field.index === undefined || props.field.index === null)
  )
    return '请输入索引';
  return key === 'name' && !props.field.name?.trim() ? '请输入字段名称' : issue;
};
// Dropdown class for the reference option style (blue bold selection on a light background).
const popupClasses = { popup: { root: 'qi-file-field-popup' } };
function updateIndex(value: number | string | undefined) {
  const text = String(value ?? '').trim();
  update('index', /^\d+$/u.test(text) ? Number(text) : text || undefined);
}
const count = ({ count, maxLength }: { count: number; maxLength?: number }) =>
  `${count} / ${maxLength ?? 256}个字符`;
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
    :width="912"
    ok-text="保存"
    cancel-text="关闭"
    :ok-button-props="{ disabled, autoInsertSpace: false }"
    :cancel-button-props="{ autoInsertSpace: false }"
    :destroy-on-hidden="true"
    class="qi-node-modal qi-node-modal-reference"
    wrap-class-name="qi-reference-field-modal qi-kafka-input-field-modal"
    @ok="save"
    @cancel="emit('cancel')"
  >
    <Alert
      v-if="
        errors.some(
          (issue) =>
            !['名称', '类型', keyLabel].some((key) => issue.includes(key)),
        )
      "
      type="error"
      :message="errors.join('；')"
    />
    <Form
      :model="field"
      layout="horizontal"
      :colon="false"
      :label-col="{ flex: '120px' }"
      :wrapper-col="{ flex: 1 }"
      class="qi-kafka-input-field-form"
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
          :classes="popupClasses"
          :disabled="disabled"
          aria-label="属性字段编辑类型"
          placeholder="请选择字段类型"
          @update:value="(value) => update('type', value)"
        />
      </FormItem>
      <FormItem
        v-if="keyMode === 'index'"
        label="索引"
        required
        :validate-status="fieldIssue('key') ? 'error' : undefined"
        :help="fieldIssue('key')"
      >
        <Input
          :value="field.index === undefined ? undefined : String(field.index)"
          :disabled="disabled"
          inputmode="numeric"
          aria-label="属性字段编辑索引"
          placeholder="请输入索引"
          @update:value="updateIndex"
        />
      </FormItem>
      <FormItem
        v-else
        class="qi-kafka-input-key"
        label="JSON解析值"
        required
        :validate-status="fieldIssue('key') ? 'error' : undefined"
        :help="fieldIssue('key')"
      >
        <TextArea
          :value="field.key"
          :disabled="disabled"
          aria-label="属性字段编辑取值"
          placeholder="例如:info.aga"
          :rows="4"
          :maxlength="256"
          :show-count="{ formatter: count }"
          @update:value="(value) => update('key', value)"
        />
      </FormItem>
    </Form>
  </Modal>
</template>

<style>
.qi-kafka-input-field-modal .qi-kafka-input-field-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 22px;
  padding: 0;
}

.qi-kafka-input-field-modal .qi-kafka-input-field-form .ant-form-item {
  margin-bottom: 15px;
}

/* Validation text sits under the control without moving the next row. */
.qi-kafka-input-field-modal .qi-kafka-input-field-form .ant-form-item-control {
  position: relative;
}

.qi-kafka-input-field-modal .qi-kafka-input-field-form .ant-form-item-additional {
  position: absolute;
  top: 100%;
  left: 0;
}

.qi-kafka-input-field-modal .qi-kafka-input-field-form .ant-form-item-margin-offset {
  display: none;
}

.qi-kafka-input-field-modal .qi-kafka-input-field-form .ant-form-item-explain {
  min-height: 0;
  font-size: 12px;
  line-height: 16px;
}

.qi-kafka-input-field-modal .qi-kafka-input-key {
  grid-column: 1 / -1;
}

.qi-kafka-input-field-modal .ant-select {
  width: 100%;
}

@media (max-width: 768px) {
  .qi-kafka-input-field-modal .qi-kafka-input-field-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
