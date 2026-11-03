<script setup lang="ts">
import type { EditableLogicKind, LogicFieldRow } from '../editor-state';

import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { computed, ref } from 'vue';

import { Checkbox, Input, InputNumber, Modal, Select, Tag } from 'antdv-next';

import { isDecimalField } from '../../modelcommon/field-state';
import {
  LOGIC_FIELD_CATEGORIES,
  LOGIC_FIELD_TYPES,
  newLogicField,
  numericField,
} from '../editor-state';
import { QdIcon } from '../icons';

type Option = { label: string; value: number | string };
const props = withDefaults(
  defineProps<{
    classifications?: Array<Option & { dataGrade?: string }>;
    codeTables?: Option[];
    dimensions?: Option[];
    disabled?: boolean;
    kind: EditableLogicKind;
    modelValue: LogicFieldRow[];
    standards?: GovernanceStandardElementApi.StandardElement[];
  }>(),
  {
    disabled: false,
    standards: () => [],
    codeTables: () => [],
    classifications: () => [],
    dimensions: () => [],
  },
);
const emit = defineEmits<{ 'update:modelValue': [value: LogicFieldRow[]] }>();
const keyword = ref('');
const checked = ref(new Set<string>());
const rows = computed(() =>
  props.modelValue.filter((row) =>
    `${row.fieldName || ''} ${row.cnName || ''}`
      .toLowerCase()
      .includes(keyword.value.trim().toLowerCase()),
  ),
);
const allChecked = computed(
  () =>
    selectableRows.value.length > 0 &&
    selectableRows.value.every((row) => checked.value.has(row._key)),
);
const selectableRows = computed(() =>
  rows.value.filter((row) => props.kind === 'application' || !row.pkFlag),
);
const standardOptions = computed(() =>
  props.standards.map((item) => ({ label: item.name, value: String(item.id) })),
);
const constraints = [
  { label: '非空', value: 'NOT_NULL' },
  { label: '唯一性', value: 'UNIQUE' },
];
function patch(row: LogicFieldRow, values: Partial<LogicFieldRow>) {
  emit(
    'update:modelValue',
    props.modelValue.map((item) =>
      item._key === row._key ? { ...item, ...values } : item,
    ),
  );
}
function add() {
  emit('update:modelValue', [
    ...props.modelValue,
    newLogicField({
      fieldCategory: props.kind === 'application' ? 'NORMAL' : 'ATTRIBUTE',
    }),
  ]);
}
function remove(keys: Set<string>) {
  if (props.disabled || keys.size === 0) return;
  const deletable = new Set(
    selectableRows.value
      .filter((row) => keys.has(row._key))
      .map((row) => row._key),
  );
  if (deletable.size === 0) return;
  Modal.confirm({
    title: '删除字段',
    content: `确认删除选中的 ${deletable.size} 个字段吗？`,
    onOk() {
      emit(
        'update:modelValue',
        props.modelValue.filter((row) => !deletable.has(row._key)),
      );
      checked.value = new Set();
    },
  });
}
function toggle(row: LogicFieldRow, value: boolean) {
  const next = new Set(checked.value);
  if (value) next.add(row._key);
  else next.delete(row._key);
  checked.value = next;
}
function rawId(value: unknown): number | string | undefined {
  return typeof value === 'string' || typeof value === 'number'
    ? value
    : undefined;
}
function setConstraints(row: LogicFieldRow, value: unknown) {
  const values = Array.isArray(value) ? value : [];
  patch(row, {
    nullableFlag: !values.includes('NOT_NULL'),
    uniqueFlag: values.includes('UNIQUE'),
  });
}
function chooseClassification(row: LogicFieldRow, value: unknown) {
  const id = rawId(value);
  const option = props.classifications.find(
    (item) => String(item.value) === String(id),
  );
  patch(row, {
    dataClassificationId: id,
    dataClassificationName: option?.label,
    dataGrade: option?.dataGrade,
  });
}
function chooseStandard(row: LogicFieldRow, value: unknown) {
  const id = rawId(value);
  const standard = props.standards.find(
    (item) => String(item.id) === String(id),
  );
  patch(row, {
    standardId: id,
    standardName: standard?.name,
    standardCode: standard?.code,
    ...(standard
      ? {
          fieldName: standard.engName || row.fieldName,
          cnName: standard.name,
          dataType: standard.columnType || row.dataType,
          dataLength: standard.dataLength ?? row.dataLength,
          dataPrecision: standard.dataPrecision,
          dataScale: standard.dataScale,
          standardColumnType: standard.columnType,
          dataFormat: standard.dataFormat,
          measureUnit: standard.measureUnit,
          valueRange: standard.valueRange,
        }
      : {}),
  });
}
defineExpose({ add });
</script>

