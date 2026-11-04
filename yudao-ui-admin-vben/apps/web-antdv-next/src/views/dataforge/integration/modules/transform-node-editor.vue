<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 节点编辑的是设计器独立草稿，确认后才写回画布 */
import { computed, ref, watch } from 'vue';

import {
  Alert,
  AutoComplete,
  Button,
  Input,
  Modal,
  Select,
  TextArea,
  Tooltip,
} from 'antdv-next';

import NativeStreamingWindowEditor from './native-streaming-window-editor.vue';
import { validateStreamingWindow } from './native-streaming-window-schema';
import ReferenceFieldMergeDialog from './reference-field-merge-dialog.vue';
import ReferenceIcon from './reference-icon.vue';
import { moveReferenceField } from './reference-node-schema';
import { validateReferenceTransformRows } from './reference-transform-schema';

import './reference-node-editor.css';
import './reference-transform-layout.css';
import './dedup-reference.css';
import './string-transform-reference.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  engine?: string;
  inputFields?: { dataType?: string; name?: string; type?: string }[];
  mode?: string;
  toolCode: string;
}>();
const fields = computed(() =>
  (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({ value: field.name!, label: field.name! })),
);
const errors = ref<string[]>([]);
const stringTransform = computed(() =>
  ['string-operation', 'string-replace'].includes(props.toolCode),
);
const ruleTableReference = computed(() =>
  stringTransform.value || props.toolCode === 'set-field-value',
);
const fieldMergeOpen = ref(false);
function openFieldMerge() {
  if (!props.disabled && fields.value.length > 0) fieldMergeOpen.value = true;
}
const sectionTitle = computed(
  () =>
    ({
      'dedup-records': '字段',
      'string-operation': '需要操作的字段',
      'string-replace': '需要替换的字段',
      'set-field-value': '字段',
    })[props.toolCode] ?? '字段',
);
const dedupRows = computed<string[]>(() =>
  Array.isArray(props.config.keyFields) ? props.config.keyFields : [],
);
function isStringField(name: string) {
  const field = props.inputFields?.find((item) => item.name === name);
  return !!field && /string|varchar|char|text/i.test(field.dataType ?? field.type ?? '');
}
function caseIgnored(name: string) {
  return Array.isArray(props.config.ignoreCaseFields)
    ? props.config.ignoreCaseFields.includes(name)
    : props.config.ignoreCase === true && isStringField(name);
}
function setFieldCase(name: string, value: boolean) {
  if (props.disabled || !isStringField(name)) return;
  let existing = props.config.ignoreCaseFields;
  if (!Array.isArray(existing)) existing = props.config.ignoreCase === true ? dedupRows.value.filter(isStringField) : [];
  const selected = new Set<string>(existing);
  if (value) selected.add(name); else selected.delete(name);
  props.config.ignoreCaseFields = dedupRows.value.filter((key) => isStringField(key) && selected.has(key));
}
function normalizeCaseFields() {
  if (Array.isArray(props.config.ignoreCaseFields))
    props.config.ignoreCaseFields = props.config.ignoreCaseFields.filter((name: string) => dedupRows.value.includes(name) && isStringField(name));
}
function setDedupField(index: number, value: unknown) {
  if (props.disabled) return;
  const rows = [...dedupRows.value];
  rows[index] = String(value ?? '');
  props.config.keyFields = rows;
  normalizeCaseFields();
}
function addDedupField() {
  if (props.disabled) return;
  if (
    dedupRows.value.some((name) => !name.trim()) ||
    new Set(dedupRows.value).size !== dedupRows.value.length
  ) {
    errors.value = ['请选择不重复的去重字段'];
    return;
  }
  const available = fields.value.find(
    (field) => !dedupRows.value.includes(field.value),
  );
  props.config.keyFields = [...dedupRows.value, available?.value ?? ''];
}
function removeDedupField(index: number) {
  if (props.disabled) return;
  if (dedupRows.value.length === 1) {
    errors.value = ['请至少保留一行规则'];
    return;
  }
  const context = props.config;
  const name = dedupRows.value[index];
  Modal.confirm({
    title: '确定要删除这条数据吗？',
    okText: '确定',
    cancelText: '取消',
    onOk: () => {
      if (!props.disabled && props.config === context) {
        props.config.keyFields = dedupRows.value.filter(
          (field, position) => field !== name || position !== index,
        );
        normalizeCaseFields();
      }
    },
  });
}
function moveDedup(from: number, to: number) {
  if (!props.disabled)
    props.config.keyFields = moveReferenceField(dedupRows.value, from, to);
}

