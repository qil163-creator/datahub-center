<script setup lang="ts">
import { computed } from 'vue';

import { Button, Checkbox, Input, InputNumber, Select } from 'antdv-next';

import ParameterEditor from '../../../cleanrule/modules/parameter-editor.vue';
import { auditParameterFields } from './audit-rule-parameters';

const props = defineProps<{
  fieldOptions: { label: string; value: string }[];
  readonly?: boolean;
  referenceFieldOptions?: { label: string; value: string }[];
  strategyKey: string;
}>();
const config = defineModel<Record<string, unknown>>('config', {
  required: true,
});
const schema = computed(() =>
  auditParameterFields(props.strategyKey, config.value),
);
const records = (name: string) =>
  Array.isArray(config.value[name])
    ? (config.value[name] as Record<string, unknown>[])
    : [];
const text = (value: unknown) =>
  typeof value === 'string' ? value : undefined;
const number = (value: unknown) =>
  typeof value === 'number' ? value : undefined;
function update(name: string, index: number, field: string, value: unknown) {
  if (props.readonly) return;
  const rows = records(name).map((row) => ({ ...row }));
  let row = rows[index]!;
  if (
    value === undefined ||
    (name === 'expectedColumns' &&
      field !== 'name' &&
      (value === '' || value === null))
  )
    row = Object.fromEntries(
      Object.entries(row).filter(([key]) => key !== field),
    );
  else row[field] = value;
  if (name === 'assertions' && field === 'operator') {
    delete row.value;
    delete row.values;
  }
  rows[index] = row;
  config.value = { ...config.value, [name]: rows };
}
function add(name: string, value: Record<string, unknown>) {
  if (!props.readonly)
    config.value = { ...config.value, [name]: [...records(name), value] };
}
function remove(name: string, index: number) {
  if (!props.readonly)
    config.value = {
      ...config.value,
      [name]: records(name).filter((_, item) => item !== index),
    };
}
const comparisonOperators = ['<', '<=', '>', '>=', '=', '<>'].map((value) => ({
  label: value,
  value,
}));
const assertionOperators = [
  'IS_NULL',
  'NOT_NULL',
  'EQ',
  'NE',
  'GT',
  'GTE',
  'LT',
  'LTE',
  'IN',
  'NOT_IN',
].map((value) => ({ label: value, value }));
</script>

