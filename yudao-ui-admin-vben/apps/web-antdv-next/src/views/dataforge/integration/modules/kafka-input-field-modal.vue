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
  const result = [...kafkaInputFieldTypes];
  if (
    props.field.type &&
    !result.some((item) => item.value === props.field.type)
  )
    result.unshift({ label: props.field.type, value: props.field.type });
  return result;
});
const fieldIssue = (key: 'key' | 'name' | 'type') =>
  props.errors.find((issue) =>
    issue.includes({ key: 'JSON解析值', name: '名称', type: '类型' }[key]),
  );
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
    :ok-button-props="{ disabled }"
    :destroy-on-hidden="true"
    wrap-class-name="qi-reference-field-modal qi-kafka-input-field-modal"
    @ok="save"
    @cancel="emit('cancel')"
  >
    <Alert
      v-if="
        errors.some(
          (issue) =>
            !['名称', '类型', 'JSON解析值'].some((key) => issue.includes(key)),
        )
      "
      type="error"
      :message="errors.join('；')"
    />
    <Form
      :model="field"
      layout="horizontal"
      :label-col="{ flex: '110px' }"
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
          :disabled="disabled"
          aria-label="属性字段编辑类型"
          placeholder="请选择字段类型"
          @update:value="(value) => update('type', value)"
        />
      </FormItem>
      <FormItem
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
  column-gap: 20px;
  padding: 14px 16px 14px 0;
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