let dragged = -1;
const trims = [
  { label: '不去空格', value: 'NONE' },
  { label: '去除左侧空格', value: 'LEFT' },
  { label: '去除右侧空格', value: 'RIGHT' },
  { label: '去除两侧空格', value: 'BOTH' },
];
const cases = [
  { label: '不转换', value: 'NONE' },
  { label: '小写', value: 'LOWER' },
  { label: '大写', value: 'UPPER' },
];
const pads = [
  { label: '不填充', value: 'NONE' },
  { label: '填充左侧', value: 'LEFT' },
  { label: '填充右侧', value: 'RIGHT' },
  { label: '填充两侧', value: 'BOTH' },
];
const yesNo = [
  { label: '是', value: 1 },
  { label: '否', value: 0 },
];
watch(
  () => [props.config, props.toolCode, props.disabled],
  () => {
    errors.value = [];
    if (props.disabled) return;
    if (props.toolCode === 'dedup-records') {
      props.config.enableDeduplication = true;
      props.config.deduplicationStrategy ??= 'KEY_FIELDS';
      props.config.keyFields ??= [];
    } else if (props.toolCode === 'custom-token') {
      props.config.code ??= '';
      props.config.returnTokenVariableId ??= '';
    } else props.config.rules ??= [];
  },
  { immediate: true },
);
function blankRule(inputField = '') {
  return {
    inputField,
    outputField: '',
    trim: 'NONE',
    caseMode: 'NONE',
    padDirection: 'NONE',
    padCharacter: '',
    padLength: 0,
    search: '',
    replacement: '',
    regex: false,
    caseSensitive: true,
    wholeWord: false,
    emptyReplacement: false,
    replacementField: '',
    sourceField: inputField,
    targetField: inputField,
  };
}
function addRule() {
  if (props.disabled) return;
  const rows = props.config.rules ?? [];
  const issues =
    rows.length > 0 ? validateReferenceTransformRows(props.toolCode, rows) : [];
  if (issues.length > 0) {
    errors.value = issues;
    return;
  }
  props.config.rules = [...rows, blankRule()];
}
function getFields(mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll') {
  if (props.disabled) return;
  const names = fields.value.map((field) => field.value);
  if (props.toolCode === 'dedup-records') {
    props.config.keyFields =
      mode === 'addNewOnly'
        ? [...new Set([...(props.config.keyFields ?? []), ...names])]
        : names;
    normalizeCaseFields();
    return;
  }
  const rows = props.config.rules ?? [];
  const known = new Set(
    rows.map((row: Record<string, string>) =>
      props.toolCode === 'set-field-value' ? row.targetField : row.inputField,
    ),
  );
  props.config.rules =
    mode === 'addNewOnly'
      ? [
          ...rows,
          ...names
            .filter((name) => !known.has(name))
            .map((name) => blankRule(name)),
        ]
      : names.map((name) => blankRule(name));
}
function setConfig(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function setRule(row: Record<string, unknown>, key: string, value: unknown) {
  if (props.disabled) return;
  row[key] = value;
  if (props.toolCode !== 'string-replace') return;
  if (key === 'replacement' && String(value ?? '').length > 0) {
    row.emptyReplacement = false;
    row.replacementField = '';
  } else if (key === 'emptyReplacement' && value === true) {
    row.replacement = '';
    row.replacementField = '';
  } else if (key === 'replacementField' && String(value ?? '').length > 0) {
    row.replacement = '';
    row.emptyReplacement = false;
  } else if (key === 'regex' && value === true) {
    row.wholeWord = false;
    row.caseSensitive = false;
  }
}
function moveRule(from: number, to: number) {
  if (!props.disabled)
    props.config.rules = moveReferenceField(props.config.rules ?? [], from, to);
}
function removeRule(index: number) {
  if (props.disabled) return;
  if (props.config.rules.length === 1) {
    errors.value = ['请至少保留一行规则'];
    return;
  }
  const context = props.config;
  const row = props.config.rules[index];
  Modal.confirm({
    title: '确定要删除这条数据吗？',
    okText: '确定',
    cancelText: '取消',
    onOk: () => {
      if (!props.disabled && props.config === context)
        props.config.rules = props.config.rules.filter(
          (item: unknown) => item !== row,
        );
    },
  });
}
function validate() {
  if (props.toolCode === 'custom-token')
    return (
      !!props.config.code?.trim() &&
      !!props.config.returnTokenVariableId?.trim()
    );
  if (props.toolCode === 'dedup-records') {
    const keys = props.config.keyFields ?? [];
    if (
      ['ALL_FIELDS', 'KEY_FIELDS'].includes(props.config.deduplicationStrategy)
    )
      errors.value =
        props.config.deduplicationStrategy === 'ALL_FIELDS' ||
        (Array.isArray(keys) &&
          keys.length > 0 &&
          keys.every((key) => typeof key === 'string' && key.trim()) &&
          new Set(keys).size === keys.length)
          ? []
          : ['请选择不重复的去重字段'];
    else errors.value = ['请选择去重策略'];
  } else
    errors.value = validateReferenceTransformRows(
      props.toolCode,
      props.config.rules ?? [],
    );
  if (props.mode === 'REALTIME' && props.toolCode === 'dedup-records')
    errors.value.push(...validateStreamingWindow(props.config.streamingWindow));
  return errors.value.length === 0;
}
const canMergeFields = computed(() => props.toolCode !== 'custom-token');
defineExpose({ validate, openFieldMerge, canMergeFields });
</script>

<template>
  <div
    class="qi-transform-config qi-reference-editor qi-transform-reference"
    :class="{ 'qi-dedup-reference': toolCode === 'dedup-records', 'qi-rule-reference': ruleTableReference }"
    :data-tool-code="toolCode"
  >
    <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    <div
      v-if="toolCode !== 'custom-token'"
      class="qi-reference-section-header qi-reference-table-heading"
    >
      <h3>{{ sectionTitle }}</h3>
      <div>
        <Button
          :disabled="disabled"
          class="qi-add-rule"
          :class="{ 'qi-reference-button': toolCode === 'dedup-records' || ruleTableReference }"
          @click="toolCode === 'dedup-records' ? addDedupField() : addRule()"
          >
<ReferenceIcon name="plus" />新增
</Button>
      </div>
    </div>
    <template v-if="toolCode === 'custom-token'">
      <label>返回token变量id
        <Input
          :disabled="disabled"
          :value="config.returnTokenVariableId"
          @update:value="(value) => setConfig('returnTokenVariableId', value)"
          placeholder="请输入返回token变量id"
      /></label>
      <label>Java脚本
        <TextArea
          :disabled="disabled"
          :value="config.code"
          @update:value="(value) => setConfig('code', value)"
          :rows="16"
          class="qi-script-input"
          placeholder="请输入认证脚本；返回值写入指定流程变量"
      /></label>
    </template>
    <template v-else-if="toolCode === 'dedup-records'">
      <div class="qi-reference-fields-scroll">
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名称</th>
              <th>忽略大小写</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(name, index) in dedupRows"
              :key="index"
              :draggable="!disabled"
              :tabindex="disabled ? undefined : 0"
              @dragstart="dragged = index"
              @dragover.prevent
              @drop.prevent="moveDedup(dragged, index)"
              @keydown.alt.up.prevent="moveDedup(index, index - 1)"
              @keydown.alt.down.prevent="moveDedup(index, index + 1)"
            >
              <td>
                <span class="qi-dedup-sequence">
                  <ReferenceIcon name="field-drag-ref" />{{ index + 1 }}
                </span>
              </td>
              <td>
                <AutoComplete
                  :value="name"
                  :options="fields"
                  :disabled="disabled"
                  placeholder="请选择字段"
                  :aria-label="`第${index + 1}行去重字段`"
                  @update:value="(value) => setDedupField(index, value)"
                >
                  <template #suffixIcon>
                    <span class="qi-dedup-select-arrow" aria-hidden="true"></span>
                  </template>
                </AutoComplete>
              </td>
              <td>
                <Select
                  :value="caseIgnored(name) ? 1 : 0"
                  :disabled="disabled || !isStringField(name)"
                  :options="[
                    { label: '是', value: 1 },
                    { label: '否', value: 0 },
                  ]"
                  :aria-label="`${name || '当前字段'}忽略大小写`"
                  @update:value="(value) => setFieldCase(name, value === 1)"
                />
              </td>
              <td>
                <Button
                  :disabled="disabled"
                  type="link"
                  danger
                  :aria-label="`删除第${index + 1}行去重字段`"
                  @click="removeDedupField(index)"
                  >