<template>
  <div class="q-span space-y-3" data-testid="audit-parameter-editor">
    <p
      v-if="strategyKey === 'RECORD_COMPLETENESS_VALIDATION'"
      class="text-muted-foreground text-xs"
    >
      服务端自动检查整张表的全部非主键字段，并排除主键；此规则不配置 Where
      条件。
    </p>
    <ParameterEditor
      :model-value="config"
      :strategy-key="strategyKey"
      :parameter-fields="schema"
      :validation-issues="[]"
      :disabled="readonly"
      supported
      @update:model-value="config = $event"
    />
    <Checkbox
      v-if="strategyKey === 'PLACEHOLDER_VALIDATION'"
      :checked="(config.values as string[] | undefined)?.includes('') ?? false"
      :disabled="readonly"
      @update:checked="
        config = {
          ...config,
          values: $event
            ? [...new Set([...((config.values as string[]) ?? []), ''])]
            : ((config.values as string[]) ?? []).filter(
                (value) => value !== '',
              ),
        }
      "
    >
      将空字符串也视为非法占位值
    </Checkbox>
    <template
      v-if="
        [
          'CONDITIONAL_REQUIRED_VALIDATION',
          'STATE_CONSISTENCY_VALIDATION',
        ].includes(strategyKey)
      "
    >
      <label>触发条件字段</label>
      <Select
        v-model:value="config.conditionColumn"
        :options="fieldOptions"
        :disabled="readonly"
        class="w-full"
        placeholder="请选择触发条件字段"
      />
      <template v-if="strategyKey === 'CONDITIONAL_REQUIRED_VALIDATION'">
        <label>条件命中后的必填字段</label>
        <Select
          v-model:value="config.requiredColumns"
          :options="fieldOptions"
          :disabled="readonly"
          mode="multiple"
          class="w-full"
          placeholder="请选择必填字段"
        />
      </template>
    </template>
    <template v-if="strategyKey === 'NUMERIC_RELATION_VALIDATION'">
      <div
        v-for="(row, index) in records('conditions')"
        :key="index"
        class="flex flex-wrap gap-2"
      >
        <Select
          :value="text(row.leftField)"
          :options="fieldOptions"
          :disabled="readonly"
          placeholder="左侧数值字段"
          class="min-w-40"
          @update:value="update('conditions', index, 'leftField', $event)"
        />
        <Select
          :value="text(row.operator)"
          :options="comparisonOperators"
          :disabled="readonly"
          class="w-24"
          @update:value="update('conditions', index, 'operator', $event)"
        />
        <Select
          :value="text(row.rightField)"
          :options="fieldOptions"
          :disabled="readonly"
          placeholder="右侧数值字段"
          class="min-w-40"
          @update:value="update('conditions', index, 'rightField', $event)"
        />
        <Button v-if="!readonly" @click="remove('conditions', index)">
          移除条件
        </Button>
      </div>
      <Button
        v-if="!readonly"
        :disabled="records('conditions').length >= 32"
        @click="
          add('conditions', { leftField: '', operator: '<=', rightField: '' })
        "
      >
        添加数值关系
      </Button>
    </template>
    <template v-if="strategyKey === 'STATE_CONSISTENCY_VALIDATION'">
      <div
        v-for="(row, index) in records('assertions')"
        :key="index"
        class="flex flex-wrap gap-2"
      >
        <Select
          :value="text(row.column)"
          :options="fieldOptions"
          :disabled="readonly"
          placeholder="断言字段"
          class="min-w-40"
          @update:value="update('assertions', index, 'column', $event)"
        />
        <Select
          :value="text(row.operator)"
          :options="assertionOperators"
          :disabled="readonly"
          class="w-32"
          @update:value="update('assertions', index, 'operator', $event)"
        />
        <template
          v-if="!['IS_NULL', 'NOT_NULL'].includes(String(row.operator))"
        >
          <Select
            :value="text(row.valueType)"
            :options="[
              { label: '文本', value: 'TEXT' },
              { label: '精确数值', value: 'DECIMAL' },
            ]"
            :disabled="readonly"
            class="w-32"
            @update:value="update('assertions', index, 'valueType', $event)"
          />
          <Select
            v-if="['IN', 'NOT_IN'].includes(String(row.operator))"
            :value="row.values as string[]"
            :disabled="readonly"
            mode="tags"
            class="min-w-40"
            placeholder="比较值集合"
            @update:value="update('assertions', index, 'values', $event)"
          />
          <Input
            v-else
            :value="text(row.value)"
            :disabled="readonly"
            placeholder="比较值，精确数值保持文本"
            class="w-48"
            @update:value="update('assertions', index, 'value', $event)"
          />
        </template>
        <Button v-if="!readonly" @click="remove('assertions', index)">
          移除断言
        </Button>
      </div>
      <Button
        v-if="!readonly"
        :disabled="records('assertions').length >= 32"
        @click="
          add('assertions', {
            column: '',
            operator: 'NOT_NULL',
            valueType: 'TEXT',
          })
        "
      >
        添加状态断言
      </Button>
    </template>
    <template v-if="strategyKey === 'MODEL_STRUCTURE_VALIDATION'">
      <div class="overflow-x-auto">
        <table class="q-table min-w-[780px]">
          <thead>
            <tr>
              <th>字段名</th>
              <th>期望类型</th>
              <th>允许空值</th>
              <th>长度</th>
              <th>精度</th>
              <th>小数位</th>
              <th v-if="!readonly">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in records('expectedColumns')" :key="index">
              <td>
                <Input
                  :value="text(row.name)"
                  :disabled="readonly"
                  placeholder="字段名"
                  @update:value="
                    update('expectedColumns', index, 'name', $event)
                  "
                />
              </td>
              <td>
                <Input
                  :value="text(row.type)"
                  :disabled="readonly"
                  placeholder="不填写则不比较"
                  @update:value="
                    update('expectedColumns', index, 'type', $event)
                  "
                />
              </td>
              <td>
                <Select
                  :value="
                    typeof row.nullable === 'boolean'
                      ? String(row.nullable)
                      : 'IGNORE'
                  "
                  :options="[
                    { label: '不约束', value: 'IGNORE' },
                    { label: '可空', value: 'true' },
                    { label: '不可空', value: 'false' },
                  ]"
                  :disabled="readonly"
                  class="w-24"
                  @update:value="
                    update(
                      'expectedColumns',
                      index,
                      'nullable',
                      $event === 'IGNORE' ? undefined : $event === 'true',
                    )
                  "
                />
              </td>
              <td
                v-for="field in ['length', 'precision', 'scale']"
                :key="field"
              >
                <InputNumber
                  :value="number(row[field])"
                  :disabled="readonly"
                  :min="0"
                  :precision="0"
                  @update:value="
                    update('expectedColumns', index, field, $event)
                  "
                />
              </td>
              <td v-if="!readonly">
                <Button @click="remove('expectedColumns', index)">移除</Button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Button
        v-if="!readonly"
        :disabled="records('expectedColumns').length >= 256"
        @click="add('expectedColumns', { name: '' })"
      >
        添加模型字段
      </Button>
      <p class="text-muted-foreground text-xs">
        这里只配置期望结构；实际结构由服务端读取授权数据源后比较。
      </p>
    </template>
    <template v-if="strategyKey === 'CROSS_TABLE_CONSISTENCY'">
      <label>本表连接键</label>
      <Select
        v-model:value="config.leftColumn"
        :options="fieldOptions"
        :disabled="readonly"
        class="w-full"
        placeholder="请选择本表连接键"
      />
      <div
        v-for="(row, index) in records('comparisons')"
        :key="index"
        class="flex flex-wrap gap-2"
      >
        <Select
          :value="text(row.leftColumn)"
          :options="fieldOptions"
          :disabled="readonly"
          class="min-w-40"
          placeholder="本表比较字段"
          @update:value="update('comparisons', index, 'leftColumn', $event)"
        />
        <Select
          :value="text(row.rightColumn)"
          :options="referenceFieldOptions"
          :disabled="readonly"
          class="min-w-40"
          placeholder="引用表比较字段"
          @update:value="update('comparisons', index, 'rightColumn', $event)"
        />
        <Button v-if="!readonly" @click="remove('comparisons', index)">
          移除比较
        </Button>
      </div>
      <Button
        v-if="!readonly"
        :disabled="records('comparisons').length >= 32"
        @click="add('comparisons', { leftColumn: '', rightColumn: '' })"
      >
        添加跨表比较字段
      </Button>
    </template>
  </div>
</template>
