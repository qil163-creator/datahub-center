<script lang="ts" setup>
import type {
  CleanMappingItem,
  CleanParameterField,
  CleanSortItem,
} from './clean-rule-parameters';

import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  InputNumber,
  RadioGroup,
  Select,
} from 'antdv-next';

import { formatCleanRuleConfig } from './clean-rule-config';
import {
  cleanParameterFields,
  cleanParameterIssues,
  cleanParameterValue,
  replaceCleanParameter,
} from './clean-rule-parameters';

const props = defineProps<{
  disabled?: boolean;
  modelValue?: null | Record<string, unknown>;
  parameterFields?: CleanParameterField[];
  strategyKey?: string;
  supported: boolean;
  validationIssues?: string[];
}>();
const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>];
}>();
const schema = computed(
  () =>
    props.parameterFields ??
    cleanParameterFields(props.strategyKey, props.modelValue ?? {}),
);
const editable = computed(() => props.supported && schema.value !== undefined);
const issues = computed(
  () =>
    props.validationIssues ??
    (editable.value && !props.parameterFields
      ? cleanParameterIssues(props.strategyKey, props.modelValue ?? {})
      : []),
);
const value = (key: string) =>
  cleanParameterValue(props.modelValue, key, props.strategyKey);
const textValue = (key: string) =>
  value(key) === undefined || value(key) === null
    ? undefined
    : String(value(key));
const booleanValue = (key: string) =>
  typeof value(key) === 'boolean' ? (value(key) as boolean) : undefined;
const numberValue = (key: string) => {
  const raw = value(key);
  return typeof raw === 'number' || typeof raw === 'string' ? raw : undefined;
};
const selectValue = (key: string) => {
  const field = schema.value?.find((item) => item.key === key);
  return field?.options?.find(
    (option) => String(option.value) === String(value(key)),
  )?.value;
};
const formats = (key: string) =>
  Array.isArray(value(key)) ? (value(key) as string[]) : [];
const mappings = (key: string) =>
  Array.isArray(value(key))
    ? (value(key) as unknown[]).map((item) =>
        item && typeof item === 'object'
          ? (item as CleanMappingItem)
          : { value: '', name: '' },
      )
    : [];
const sorts = (key: string) =>
  Array.isArray(value(key))
    ? (value(key) as unknown[]).map((item) =>
        item && typeof item === 'object'
          ? (item as CleanSortItem)
          : { columns: '', type: 0, sort: 0 },
      )
    : [];
function update(key: string, next: unknown) {
  const field = schema.value?.find((item) => item.key === key);
  if (next === '' && field && !field.required && !field.allowEmpty)
    next = undefined;
  if (editable.value && !props.disabled)
    emit(
      'update:modelValue',
      replaceCleanParameter(props.modelValue, key, next),
    );
}
function updateItem(
  key: string,
  index: number,
  itemKey: string,
  next: unknown,
) {
  const items = Array.isArray(value(key))
    ? [...(value(key) as Record<string, unknown>[])]
    : [];
  items[index] = { ...items[index], [itemKey]: next };
  update(key, items);
}
function removeItem(key: string, index: number) {
  const items = Array.isArray(value(key)) ? [...(value(key) as unknown[])] : [];
  items.splice(index, 1);
  update(key, items);
}
</script>

