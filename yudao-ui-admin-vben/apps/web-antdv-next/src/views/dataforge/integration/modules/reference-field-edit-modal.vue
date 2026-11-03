<script setup lang="ts">
import type { ReferenceField } from './reference-node-schema';

import { Alert, Input, InputNumber, Modal, Select } from 'antdv-next';

import { referenceFieldTypes } from './reference-node-schema';

import './reference-node-editor.css';

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    errors?: string[];
    field: ReferenceField;
    keyLabel?: string;
    open: boolean;
    showComment?: boolean;
    showFormat?: boolean;
    showIndex?: boolean;
    showJsonPath?: boolean;
    showKey?: boolean;
    showMetadata?: boolean;
    typeOptions?: { label: string; value: string }[];
  }>(),
  {
    disabled: false,
    errors: () => [],
    keyLabel: 'JSON解析取值',
    showComment: true,
    showFormat: true,
    showIndex: false,
    showJsonPath: false,
    showKey: false,
    showMetadata: true,
    typeOptions: () => referenceFieldTypes,
  },
);
const emit = defineEmits<{
  cancel: [];
  save: [];
  'update:field': [value: ReferenceField];
}>();
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
    :width="520"
    ok-text="保存"
    cancel-text="关闭"
    :ok-button-props="{ disabled }"
    :destroy-on-hidden="true"
    wrap-class-name="qi-reference-field-modal"
    @ok="save"
    @cancel="emit('cancel')"
  >
    <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    <div class="qi-reference-field-edit-grid">
      <label>字段名称</label><Input
        :value="field.name"
        :disabled="disabled"
        aria-label="属性字段编辑名称"
        @update:value="(value) => update('name', value)"
      />
      <label>字段类型</label><Select
        :value="field.type"
        :options="typeOptions"
        :disabled="disabled"
        show-search
        allow-clear
        aria-label="属性字段编辑类型"
        @update:value="(value) => update('type', value)"
      />
      <template v-if="showKey">
<label>{{ keyLabel }}</label><Input
          :value="field.key"
          :disabled="disabled"
          aria-label="属性字段编辑取值"
          @update:value="(value) => update('key', value)"
      />
</template>
      <template v-if="showJsonPath">
<label>JSON路径</label><Input
          :value="field.jsonPath"
          :disabled="disabled"
          aria-label="属性字段编辑JSON路径"
          @update:value="(value) => update('jsonPath', value)"
      />
</template>
      <template v-if="showIndex">
<label>索引</label><InputNumber
          :value="field.index"
          :min="0"
          :precision="0"
          :disabled="disabled"
          aria-label="属性字段编辑索引"
          @update:value="(value) => update('index', value)"
      />
</template>
      <template v-if="showMetadata">
        <label>字段长度</label><Input
          :value="field.length"
          :disabled="disabled"
          inputmode="numeric"
          aria-label="属性字段编辑长度"
          @update:value="(value) => update('length', value)"
        />
        <label>小数精度</label><Input
          :value="field.precision"
          :disabled="disabled"
          inputmode="numeric"
          aria-label="属性字段编辑精度"
          @update:value="(value) => update('precision', value)"
        />
      </template>
      <template v-if="showFormat">
<label>格式</label><Input
          :value="field.format"
          :disabled="disabled"
          aria-label="属性字段编辑格式"
          @update:value="(value) => update('format', value)"
      />
</template>
      <template v-if="showComment">
<label>字段描述</label><Input
          :value="field.comment"
          :disabled="disabled"
          aria-label="属性字段编辑描述"
          @update:value="(value) => update('comment', value)"
      />
</template>
    </div>
  </Modal>
</template>
