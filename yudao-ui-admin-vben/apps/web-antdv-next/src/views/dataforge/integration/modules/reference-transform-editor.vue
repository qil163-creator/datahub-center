<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑父设计器隔离草稿，disabled时禁止所有配置写入。 */
import { computed, ref, watch } from 'vue';

import {
  Alert,
  AutoComplete,
  Button,
  Checkbox,
  Input,
  InputNumber,
  Modal,
  Select,
  TextArea,
  Tooltip,
} from 'antdv-next';

import JavaScriptEditor from '#/views/dataforge/etl/pipeline/node-editors/java-script.vue';

import ReferenceFieldMergeDialog from './reference-field-merge-dialog.vue';
import ReferenceIcon from './reference-icon.vue';
import {
  moveReferenceField,
  referenceFieldTypes,
  resolveReferenceFields,
} from './reference-node-schema';
import {
  referenceCalculations,
  validateReferenceDerive,
  validateReferenceSequence,
  validateReferenceTransformRows,
} from './reference-transform-schema';

import './reference-node-editor.css';
import './reference-transform-layout.css';
import './field-split-reference.css';
import './id-generator-reference.css';
import './calculator-reference.css';
import './value-map-reference.css';
import './add-constant-reference.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  engine?: string;
  inputFields?: {
    dataType?: string;
    length?: null | number | string;
    name?: string;
    scale?: null | number;
  }[];
  toolCode: string;
}>();
type Row = Record<string, any>;
type Column = {
  check?: boolean;
  help?: string;
  key: string;
  label: string;
  metadata?: boolean;
  minArity?: number;
  options?: { label: string; value: string }[];
  placeholder?: string;
  source?: boolean;
};
const errors = ref<string[]>([]);
const fieldMergeOpen = ref(false);
function openFieldMerge() {
  if (!props.disabled && props.inputFields?.length) fieldMergeOpen.value = true;
}
const sectionTitle = computed(
  () =>
    ({
      'sort-records': '字段',
      'field-split': '拆分字段',
      'field-derive': '字段值',
      calculator: '字段',
      'value-map': '字段值',
      'add-constant': '常量字段',
      'number-range': '范围(最小 <= 值 < 最大)',
      'java-script': '声明输出字段',
    })[props.toolCode] ?? '字段配置',
);
let dragged = -1;
const rowKey = computed(
  () =>
    ({
      'value-map': 'mappings',
      'add-constant': 'fields',
      'number-range': 'ranges',
      'field-split': 'outputs',
      'sort-records': 'sorts',
      'java-script': 'outputFields',
      'field-derive': 'sourceFields',
    })[props.toolCode] || 'rules',
);
const rows = computed<Row[]>(() => {
  const value = props.config[rowKey.value];
  if (!Array.isArray(value)) return [];
  return props.toolCode === 'field-derive'
    ? value.map((name, sourceIndex) => ({ name, sourceIndex }))
    : value;
});
const originalRulesSnapshot = ref('');
const originalDuplicateErrors = ref<string[]>([]);
const duplicateError = (error: string) =>
  error.startsWith('存在重复字段或原值：');