<template>
  <div class="qd-field-grid">
    <div class="qd-editor-toolbar">
      <div class="qd-editor-toolbar__actions">
        <button
          class="qd-editor-button is-plain"
          type="button"
          :disabled="disabled"
          @click="add"
        >
          <QdIcon name="plus" />新增字段
        </button>
        <slot name="actions"></slot>
        <button
          class="qd-editor-button is-danger"
          type="button"
          :disabled="disabled || checked.size === 0"
          @click="remove(checked)"
        >
          <QdIcon name="delete" />批量删除
        </button>
      </div>
      <Input
        v-model:value="keyword"
        class="qd-field-search"
        placeholder="请输入字段中文/英文名称"
        allow-clear
      />
    </div>
    <div class="qd-field-grid__scroll">
      <table class="qd-field-grid__table">
        <colgroup>
          <col style="width: 36px" />
          <col style="width: 55px" />
          <col style="width: 190px" />
          <col style="width: 170px" />
          <col style="width: 145px" />
          <col style="width: 125px" />
          <col v-if="kind !== 'application'" style="width: 140px" />
          <col style="width: 170px" />
          <col style="width: 170px" />
          <col style="width: 130px" />
          <col style="width: 175px" />
          <col style="width: 200px" />
          <col style="width: 235px" />
          <col style="width: 185px" />
          <col style="width: 185px" />
          <col v-if="kind === 'application'" style="width: 260px" />
          <col style="width: 90px" />
        </colgroup>
        <thead>
          <tr>
            <th>
              <Checkbox
                :checked="allChecked"
                :indeterminate="checked.size > 0 && !allChecked"
                :disabled="disabled"
                @change="
                  (event) =>
                    (checked = new Set(
                      event.target.checked
                        ? selectableRows.map((row) => row._key)
                        : [],
                    ))
                "
              />
            </th>
            <th>编号</th>
            <th>字段英文名称</th>
            <th>字段中文名称</th>
            <th>数据类型</th>
            <th>{{ kind === 'application' ? '是否主键' : '字段类别' }}</th>
            <th v-if="kind !== 'application'">关联维度</th>
            <th>属性长度/总精度</th>
            <th>小数位数</th>
            <th>默认值</th>
            <th>数据分类</th>
            <th>数据分级</th>
            <th>字段约束</th>
            <th>标准数据元</th>
            <th>标准代码表</th>
            <th v-if="kind === 'application'">描述</th>
            <th class="qd-field-grid__fixed">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row._key"
            :class="{ 'is-selected': checked.has(row._key) }"
          >
            <td>
              <Checkbox
                :checked="checked.has(row._key)"
                :disabled="disabled || (kind !== 'application' && !!row.pkFlag)"
                @change="(event) => toggle(row, !!event.target.checked)"
              />
            </td>
            <td>{{ modelValue.indexOf(row) + 1 }}</td>
            <td>
              <Input
                :value="row.fieldName"
                placeholder="请输入字段英文名称"
                :disabled="disabled"
                @update:value="(value) => patch(row, { fieldName: value })"
              />
            </td>
            <td>
              <Input
                :value="row.cnName"
                placeholder="请输入字段中文名称"
                :disabled="disabled"
                @update:value="(value) => patch(row, { cnName: value })"
              />
            </td>
            <td>
              <Select
                :value="row.dataType"
                :options="LOGIC_FIELD_TYPES"
                :disabled="disabled || (kind !== 'application' && !!row.pkFlag)"
                @update:value="
                  (value) =>
                    patch(row, {
                      dataType: String(value),
                      dataScale: numericField(String(value))
                        ? row.dataScale
                        : undefined,
                    })
                "
              />
            </td>
            <td>
              <Select
                v-if="kind === 'application'"
                :value="row.pkFlag ? 1 : 0"
                :options="[
                  { label: '是', value: 1 },
                  { label: '否', value: 0 },
                ]"
                :disabled="disabled"
                @update:value="
                  (value) =>
                    patch(row, {
                      pkFlag: value === 1,
                      nullableFlag: value === 1 ? false : row.nullableFlag,
                    })
                "
              />
              <Input v-else-if="row.pkFlag" value="主键" disabled />
              <Select
                v-else
                :value="row.fieldCategory || 'ATTRIBUTE'"
                :options="LOGIC_FIELD_CATEGORIES"
                :disabled="disabled"
                @update:value="
                  (value) => patch(row, { fieldCategory: String(value) })
                "
              />
            </td>
            <td v-if="kind !== 'application'">
              <Select
                v-if="row.fieldCategory === 'DIMENSION'"
                :value="
                  row.dimensionId === undefined
                    ? undefined
                    : String(row.dimensionId)
                "
                :options="dimensions"
                placeholder="请选择维度"
                :disabled="disabled"
                allow-clear
                show-search
                option-filter-prop="label"
                @update:value="
                  (value) =>
                    patch(row, {
                      dimensionId: rawId(value),
                      dimensionName: dimensions.find(
                        (item) => String(item.value) === String(value),
                      )?.label,
                    })
                "
              />
              <span v-else>{{ row.dimensionName || '-' }}</span>
            </td>
            <td>
              <InputNumber
                :value="
                  isDecimalField(row) ? row.dataPrecision : row.dataLength
                "
                :min="1"
                :precision="0"
                :placeholder="
                  isDecimalField(row) ? '请输入总精度' : '请输入属性长度'
                "
                :aria-label="isDecimalField(row) ? '总精度' : '属性长度'"
                :disabled="disabled"
                @update:value="
                  (value) =>
                    patch(
                      row,
                      isDecimalField(row)
                        ? { dataPrecision: value === null ? undefined : value }
                        : { dataLength: value === null ? undefined : value },
                    )
                "
              />
            </td>
            <td>
              <InputNumber
                :value="row.dataScale"
                :min="0"
                :precision="0"
                placeholder="请输入小数位数"
                :disabled="disabled || !numericField(row.dataType)"
                @update:value="
                  (value) =>
                    patch(row, {
                      dataScale: value === null ? undefined : value,
                    })
                "
              />
            </td>
            <td>
              <Input
                :value="row.defaultValue"
                placeholder="请输入默认值"
                :disabled="disabled"
                @update:value="(value) => patch(row, { defaultValue: value })"
              />
            </td>
            <td>
              <Select
                :value="
                  row.dataClassificationId === undefined
                    ? undefined
                    : String(row.dataClassificationId)
                "
                :options="classifications"
                :disabled="disabled"
                placeholder="请选择数据分类"
                allow-clear
                show-search
                option-filter-prop="label"
                @update:value="(value) => chooseClassification(row, value)"
              />
            </td>
            <td>
              <Input
                :value="row.dataGrade"
                disabled
                :placeholder="
                  kind === 'application' ? '请先选择数据分类' : '请选择数据分级'
                "
              />
            </td>
            <td>
              <div v-if="row.pkFlag" class="qd-field-grid__constraint">
                <Tag color="purple">非空</Tag><Tag color="purple">唯一性</Tag>
              </div>
              <Select
                v-else
                :value="[
                  ...(row.nullableFlag === false ? ['NOT_NULL'] : []),
                  ...(row.uniqueFlag ? ['UNIQUE'] : []),
                ]"
                :options="constraints"
                mode="multiple"
                :max-tag-count="2"
                placeholder="请选择字段约束"
                :disabled="disabled"
                @update:value="(value) => setConstraints(row, value)"
              />
            </td>
            <td>
              <Select
                :value="
                  row.standardId === undefined || row.standardId === null
                    ? undefined
                    : String(row.standardId)
                "
                :options="standardOptions"
                :disabled="disabled"
                placeholder="请选择标准数据元"
                allow-clear
                show-search
                option-filter-prop="label"
                @update:value="(value) => chooseStandard(row, value)"
              />
            </td>
            <td>
              <Select
                :value="
                  row.codeTableId === undefined
                    ? undefined
                    : String(row.codeTableId)
                "
                :options="codeTables"
                :disabled="disabled"
                placeholder="请选择标准代码表"
                allow-clear
                show-search
                option-filter-prop="label"
                @update:value="
                  (value) =>
                    patch(row, {
                      codeTableId: rawId(value),
                      codeTableName: codeTables.find(
                        (item) => String(item.value) === String(value),
                      )?.label,
                    })
                "
              />
            </td>
            <td v-if="kind === 'application'">
              <Input
                :value="row.comment"
                placeholder="请输入字段描述"
                :disabled="disabled"
                :maxlength="256"
                @update:value="(value) => patch(row, { comment: value })"
              />
            </td>
            <td class="qd-field-grid__fixed">
              <button
                class="qd-field-grid__delete"
                type="button"
                :disabled="disabled || (kind !== 'application' && !!row.pkFlag)"
                @click="remove(new Set([row._key]))"
              >
                <QdIcon name="delete" />删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="rows.length === 0" class="qd-field-grid__empty">
        {{ keyword ? '未找到匹配字段' : '暂无记录' }}
      </div>
    </div>
  </div>
</template>