<template>
  <div class="mt-3 space-y-3" data-testid="clean-parameter-editor">
    <Alert
      v-if="!editable"
      message="当前策略没有可编辑的参数表单。历史策略与已保存参数保留显示，请选择引擎支持的策略后配置。"
      show-icon
      type="warning"
    />
    <template v-else>
      <Alert
        v-if="issues.length"
        :message="issues.join('；')"
        show-icon
        type="warning"
      />
      <div
        v-if="schema?.length === 0 && !parameterFields"
        class="text-muted-foreground text-xs"
      >
        此策略不需要函数参数，在 ETL 节点选择目标字段即可。
      </div>
      <div v-for="field in schema" :key="field.key" class="space-y-1">
        <label class="block text-sm" :for="`clean-parameter-${field.key}`">
          {{ field.label
          }}<span v-if="field.required" class="text-destructive"> *</span>
        </label>
        <Select
          v-if="field.kind === 'select'"
          :id="`clean-parameter-${field.key}`"
          :disabled="disabled"
          :options="field.options"
          :placeholder="`默认：${field.options?.find((item) => item.value === field.defaultValue)?.label}`"
          :value="selectValue(field.key)"
          allow-clear
          class="w-full"
          @update:value="update(field.key, $event)"
        />
        <RadioGroup
          v-else-if="field.kind === 'boolean'"
          :id="`clean-parameter-${field.key}`"
          :disabled="disabled || value(field.key) === null"
          :value="booleanValue(field.key)"
          :options="[
            { label: '是 / true', value: true },
            { label: '否 / false', value: false },
          ]"
          @update:value="update(field.key, $event)"
        />
        <InputNumber
          v-else-if="field.kind === 'integer'"
          :id="`clean-parameter-${field.key}`"
          :disabled="disabled || value(field.key) === null"
          :max="field.max"
          :min="field.min"
          :placeholder="
            field.defaultValue === undefined
              ? `请输入 ${field.min} 至 ${field.max} 的整数`
              : `默认：${field.defaultValue}`
          "
          :precision="0"
          :value="numberValue(field.key)"
          class="w-full"
          @update:value="update(field.key, $event)"
        />
        <Select
          v-else-if="field.kind === 'tags'"
          :id="`clean-parameter-${field.key}`"
          :disabled="disabled"
          :value="formats(field.key)"
          class="w-full"
          mode="tags"
          :placeholder="`输入${field.label}并回车`"
          @update:value="update(field.key, $event)"
        />
        <template v-else-if="field.kind === 'mapping'">
          <div
            v-for="(item, index) in mappings(field.key)"
            :key="index"
            class="flex gap-2"
          >
            <Input
              :disabled="disabled"
              :maxlength="128"
              :value="item.value"
              placeholder="原值（可为空串）"
              @update:value="updateItem(field.key, index, 'value', $event)"
            />
            <Input
              :disabled="disabled"
              :maxlength="256"
              :value="item.name"
              placeholder="目标值（可为空串）"
              @update:value="updateItem(field.key, index, 'name', $event)"
            />
            <Button :disabled="disabled" @click="removeItem(field.key, index)">
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              移除
            </Button>
          </div>
          <Button
            :disabled="disabled || mappings(field.key).length >= 1000"
            @click="
              update(field.key, [
                ...mappings(field.key),
                { value: '', name: '' },
              ])
            "
          >
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            添加映射
          </Button>
        </template>
        <template v-else-if="field.kind === 'sort'">
          <div
            v-for="(item, index) in sorts(field.key)"
            :key="index"
            class="flex flex-wrap gap-2"
          >
            <Input
              :disabled="disabled"
              :maxlength="128"
              :value="item.columns"
              placeholder="排序字段名"
              @update:value="updateItem(field.key, index, 'columns', $event)"
            />
            <Select
              :disabled="disabled"
              :options="[
                { label: '值大者优先', value: 0 },
                { label: '值小者优先', value: 1 },
              ]"
              :value="item.type"
              class="w-36"
              @update:value="updateItem(field.key, index, 'type', $event)"
            />
            <InputNumber
              :disabled="disabled"
              :max="100000"
              :min="-100000"
              :precision="0"
              :value="item.sort"
              placeholder="排序优先级"
              @update:value="updateItem(field.key, index, 'sort', $event)"
            />
            <Button :disabled="disabled" @click="removeItem(field.key, index)">
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              移除
            </Button>
          </div>
          <Button
            :disabled="disabled || sorts(field.key).length >= 32"
            @click="
              update(field.key, [
                ...sorts(field.key),
                { columns: '', type: 0, sort: sorts(field.key).length },
              ])
            "
          >
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            添加排序字段
          </Button>
          <div class="text-muted-foreground text-xs">
            优先级越小越先比较；不配置排序时保留首条。
          </div>
        </template>
        <Input
          v-else
          :id="`clean-parameter-${field.key}`"
          :disabled="disabled || value(field.key) === null"
          :maxlength="field.maxLength"
          :placeholder="
            field.defaultValue === undefined
              ? `请输入${field.label}`
              : `默认：${field.defaultValue}`
          "
          :value="textValue(field.key)"
          @update:value="update(field.key, $event)"
        />
        <Checkbox
          v-if="field.allowNull"
          :checked="value(field.key) === null"
          :disabled="disabled"
          @update:checked="update(field.key, $event ? null : undefined)"
        >
          将匹配值替换为 null
        </Checkbox>
        <div
          v-if="field.kind === 'decimal'"
          class="text-muted-foreground text-xs"
        >
          以十进制字符串保存，保留输入精度；边界顺序由后端校验。
        </div>
        <div
          v-if="
            strategyKey === 'CHECK_EXPIRATION' &&
            field.key === 'dataRangeValue' &&
            field.kind === 'text'
          "
          class="text-muted-foreground text-xs"
        >
          请输入截止日期，如
          2026-10-01；切换方式后保留的相对数量需要重新填写为日期。
        </div>
      </div>
    </template>
    <details>
      <summary class="text-muted-foreground cursor-pointer text-xs">
        查看已保存参数
      </summary>
      <pre class="mt-1 overflow-auto whitespace-pre-wrap text-xs">{{
        formatCleanRuleConfig(modelValue)
      }}</pre>
    </details>
  </div>
</template>