watch(
  () => [props.config, props.toolCode],
  () => {
    originalRulesSnapshot.value = JSON.stringify(rows.value);
    originalDuplicateErrors.value = ['calculator', 'value-map'].includes(
      props.toolCode,
    )
      ? validateReferenceTransformRows(props.toolCode, rows.value).filter(
          (error) => duplicateError(error),
        )
      : [];
  },
  { immediate: true, flush: 'sync' },
);
const unchangedLegacyDuplicates = computed(
  () =>
    originalDuplicateErrors.value.length > 0 &&
    originalRulesSnapshot.value === JSON.stringify(rows.value),
);
const deriveModes = [
  ['CONCAT', '拼接'],
  ['SUBSTRING', '截取'],
  ['REPLACE', '替换'],
  ['EXPRESSION', '表达式'],
  ['HASH', '哈希'],
  ['REGEX_EXTRACT', '正则提取'],
  ['CONSTANT', '常量赋值'],
].map(([value, label]) => ({ value, label }));
const deriveParameters = computed(() => {
  const parameters: { key: string; label: string; number?: boolean }[] = [];
  switch (props.config.operateType) {
    case 'CONCAT': {
      parameters.push(
        { key: 'prefix', label: '前缀' },
        { key: 'suffix', label: '后缀' },
        { key: 'separator', label: '连接符' },
      );
      break;
    }
    case 'CONSTANT': {
      parameters.push({ key: 'constantValue', label: '常量值' });
      break;
    }
    case 'REGEX_EXTRACT': {
      parameters.push(
        { key: 'pattern', label: '正则表达式' },
        { key: 'groupIndex', label: '分组序号（0整体匹配）', number: true },
      );
      break;
    }
    case 'REPLACE': {
      parameters.push(
        { key: 'search', label: '查找内容' },
        { key: 'replacement', label: '替换为' },
      );
      break;
    }
    case 'SUBSTRING': {
      parameters.push(
        { key: 'beginIndex', label: '起始位置（从1开始）', number: true },
        { key: 'length', label: '截取长度（0到末尾）', number: true },
      );
      break;
    }
  }
  return parameters;
});
const showRows = computed(
  () =>
    props.toolCode !== 'field-derive' ||
    !['CONSTANT', 'EXPRESSION'].includes(props.config.operateType),
);
const sourceOptions = computed(() =>
  (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({ value: field.name!, label: field.name! })),
);
const choices = (...values: string[]) =>
  values.map((value) => ({ value, label: value }));
