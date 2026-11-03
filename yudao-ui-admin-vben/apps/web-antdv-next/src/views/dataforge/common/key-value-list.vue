<script lang="ts" setup>
import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Input } from 'antdv-next';

/**
 * 键值对/字段映射列表编辑器。
 *
 * 作为 useVbenForm 的自定义组件使用（component: markRaw(KeyValueList)），
 * 通过 `value` / `update:value` 与表单双向绑定；行结构由 keyField/valueField 决定，
 * 可直接承载 `{ source, target }`（字段映射）或 `{ key, value }`（请求头/参数）。
 */
type PairRow = Record<string, string | undefined>;

const props = withDefaults(
  defineProps<{
    addText?: string;
    disabled?: boolean;
    emptyText?: string;
    keyField?: string;
    keyPlaceholder?: string;
    modelValue?: PairRow[];
    /** 键名匹配该正则时，值以密码框展示（用于 Authorization / token 等） */
    secretKeyPattern?: RegExp;
    separator?: string;
    value?: PairRow[];
    valueField?: string;
    valuePlaceholder?: string;
  }>(),
  {
    addText: '添加一行',
    disabled: false,
    emptyText: '暂无配置',
    keyField: 'key',
    keyPlaceholder: '键',
    modelValue: undefined,
    secretKeyPattern: undefined,
    separator: '→',
    value: undefined,
    valueField: 'value',
    valuePlaceholder: '值',
  },
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: PairRow[]): void;
  (e: 'update:value', value: PairRow[]): void;
}>();

const rows = computed(() => props.modelValue ?? props.value ?? []);

function commit(next: PairRow[]) {
  emit('update:modelValue', next);
  emit('update:value', next);
}

function updateCell(index: number, field: string, val: string) {
  commit(
    rows.value.map((row, i) => (i === index ? { ...row, [field]: val } : row)),
  );
}

function addRow() {
  commit([...rows.value, { [props.keyField]: '', [props.valueField]: '' }]);
}

function removeRow(index: number) {
  commit(rows.value.filter((_, i) => i !== index));
}

function isSecret(row: PairRow) {
  const key = row[props.keyField] ?? '';
  return !!props.secretKeyPattern && props.secretKeyPattern.test(key);
}
</script>

<template>
  <div class="w-full">
    <div
      v-if="rows.length === 0"
      class="text-muted-foreground mb-2 text-xs leading-6"
    >
      {{ emptyText }}
    </div>
    <div
      v-for="(row, index) in rows"
      :key="index"
      class="mb-2 flex items-center gap-2"
    >
      <Input
        :disabled="disabled"
        :placeholder="keyPlaceholder"
        :value="row[keyField]"
        class="flex-1"
        @update:value="(val: string) => updateCell(index, keyField, val)"
      />
      <span class="text-muted-foreground shrink-0">{{ separator }}</span>
      <Input.Password
        v-if="isSecret(row)"
        :disabled="disabled"
        :placeholder="valuePlaceholder"
        :value="row[valueField]"
        class="flex-1"
        @update:value="(val: string) => updateCell(index, valueField, val)"
      />
      <Input
        v-else
        :disabled="disabled"
        :placeholder="valuePlaceholder"
        :value="row[valueField]"
        class="flex-1"
        @update:value="(val: string) => updateCell(index, valueField, val)"
      />
      <Button
        :disabled="disabled"
        danger
        size="small"
        type="text"
        @click="removeRow(index)"
      >
        <template #icon>
          <IconifyIcon icon="lucide:trash-2" />
        </template>
      </Button>
    </div>
    <Button
      :disabled="disabled"
      block
      size="small"
      type="dashed"
      @click="addRow"
    >
      <template #icon>
        <IconifyIcon icon="lucide:plus" />
      </template>
      {{ addText }}
    </Button>
  </div>
</template>