<ReferenceIcon name="delete" />删除
</Button>
              </td>
            </tr>
            <tr v-if="!dedupRows.length">
              <td colspan="4" class="qi-reference-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template v-else>
      <div class="qi-transform-table">
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>
                {{ toolCode === 'set-field-value' ? '字段名称' : '输入字段' }}
              </th>
              <th>
                {{
                  toolCode === 'set-field-value' ? '替换字段名称' : '输出字段'
                }}
              </th>
              <template v-if="toolCode === 'string-operation'">
                <th>去空格方式</th>
                <th>大小写转换</th>
                <th>填充方向</th>
                <th>填充字符 <Tooltip title="如果使用填充功能，指定填充所使用的字符。填充长度在下方配置。"><span class="qi-string-help" tabindex="0" aria-label="填充字符说明"><ReferenceIcon name="info" /></span></Tooltip></th>
</template><template v-if="toolCode === 'string-replace'">
                <th>使用正则表达式</th>
                <th>搜索</th>
                <th>替换成的内容</th>
                <th>设置为空串 <Tooltip title="选择是时使用空字符串替换匹配内容。"><span class="qi-string-help" tabindex="0" aria-label="设置为空串说明"><ReferenceIcon name="info" /></span></Tooltip></th>
                <th>使用字段值替换 <Tooltip title="使用所选字段的值替换匹配内容；与替换成的内容、设置为空串互斥。"><span class="qi-string-help" tabindex="0" aria-label="使用字段值替换说明"><ReferenceIcon name="info" /></span></Tooltip></th>
                <th>整个单词匹配 <Tooltip title="仅替换完整单词的匹配，单词边界按Unicode字符判断。"><span class="qi-string-help" tabindex="0" aria-label="整个单词匹配说明"><ReferenceIcon name="info" /></span></Tooltip></th>
                <th>大小写敏感 <Tooltip title="选择是时区分大小写进行匹配。"><span class="qi-string-help" tabindex="0" aria-label="大小写敏感说明"><ReferenceIcon name="info" /></span></Tooltip></th>
              </template>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(rule, index) in config.rules"
              :key="index"
              :draggable="!disabled"
              :tabindex="disabled ? undefined : 0"
              @dragstart="dragged = Number(index)"
              @dragover.prevent
              @drop.prevent="moveRule(dragged, Number(index))"
              @keydown.alt.up.prevent="
                moveRule(Number(index), Number(index) - 1)
              "
              @keydown.alt.down.prevent="
                moveRule(Number(index), Number(index) + 1)
              "
            >
              <td><span v-if="ruleTableReference" class="qi-string-sequence"><ReferenceIcon name="field-drag-ref" />{{ Number(index) + 1 }}</span><template v-else>{{ Number(index) + 1 }}</template></td>
              <td>
                <AutoComplete
                  :disabled="disabled"
                  v-if="toolCode === 'set-field-value'"
                  :options="fields"
                  :value="rule.targetField"
                  :aria-label="`第${Number(index) + 1}行字段名称`"
                  @update:value="(value) => setRule(rule, 'targetField', value)"
                  placeholder="字段名称"
                >
                  <template #suffixIcon>
                    <span class="qi-string-select-arrow" aria-hidden="true"></span>
                  </template>
                </AutoComplete><AutoComplete
                  :disabled="disabled"
                  v-else
                  :options="fields"
                  :value="rule.inputField"
                  :aria-label="`第${Number(index) + 1}行输入字段`"
                  @update:value="(value) => setRule(rule, 'inputField', value)"
                  placeholder="输入字段"
                >
                  <template v-if="stringTransform" #suffixIcon>
                    <span class="qi-string-select-arrow" aria-hidden="true"></span>
                  </template>
                </AutoComplete>
              </td>
              <td>
                <AutoComplete
                  :disabled="disabled"
                  v-if="toolCode === 'set-field-value'"
                  :options="fields"
                  :value="rule.sourceField"
                  :aria-label="`第${Number(index) + 1}行替换字段名称`"
                  @update:value="(value) => setRule(rule, 'sourceField', value)"
                  placeholder="请选择字段"
                >
                  <template #suffixIcon>
                    <span class="qi-string-select-arrow" aria-hidden="true"></span>
                  </template>
                </AutoComplete><Input
                  :disabled="disabled"
                  v-else
                  :value="rule.outputField"
                  :aria-label="`第${Number(index) + 1}行输出字段`"
                  @update:value="(value) => setRule(rule, 'outputField', value)"
                  :placeholder="stringTransform ? '请输入' : '留空覆盖输入字段'"
                  :title="stringTransform ? '留空覆盖输入字段' : undefined"
                />
              </td>
              <template v-if="toolCode === 'string-operation'">
                <td>
                  <Select
                    :disabled="disabled"
                    :value="rule.trim"
                    :aria-label="`第${Number(index) + 1}行去空格方式`"
                    placeholder="请选择"
                    @update:value="(value) => setRule(rule, 'trim', value)"
                    :options="trims"
                  />
                </td>
                <td>
                  <Select
                    :disabled="disabled"
                    :value="rule.caseMode"
                    :aria-label="`第${Number(index) + 1}行大小写转换`"
                    placeholder="请选择"
                    @update:value="(value) => setRule(rule, 'caseMode', value)"
                    :options="cases"
                  />
                </td>
                <td>
                  <Select
                    :disabled="disabled"
                    :value="rule.padDirection"
                    :aria-label="`第${Number(index) + 1}行填充方向`"
                    placeholder="请选择"
                    @update:value="
                      (value) => setRule(rule, 'padDirection', value)
                    "
                    :options="pads"
                  />
                </td>
                <td>
                  <Input
                    :disabled="
                      disabled ||
                      !rule.padDirection ||
                      rule.padDirection === 'NONE'
                    "
                    :value="rule.padCharacter"
                    :aria-label="`第${Number(index) + 1}行填充字符`"
                    placeholder="请输入"
                    @update:value="
                      (value) => setRule(rule, 'padCharacter', value)
                    "
                  />
                </td>