const trims = [
  { value: 'NONE', label: '不去空格' },
  { value: 'LEFT', label: '去除左空格' },
  { value: 'RIGHT', label: '去除右空格' },
  { value: 'BOTH', label: '去除两边空格' },
];
const columns = computed<Column[]>(() => {
  switch (props.toolCode) {
    case 'add-constant': {
      return [
        { key: 'name', label: '字段名称', placeholder: '请输入' },
        {
          key: 'fieldType',
          label: '字段类型',
          options: choices(
            'String',
            'Integer',
            'Long',
            'Double',
            'Boolean',
            'Date',
          ),
        },
        { key: 'value', label: '默认值', placeholder: '请输入' },
        { key: 'setEmpty', label: '设为空串', check: true, help: '设为是时，输出空字符串并忽略默认值' },
      ];
    }
    case 'calculator': {
      return [
        { key: 'targetField', label: '结果字段名称', placeholder: '请输入结果字段名称' },
        { key: 'calcType', label: '计算类型', options: referenceCalculations, placeholder: '请选择计算类型' },
        { key: 'fieldA', label: '字段A', source: true, minArity: 1, placeholder: '请选择...' },
        { key: 'fieldB', label: '字段B', source: true, minArity: 2, placeholder: '请选择...' },
        { key: 'fieldC', label: '字段C', source: true, minArity: 3, placeholder: '请选择...' },
        {
          key: 'valueType',
          label: '值类型',
          options: [
            { label: '字符串', value: 'STRING' },
            { label: '整数', value: 'INTEGER' },
            { label: '小数', value: 'DOUBLE' },
          ],
        },
      ];
    }
    case 'field-derive': {
      return [{ key: 'name', label: '字段名称', source: true }];
    }
    case 'field-split': {
      return [
        { key: 'name', label: '字段名称', placeholder: '请输入字段' },
        { key: 'type', label: '字段类型', options: referenceFieldTypes },
        { key: 'length', label: '字段长度', placeholder: '请输入字段长度' },
        { key: 'precision', label: '字段精度', placeholder: '请输入字段精度' },
        { key: 'format', label: '格式', placeholder: '请输入格式' },
        { key: 'trimType', label: '去除空格类型', options: trims, placeholder: '请选择' },
      ];
    }
    case 'java-script': {
      return [
        { key: 'name', label: '字段名称' },
        {
          key: 'dataType',
          label: '字段类型',
          options: choices(
            'varchar',
            'int',
            'bigint',
            'decimal',
            'double',
            'boolean',
            'datetime',
            'timestamp',
          ),
        },
        { key: 'length', label: '字段长度' },
        { key: 'scale', label: '小数精度' },
      ];
    }
    case 'number-range': {
      return [
        { key: 'min', label: '下限' },
        { key: 'max', label: '上界' },
        { key: 'value', label: '值' },
      ];
    }
    case 'sort-records': {
      return [
        { key: 'field', label: '字段名称', source: true },
        {
          key: 'order',
          label: '排序规则',
          options: [
            { label: '升序', value: 'ASC' },
            { label: '降序', value: 'DESC' },
          ],
        },
      ];
    }
    case 'value-map': {
      return [
        { key: 'from', label: '原值', placeholder: '请输入原值' },
        { key: 'to', label: '目标值', placeholder: '请输入目标值' },
      ];
    }
    default: {
      return [];
    }
  }
});
watch(
  () => [props.config, props.toolCode, props.disabled],
  () => {
    errors.value = [];
    if (props.disabled) return;
    let defaults: Row = {};
    if (props.toolCode === 'id-generator')
      defaults = { mode: 'SEQUENCE', start: '1', step: '1', max: '0' };
    else if (props.toolCode === 'field-split')
      defaults = { splitType: 'DELIMITER' };
    else if (props.toolCode === 'value-map') defaults = { unmatched: 'KEEP' };
    else if (props.toolCode === 'field-derive')
      defaults = {
        operateType: 'CONCAT',
        beginIndex: 1,
        length: 0,
        algorithm: 'MD5',
        groupIndex: 1,
      };
    for (const [key, value] of Object.entries(defaults))
      props.config[key] ??= value;
    if (props.toolCode !== 'id-generator') props.config[rowKey.value] ??= [];
  },
  { immediate: true },
);
function setConfig(key: string, value: unknown) {
  if (props.disabled) return;
  props.config[key] = value;
  if (errors.value.length > 0) validate();
}
function setField(row: Row, key: string, value: unknown) {
  if (props.disabled) return;
  if (props.toolCode === 'field-derive') {
    const fields = [...props.config.sourceFields];
    fields[row.sourceIndex] = value;
    props.config.sourceFields = fields;
  } else row[key] = value;
  if (
    errors.value.length > 0 ||
    ['calculator', 'value-map'].includes(props.toolCode)
  )
    validate();
}
function commitRows(value: Row[]) {
  if (!props.disabled)
    props.config[rowKey.value] =
      props.toolCode === 'field-derive' ? value.map((row) => row.name) : value;
}
function metadata(row: Row, key: string) {
  return (
    props.inputFields?.find((field) => field.name === row.name)?.[
      key as 'dataType' | 'length' | 'scale'
    ] ?? '-'
  );
}
function cellDisabled(row: Row, column: Column) {
  return (
    props.disabled ||
    (column.minArity !== undefined &&
      (referenceCalculations.find((item) => item.value === row.calcType)
        ?.arity ?? 0) < column.minArity)
  );
}
function cellVisible(row: Row, column: Column) {
  return (
    props.toolCode !== 'calculator' ||
    column.minArity === undefined ||
    (referenceCalculations.find((item) => item.value === row.calcType)?.arity ?? 0) >= column.minArity
  );
}
function calculatorValueType(row: Row) {
  if (row.valueType === 'STRING') return 'string';
  if (row.valueType === 'INTEGER' || row.valueType === 'DOUBLE') return 'number';
  return row.valueType ?? '-';
}
function rowErrors() {
  return validateReferenceTransformRows(props.toolCode, rows.value);
}
function add() {
  if (props.disabled) return;
  if (
    props.toolCode === 'field-derive' &&
    ['REGEX_EXTRACT', 'REPLACE', 'SUBSTRING'].includes(
      props.config.operateType,
    ) &&
    rows.value.length > 0
  ) {
    errors.value = ['当前操作只能选择一个来源字段'];
    return;
  }
  if (rows.value.length > 0 && rowErrors().length > 0) {
    errors.value = rowErrors();
    return;
  }
  const defaults: Record<string, Row> = {
    'add-constant': {
      name: '',
      fieldType: 'String',
      value: '',
      setEmpty: false,
    },
    'sort-records': { field: '', order: 'ASC' },
    'field-split': { name: '', type: 'String', trimType: 'NONE' },
    'value-map': { from: '', to: '' },
    'number-range': { min: '', max: '', value: '' },
    calculator: {
      targetField: '',
      calcType: undefined,
      fieldA: '',
      fieldB: '',
      fieldC: '',
      valueType: 'STRING',
    },
  };
  const row = defaults[props.toolCode] ?? { name: '' };
  commitRows([...rows.value, row]);
}
function remove(index: number) {
  if (props.disabled) return;
  if (rows.value.length === 1 && props.toolCode !== 'java-script') {
    errors.value = ['请至少保留一行规则'];
    return;
  }
  const context = props.config;
  const row = rows.value[index];
  Modal.confirm({
    title: '确定要删除这条数据吗？',
    okText: '确定',
    cancelText: '取消',
    onOk: () => {
      if (!props.disabled && props.config === context)
        commitRows(rows.value.filter((item) => item !== row));
    },
  });
}
function move(from: number, to: number) {
  if (!props.disabled) commitRows(moveReferenceField(rows.value, from, to));
}
function getFields(mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll') {
  if (props.disabled) return;
  const current = rows.value.map((row) => ({
    ...row,
    name: row.name ?? row.field ?? row.targetField,
  }));
  const merged = resolveReferenceFields(
    current,
    [],
    props.inputFields ?? [],
    mode,
  );
  if (props.toolCode === 'field-derive') {
    if (
      ['REGEX_EXTRACT', 'REPLACE', 'SUBSTRING'].includes(
        props.config.operateType,
      ) &&
      merged.length > 1
    ) {
      errors.value = ['当前操作只能选择一个来源字段，请手动选择'];
      return;
    }
    commitRows(merged);
    return;
  }
  if (props.toolCode === 'calculator') {
    const currentByName = new Map(
      rows.value.map((row) => [row.targetField, row]),
    );
    commitRows(
      merged.map((field) =>
        mode === 'addNewOnly' && currentByName.has(field.name)
          ? { ...currentByName.get(field.name) }
          : {
              targetField: field.name,
              fieldA: field.name,
              calcType: undefined,
              fieldB: '',
              fieldC: '',
              valueType: 'STRING',
            },
      ),
    );
    return;
  }
  commitRows(
    merged.map((field) =>
      props.toolCode === 'java-script'
        ? { ...field, dataType: (field as Row).dataType ?? field.type }
        : { ...field, field: field.name, order: (field as Row).order ?? 'ASC' },
    ),
  );
}
function stepSequence(key: string, direction: number) {
  if (props.disabled) return;
  const current = props.config[key] ?? '0';
  if (typeof current === 'number' && !Number.isSafeInteger(current)) return;
  if (!/^[+-]?[0-9]+$/.test(String(current))) return;
  const next = BigInt(String(current)) + BigInt(direction);
  if (next < -9_223_372_036_854_775_808n || next > 9_223_372_036_854_775_807n) return;
  setConfig(key, next.toString());
}
function validate() {
  let result: string[] = [];
  if (props.toolCode === 'id-generator')
    result = validateReferenceSequence(props.config);
  else if (props.toolCode === 'field-derive')
    result = validateReferenceDerive(props.config);
  else if (props.toolCode !== 'java-script' || rows.value.length > 0)
    result = rowErrors();
  if (unchangedLegacyDuplicates.value)
    result = result.filter((error) => !duplicateError(error));
  if (props.toolCode === 'java-script' && !props.config.code?.trim())
    result.push('请填写脚本代码');
  if (
    ['field-split', 'number-range', 'value-map'].includes(props.toolCode) &&
    !props.config.sourceField?.trim()
  )
    result.push('请选择输入字段');
  if (
    props.toolCode === 'field-split' &&
    !(props.config.splitType === 'REGEX'
      ? props.config.pattern
      : props.config.delimiter)
  )
    result.push('请填写分隔符或正则表达式');
  errors.value = result;
  return result.length === 0;
}
const canMergeFields = computed(() => ['calculator', 'field-derive', 'sort-records'].includes(props.toolCode));
defineExpose({ validate, openFieldMerge, canMergeFields });
</script>