</template><template v-if="toolCode === 'string-replace'">
                <td>
                  <Select
                    :disabled="disabled"
                    :value="rule.regex ? 1 : 0"
                    :options="yesNo"
                    :aria-label="`第${Number(index) + 1}行使用正则表达式`"
                    @update:value="(value) => setRule(rule, 'regex', value === 1)"
                  />
                </td>
                <td>
                  <Input
                    :disabled="disabled"
                    :value="rule.search"
                    :aria-label="`第${Number(index) + 1}行搜索`"
                    placeholder="请输入"
                    @update:value="(value) => setRule(rule, 'search', value)"
                  />
                </td>
                <td>
                  <Input
                    :value="rule.replacement"
                    :aria-label="`第${Number(index) + 1}行替换成的内容`"
                    placeholder="请输入"
                    @update:value="
                      (value) => setRule(rule, 'replacement', value)
                    "
                    :disabled="disabled || rule.emptyReplacement"
                  />
                </td>
                <td>
                  <Select
                    :disabled="disabled"
                    :value="rule.emptyReplacement ? 1 : 0"
                    :options="yesNo"
                    :aria-label="`第${Number(index) + 1}行设置为空串`"
                    @update:value="
                      (value) => setRule(rule, 'emptyReplacement', value === 1)
                    "
                  />
                </td>
                <td>
                  <AutoComplete
                    :disabled="disabled"
                    :options="fields"
                    placeholder="请选择"
                    :value="rule.replacementField"
                    :aria-label="`第${Number(index) + 1}行使用字段值替换`"
                    @update:value="
                      (value) => setRule(rule, 'replacementField', value)
                    "
                  >
                    <template #suffixIcon>
                      <span class="qi-string-select-arrow" aria-hidden="true"></span>
                    </template>
                  </AutoComplete>
                </td>
                <td>
                  <Select
                    :disabled="disabled"
                    :value="rule.wholeWord ? 1 : 0"
                    :options="yesNo"
                    :aria-label="`第${Number(index) + 1}行整个单词匹配`"
                    @update:value="
                      (value) => setRule(rule, 'wholeWord', value === 1)
                    "
                  />
                </td>
                <td>
                  <Select
                    :disabled="disabled"
                    :value="rule.caseSensitive === false ? 0 : 1"
                    :options="yesNo"
                    :aria-label="`第${Number(index) + 1}行大小写敏感`"
                    @update:value="
                      (value) => setRule(rule, 'caseSensitive', value === 1)
                    "
                  />
                </td>
              </template>
              <td>
                <Button
                  danger
                  type="link"
                  aria-label="删除规则"
                  :disabled="disabled"
                  @click="removeRule(Number(index))"
                >
                  <ReferenceIcon name="delete" />
                  <template v-if="ruleTableReference">删除</template>
                </Button>
              </td>
            </tr>
            <tr v-if="ruleTableReference && !config.rules?.length">
              <td :colspan="toolCode === 'string-operation' ? 8 : toolCode === 'string-replace' ? 11 : 4" class="qi-reference-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <NativeStreamingWindowEditor v-if="mode === 'REALTIME' && toolCode === 'dedup-records'" :config="config" :disabled="disabled" />
    <ReferenceFieldMergeDialog v-model:open="fieldMergeOpen" :existing-count="toolCode === 'dedup-records' ? dedupRows.length : (config.rules?.length ?? 0)" :incoming-count="fields.length" :disabled="disabled" @resolve="getFields" />
  </div>
</template>

<style scoped>
.qi-transform-config {
  display: grid;
  gap: 18px;
}

.qi-transform-config > label {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
}

.qi-transform-table {
  max-width: 100%;
  overflow: auto;
}

table {
  width: 100%;
  min-width: 800px;
  border-collapse: collapse;
}

th,
td {
  padding: 10px 8px;
  text-align: center;
  border-bottom: 1px solid #e4e7ed;
}

th {
  font-weight: 400;
  white-space: nowrap;
  background: #f1f1f5;
}

td:not(:first-child, :last-child) {
  min-width: 130px;
}

.qi-add-rule {
  justify-self: start;
  color: #2f62ff;
  border-color: #a5baff;
  border-radius: 2px;
}

.qi-script-input {
  font-family: monospace;
}

@media (max-width: 768px) {
  .qi-transform-config > label {
    grid-template-columns: 1fr;
    gap: 6px;
  }
}
</style>

<style scoped>
.qi-transform-table thead th,
.qi-transform-table tbody td {
  font-size: 12px;
}
</style>