<template>
  <div
    class="qi-reference-editor qi-transform-reference"
    :data-tool-code="toolCode"
  >
    <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    <Alert
      v-if="unchangedLegacyDuplicates"
      type="warning"
      :message="`历史规则存在重复，按原有顺序保留；只有规则完全未改变时允许保存。${originalDuplicateErrors.join('；')}`"
    />
    <template v-if="toolCode === 'java-script'">
      <fieldset :disabled="disabled">
        <JavaScriptEditor v-if="!disabled" :config="config" />
        <pre v-else class="qi-script-input">{{ config.code }}</pre>
      </fieldset>
      <Alert
        type="info"
        message="输出字段为人工声明，仅用于字段提示；脚本实际输出需执行验证，声明始终视为不完整。"
      />
    </template>
    <template v-if="toolCode === 'id-generator'">
      <div class="qi-reference-form-grid">
        <div class="qi-reference-form-row">
          <label class="qi-id-required">序列字段名称 <Tooltip title="会将自动生成的序列输出到这一列"><span class="qi-id-field-help" tabindex="0" aria-label="序列字段名称说明"><ReferenceIcon name="info" /></span></Tooltip></label><Input
            :value="config.targetField"
            :disabled="disabled"
            placeholder="请输入序列字段名称"
            aria-label="序列字段名称"
            aria-required="true"
            @update:value="(value) => setConfig('targetField', value)"
          />
        </div>
        <div class="qi-reference-form-row">
          <label>ID生成方式</label>
          <div class="qi-reference-radio-options qi-id-generation-modes" role="radiogroup" aria-label="ID生成方式">
            <label
              v-for="choice in [{ label: '增加序列', value: 'SEQUENCE' }, { label: '增加UUID', value: 'UUID' }]"
              :key="choice.value"
            ><input
                type="radio"
                name="qi-id-generate-mode"
                :value="choice.value"
                :checked="config.mode === choice.value"
                :disabled="disabled"
                @change="setConfig('mode', choice.value)"
              />{{ choice.label }}</label>
          </div>
        </div>
      </div>
      <div
        v-if="config.mode !== 'UUID'"
        class="qi-reference-form-grid qi-sequence-values"
      >
        <div
          v-for="item in [
            { key: 'start', label: '起始值' },
            { key: 'step', label: '递增幅度' },
            { key: 'max', label: '最大值' },
          ]"
          :key="item.key"
          class="qi-reference-form-row"
        >
          <label class="qi-id-required">{{ item.label }}</label><div class="qi-reference-long-input">
            <button type="button" :disabled="disabled" :aria-label="`减少${item.label}`" @click="stepSequence(item.key, -1)">−</button><Input
              :value="config[item.key]"
              :disabled="disabled"
              :placeholder="`请输入${item.label}`"
              :aria-label="item.label"
              aria-required="true"
              inputmode="numeric"
              @update:value="(value) => setConfig(item.key, value)"
            /><button type="button" :disabled="disabled" :aria-label="`增加${item.label}`" @click="stepSequence(item.key, 1)">+</button>
          </div>
        </div>
      </div>
      <slot name="description"></slot>
      <details v-if="config.mode !== 'UUID'" class="qi-id-sequence-help">
        <summary>序列说明</summary>
        <p class="qi-reference-notice">
          最大值为0表示不限；递增序列按下方确定顺序生成。
        </p>
      </details>
    </template>
    <template v-else>
      <template v-if="toolCode === 'field-derive'">
        <div class="qi-reference-form-grid">
        <div class="qi-reference-form-row">
          <label>操作类型</label><Select
            :value="config.operateType"
            :disabled="disabled"
            :options="deriveModes"
            @update:value="(value) => setConfig('operateType', value)"
          />
        </div>
        <div class="qi-reference-form-row">
          <label>新增字段名称</label><Input
            :value="config.targetField"
            :disabled="disabled"
            @update:value="(value) => setConfig('targetField', value)"
          />
        </div>
        <div
          v-for="parameter in deriveParameters"
          :key="parameter.key"
          class="qi-reference-form-row"
        >
          <label>{{ parameter.label }}</label><InputNumber
            v-if="parameter.number"
            :value="config[parameter.key]"
            :disabled="disabled"
            :precision="0"
            :min="parameter.key === 'beginIndex' ? 1 : 0"
            @update:value="(value) => setConfig(parameter.key, value)"
          /><Input
            v-else
            :value="config[parameter.key]"
            :disabled="disabled"
            @update:value="(value) => setConfig(parameter.key, value)"
          />
        </div>
        <label v-if="config.operateType === 'REPLACE'"><Checkbox
            :checked="config.useRegex"
            :disabled="disabled"
            @update:checked="(value) => setConfig('useRegex', value)"
          />按正则表达式匹配</label>
        <div
          v-if="config.operateType === 'EXPRESSION'"
          class="qi-reference-form-row"
        >
          <label>表达式</label><TextArea
            :value="config.expression"
            :disabled="disabled"
            :rows="3"
            @update:value="(value) => setConfig('expression', value)"
          />
        </div>
        <div v-if="config.operateType === 'HASH'" class="qi-reference-form-row">
          <label>哈希算法</label><Select
            :value="config.algorithm"
            :disabled="disabled"
            :options="choices('MD5', 'SHA1', 'SHA256')"
            @update:value="(value) => setConfig('algorithm', value)"
          />
        </div>
        </div>
        <slot name="description"></slot>
      </template>
      <template
        v-if="['value-map', 'number-range', 'field-split'].includes(toolCode)"
      >
        <div class="qi-reference-form-grid">
        <div class="qi-reference-form-row">
          <label :class="{ 'qi-field-split-required': toolCode === 'field-split' }">{{
            toolCode === 'field-split'
              ? '需要拆分的字段'
              : toolCode === 'value-map'
                ? '使用字段'
                : '输入字段'
          }}</label><AutoComplete
            :value="config.sourceField"
            :disabled="disabled"
            :options="sourceOptions"
            :placeholder="toolCode === 'field-split' ? '请输入拆分的字段' : toolCode === 'value-map' ? '请选择字段名称' : undefined"
            :aria-label="toolCode === 'field-split' ? '需要拆分的字段' : toolCode === 'value-map' ? '使用字段' : '输入字段'"
            :aria-required="toolCode === 'field-split' ? true : undefined"
            @update:value="(value) => setConfig('sourceField', value)"
          >
            <template v-if="toolCode === 'field-split'" #suffixIcon><span class="qi-field-split-chevron" aria-hidden="true"></span></template>
            <template v-else-if="toolCode === 'value-map'" #suffixIcon><span class="qi-value-map-chevron" aria-hidden="true"></span></template>
          </AutoComplete>
        </div>
        <div v-if="toolCode === 'field-split'" class="qi-reference-form-row">
          <label class="qi-field-split-required">拆分类型</label><div class="qi-reference-radio-options" role="radiogroup" aria-label="拆分类型" aria-required="true"><label v-for="choice in [{ value: 'DELIMITER', label: '分隔符' }, { value: 'REGEX', label: '正则表达式' }]" :key="choice.value"><input type="radio" name="qi-field-split-mode" :value="choice.value" :checked="config.splitType === choice.value" :disabled="disabled" @change="setConfig('splitType', choice.value)" />{{ choice.label }}</label></div>
        </div>
        <div v-if="toolCode !== 'field-split'" class="qi-reference-form-row">
          <label>{{ toolCode === 'value-map' ? '目标字段' : '输出字段' }}</label><Input
            :value="config.targetField"
            :disabled="disabled"
            :placeholder="toolCode === 'value-map' ? '请输入目标字段' : '留空覆盖输入字段'"
            :aria-label="toolCode === 'value-map' ? '目标字段' : '输出字段'"
            @update:value="(value) => setConfig('targetField', value)"
          />
        </div>

        <div v-if="toolCode === 'field-split'" class="qi-reference-form-row">
          <label class="qi-field-split-required">{{
            config.splitType === 'REGEX' ? '正则表达式' : '分隔符'
          }}</label><Input
            :value="
              config.splitType === 'REGEX' ? config.pattern : config.delimiter
            "
            :disabled="disabled"
            :placeholder="config.splitType === 'REGEX' ? '请输入正则表达式' : '请输入分隔符'"
            :aria-label="config.splitType === 'REGEX' ? '正则表达式' : '分隔符'"
            aria-required="true"
            @update:value="
              (value) =>
                setConfig(
                  config.splitType === 'REGEX' ? 'pattern' : 'delimiter',
                  value,
                )
            "
          />
        </div>
        <div v-if="toolCode === 'field-split'" class="qi-reference-form-row">
          <label>包裹字符 <Tooltip title="拆分后去除每段两端的包裹字符，再按字段设置去除空格；包裹字符内的分隔符仍参与拆分。"><span class="qi-field-split-help" tabindex="0" aria-label="包裹字符说明"><ReferenceIcon name="info" /></span></Tooltip></label><Input
            :value="config.wrapper"
            :disabled="disabled"
            placeholder="请输入包裹字符"
            aria-label="包裹字符"
            @update:value="(value) => setConfig('wrapper', value)"
          />
        </div>
        <div v-if="toolCode === 'number-range'" class="qi-reference-form-row">
          <label>缺省值</label><Input
            :value="config.defaultValue"
            :disabled="disabled"
            @update:value="(value) => setConfig('defaultValue', value)"
          />
        </div>
        </div>
      </template>
      <div
        v-if="toolCode === 'value-map'"
        class="qi-reference-form-row qi-value-map-default"
      >
        <label>不匹配时的默认值 <Tooltip title="若不填写时，则使用原值"><span class="qi-value-map-help" tabindex="0" aria-label="不匹配时的默认值说明"><ReferenceIcon name="info" /></span></Tooltip></label><Input
          :value="config.defaultValue"
          :disabled="disabled || config.unmatched === 'SET_NULL'"
          placeholder="请选择不匹配时的默认值"
          aria-label="不匹配时的默认值"
          @update:value="(value) => { setConfig('defaultValue', value); setConfig('unmatched', String(value ?? '').length ? 'DEFAULT' : 'KEEP'); }"
        />
      </div>
      <details v-if="toolCode === 'value-map'" class="qi-transform-advanced">
        <summary>未匹配处理方式</summary>
        <div class="qi-reference-form-row">
          <label>未命中处理</label><Select
            :value="config.unmatched"
            :disabled="disabled"
            :options="[
              { value: 'KEEP', label: '保留原值' },
              { value: 'DEFAULT', label: '设为默认值' },
              { value: 'SET_NULL', label: '置空' },
            ]"
            @update:value="(value) => setConfig('unmatched', value)"
          /><Input
            v-if="config.unmatched === 'DEFAULT'"
            :value="config.defaultValue"
            :disabled="disabled"
            placeholder="不匹配时的默认值"
            @update:value="(value) => setConfig('defaultValue', value)"
          />
        </div>
      </details>
      <div
        v-if="showRows"
        class="qi-reference-section-header qi-reference-table-heading"
      >
        <h3>{{ sectionTitle }}</h3>
        <div>
          <Button
            :disabled="disabled"
            class="qi-reference-button"
            @click="add"
          >
            <ReferenceIcon name="plus" />新增
          </Button>
        </div>
      </div>
      <div v-if="showRows" class="qi-reference-fields-scroll">
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>序号</th>
              <th v-for="column in primaryColumns" :key="column.key">
                {{ column.label }}
                <Tooltip v-if="column.help" :title="column.help"><span class="qi-transform-column-help" tabindex="0" :aria-label="`${column.label}说明`"><ReferenceIcon name="info" /></span></Tooltip>
              </th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(row, index) in rows"
              :key="index"
              :draggable="!disabled"
              :tabindex="disabled ? undefined : 0"
              @dragstart="dragged = index"
              @dragover.prevent
              @drop.prevent="move(dragged, index)"
              @keydown.alt.up.prevent="move(index, index - 1)"
              @keydown.alt.down.prevent="move(index, index + 1)"
            >
              <td>{{ index + 1 }}</td>
              <td v-for="column in primaryColumns" :key="column.key">
                <Select
                  v-if="column.check"
                  :value="row[column.key] === undefined ? undefined : row[column.key] ? 1 : 0"
                  :disabled="disabled"
                  :aria-label="`${column.label} ${index + 1}`"
                  :options="[
                    { label: '是', value: 1 },
                    { label: '否', value: 0 },
                  ]"
                  @update:value="(value) => setField(row, column.key, value === 1)"
                /><div v-else-if="toolCode === 'calculator' && column.key === 'valueType'" v-show="Boolean(row.calcType)" class="qi-calculator-type">
                  <span class="qi-calculator-type-value">{{ calculatorValueType(row) }}</span>
                  <details class="qi-transform-cell-options"><summary>调整类型</summary><Select :value="row.valueType" :disabled="disabled" :options="column.options" :aria-label="`值类型 ${index + 1}`" @update:value="(value) => setField(row, 'valueType', value)" /><p>更换计算类型不会自动修改当前值类型。</p></details>
                </div><Select
                  v-else-if="column.options"
                  :value="row[column.key] ?? (toolCode === 'field-split' && column.key === 'type' ? 'String' : undefined)"
                  :disabled="disabled"
                  :options="column.options"
                  :placeholder="column.placeholder"
                  :aria-label="`${column.label} ${index + 1}`"
                  allow-clear
                  @update:value="(value) => setField(row, column.key, value)"
                /><AutoComplete
                  v-else-if="column.source"
                  v-show="cellVisible(row, column)"
                  :value="row[column.key]"
                  :placeholder="column.placeholder"
                  :aria-label="`${column.label} ${index + 1}`"
                  :disabled="cellDisabled(row, column)"
                  :options="sourceOptions"
                  @update:value="(value) => setField(row, column.key, value)"
                >
                  <template v-if="toolCode === 'calculator'" #suffixIcon><span class="qi-calculator-chevron" aria-hidden="true"></span></template>
                </AutoComplete><Input
                  v-else
                  :value="row[column.key]"
                  :placeholder="column.placeholder"
                  :aria-label="`${column.label} ${index + 1}`"
                  :disabled="
                    disabled ||
                    (column.key === 'value' && row.setEmpty === true)
                  "
                  @update:value="(value) => setField(row, column.key, value)"
                />
              </td>
              <td>
                <Button
                  :disabled="disabled"
                  type="link"
                  danger
                  @click="remove(index)"
                >
                  <ReferenceIcon name="delete" />删除
                </Button>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td
                :colspan="primaryColumns.length + 2"
                class="qi-reference-empty"
              >
                暂无记录
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <details
        v-if="toolCode === 'field-derive' && showRows"
        class="qi-transform-advanced"
      >
        <summary>来源字段元数据</summary>
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>字段名称</th>
              <th>字段类型</th>
              <th>字段长度</th>
              <th>小数精度</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows" :key="index">
              <td>{{ row.name }}</td>
              <td>{{ metadata(row, 'dataType') }}</td>
              <td>{{ metadata(row, 'length') }}</td>
              <td>{{ metadata(row, 'scale') }}</td>
            </tr>
          </tbody>
        </table>
      </details>
      <details v-if="toolCode === 'field-split'" class="qi-transform-advanced">
<summary>原字段处理</summary><label><Checkbox
          :checked="config.keepSource"
          :disabled="disabled"
          @update:checked="(value) => setConfig('keepSource', value)"
        />保留原字段</label>
</details>
    </template>
    <ReferenceFieldMergeDialog v-model:open="fieldMergeOpen" :existing-count="rows.length" :incoming-count="inputFields?.length ?? 0" :disabled="disabled" @resolve="getFields" />
  </div>
</template>
