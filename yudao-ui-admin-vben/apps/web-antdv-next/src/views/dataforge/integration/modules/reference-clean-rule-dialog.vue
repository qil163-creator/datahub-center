<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';
import type {
  CleanMappingItem,
  CleanParameterField,
  CleanSortItem,
} from '#/views/governance/cleanrule/modules/clean-rule-parameters';

import { computed, reactive, ref, watch } from 'vue';

import {
  Button,
  DatePicker,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  Select,
  TextArea,
} from 'antdv-next';

import { createCleanRule } from '#/api/governance/cleanrule';
import {
  cleanParameterFields,
  cleanParameterIssues,
  cleanParameterValue,
  replaceCleanParameter,
} from '#/views/governance/cleanrule/modules/clean-rule-parameters';

import {
  governanceCleanUsesAllFields,
  governanceCleanUsesMultipleFields,
} from '../../etl/pipeline/node-editors/data-clean-governance';

import ReferenceIcon from './reference-icon.vue';

defineOptions({ name: 'IntegrationReferenceCleanRuleDialog' });

interface CleanRuleSelection {
  field?: string;
  fields?: string[];
  ruleId: string;
}

const props = defineProps<{
  inputFields?: DataforgeEtlApi.FieldMeta[];
  open: boolean;
  rule?: GovernanceCleanRuleApi.CleanRule;
}>();
const emit = defineEmits<{
  back: [];
  close: [];
  confirm: [selection: CleanRuleSelection];
}>();

const form = reactive({
  config: {} as Record<string, unknown>,
  description: '',
  field: undefined as string | undefined,
  fields: [] as string[],
  name: '',
  status: 0,
});
const saving = ref(false);
const importInput = ref<HTMLInputElement>();
let sequence = 0;

const strategyKey = computed(() => props.rule?.strategyKey);
const allFields = computed(() => governanceCleanUsesAllFields(props.rule));
const multiple = computed(() => governanceCleanUsesMultipleFields(props.rule));
const fieldOptions = computed(() =>
  (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({ label: field.name!, value: field.name! })),
);
const schema = computed(
  () => cleanParameterFields(strategyKey.value, form.config) ?? [],
);
const custom = computed(
  () =>
    ({
      CHECK_EXPIRATION: ['dataRange', 'dataRangeType', 'dataRangeValue', 'handleType', 'handleColumns', 'handleValue'],
      KEEP_LATEST_OR_FIRST: ['stringValue'],
      MENU_CUSTOM: ['stringValue'],
    })[strategyKey.value ?? ''] ?? [],
);
const genericFields = computed(() =>
  schema.value.filter((field) => !custom.value.includes(field.key)),
);

watch(
  () => [props.open, props.rule] as const,
  ([open]) => {
    if (!open) return;
    ++sequence;
    saving.value = false;
    form.name = '';
    form.status = 0;
    form.description = '';
    form.field = undefined;
    form.fields = [];
    form.config = JSON.parse(JSON.stringify(props.rule?.config ?? {}));
    if (strategyKey.value === 'CHECK_EXPIRATION') {
      form.config.dataRange ??= 0;
      form.config.dataRangeType ??= 1;
      form.config.dataRangeValue ??= 30;
      form.config.handleType ??= 1;
    }
  },
  { immediate: true },
);

const value = (key: string) =>
  cleanParameterValue(form.config, key, strategyKey.value);
function setValue(key: string, next: unknown) {
  const field = schema.value.find((item) => item.key === key);
  if (next === '' && field && !field.required && !field.allowEmpty)
    next = undefined;
  form.config = replaceCleanParameter(form.config, key, next);
}
const textOf = (key: string) =>
  value(key) === undefined || value(key) === null ? undefined : String(value(key));
const numberOf = (key: string) => {
  const raw = value(key);
  return typeof raw === 'number' || typeof raw === 'string' ? raw : undefined;
};
const list = (key: string) =>
  Array.isArray(value(key)) ? (value(key) as string[]) : [];
const mappings = computed<CleanMappingItem[]>(() =>
  Array.isArray(value('stringValue'))
    ? (value('stringValue') as CleanMappingItem[])
    : [],
);
const sorts = computed<CleanSortItem[]>(() =>
  Array.isArray(value('stringValue'))
    ? (value('stringValue') as CleanSortItem[])
    : [],
);
const keepLatest = computed(() => sorts.value.length > 0);

function updateRow(rows: unknown[], index: number, key: string, next: unknown) {
  const copy = rows.map((row) => ({ ...(row as Record<string, unknown>) }));
  copy[index] = { ...copy[index], [key]: next };
  setValue('stringValue', copy);
}
function removeRow(rows: unknown[], index: number) {
  setValue('stringValue', rows.filter((_, position) => position !== index));
}
function addMapping() {
  setValue('stringValue', [...mappings.value, { value: '', name: '' }]);
}
function addSort() {
  setValue('stringValue', [
    ...sorts.value,
    { columns: '', type: 0, sort: sorts.value.length },
  ]);
}
function chooseStrategy(latest: boolean) {
  if (latest) {
    addSort();
    return;
  }
  setValue('stringValue', undefined);
}
async function importMappings(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  const text = await file.text();
  const rows = text
    .split(/\r?\n/u)
    .map((line) => line.split(/[\t,，]/u))
    .filter((cells) => cells.length >= 2 && cells[0]!.trim() !== '');
  const known = new Set(mappings.value.map((item) => item.value));
  const merged = [...mappings.value];
  for (const [from, to] of rows) {
    if (known.has(from!.trim())) continue;
    known.add(from!.trim());
    merged.push({ value: from!.trim(), name: (to ?? '').trim() });
  }
  setValue('stringValue', merged.slice(0, 1000));
}

function stepRange(delta: number) {
  const current = Number(numberOf('dataRangeValue') ?? 0);
  setValue('dataRangeValue', Math.min(100_000, Math.max(0, (Number.isFinite(current) ? current : 0) + delta)));
}
const updateColumns = computed(() => fieldOptions.value);
const relativeUnits = [
  { label: '天前', value: 1 },
  { label: '月前', value: 2 },
  { label: '年前', value: 3 },
];

function validate(): string | undefined {
  if (!form.name.trim()) return '请输入清洗名称';
  if (
    !allFields.value &&
    (multiple.value ? form.fields.length === 0 : !form.field)
  )
    return '请选择清洗字段';
  if (
    strategyKey.value === 'CHECK_EXPIRATION' &&
    Number(form.config.handleType) === 0 &&
    (!textOf('handleColumns') || !textOf('handleValue'))
  )
    return textOf('handleColumns') ? '请输入标记值' : '请选择标记字段';
  return cleanParameterIssues(strategyKey.value, form.config)[0];
}

async function submit() {
  const rule = props.rule;
  if (!rule || saving.value) return;
  const issue = validate();
  if (issue) {
    message.warning(issue);
    return;
  }
  const request = ++sequence;
  saving.value = true;
  try {
    const id = await createCleanRule({
      categoryId: rule.categoryId,
      code: rule.code,
      config: form.config,
      description: form.description.trim() || rule.description,
      example: rule.example,
      name: form.name.trim(),
      ruleLevel: rule.ruleLevel,
      status: form.status,
      strategyKey: rule.strategyKey,
      useCase: rule.useCase,
    });
    if (request !== sequence || !props.open) return;
    const selection: CleanRuleSelection = { ruleId: String(id) };
    if (!allFields.value) {
      if (multiple.value) selection.fields = [...form.fields];
      else selection.field = form.field;
    }
    emit('confirm', selection);
  } finally {
    if (request === sequence) saving.value = false;
  }
}

const count = ({ count, maxLength }: { count: number; maxLength?: number }) =>
  `${count} / ${maxLength ?? 256}个字符`;
const fieldTypeOptions = (field: CleanParameterField) => field.options ?? [];
const selectValue = (field: CleanParameterField) =>
  field.options?.find((option) => String(option.value) === String(value(field.key)))?.value;
</script>

<template>
  <Modal
    :open="open"
    :width="1200"
    :destroy-on-hidden="true"
    :mask-closable="false"
    class="qi-node-modal qi-node-modal-reference qi-clean-rule-dialog"
    wrap-class-name="qi-clean-rule-dialog-wrap"
    @cancel="emit('close')"
  >
    <template #title>新增清洗规则–{{ rule?.name }}</template>
    <div class="qi-clean-rule-form">
      <h3 class="qi-clean-rule-heading">基础信息</h3>
      <div class="qi-clean-rule-row qi-clean-rule-row-3">
        <label class="qi-clean-rule-item"
          ><span class="required">清洗名称</span
          ><Input
            v-model:value="form.name"
            :maxlength="100"
            aria-label="清洗名称"
            placeholder="请输入清洗名称"
        /></label>
        <label class="qi-clean-rule-item"
          ><span>清洗规则编号</span
          ><Input :value="rule?.code" disabled aria-label="清洗规则编号"
        /></label>
        <label class="qi-clean-rule-item"
          ><span>清洗规则名称</span
          ><Input :value="rule?.name" disabled aria-label="清洗规则名称"
        /></label>
      </div>
      <div class="qi-clean-rule-row">
        <label class="qi-clean-rule-item"
          ><span>状态</span
          ><RadioGroup
            v-model:value="form.status"
            :options="[
              { label: '上线', value: 0 },
              { label: '下线', value: 1 },
            ]"
        /></label>
      </div>
      <div class="qi-clean-rule-row">
        <label class="qi-clean-rule-item"
          ><span>规则描述</span
          ><TextArea
            v-model:value="form.description"
            :maxlength="256"
            :rows="4"
            :show-count="{ formatter: count }"
            placeholder="请输入规则描述"
        /></label>
      </div>

      <h3 class="qi-clean-rule-heading">规则配置</h3>
      <div v-if="!allFields" class="qi-clean-rule-row">
        <label class="qi-clean-rule-item"
          ><span class="required">清洗字段</span
          ><Select
            v-if="multiple"
            v-model:value="form.fields"
            :options="fieldOptions"
            mode="multiple"
            aria-label="清洗字段"
            placeholder="请选择清洗字段"
          /><Select
            v-else
            v-model:value="form.field"
            :options="fieldOptions"
            aria-label="清洗字段"
            placeholder="请选择清洗字段"
        /></label>
      </div>

      <!-- 清理过期记录 -->
      <template v-if="strategyKey === 'CHECK_EXPIRATION'">
        <div class="qi-clean-rule-row qi-clean-rule-row-2">
          <label class="qi-clean-rule-item"
            ><span>时间范围</span
            ><RadioGroup
              :value="Number(form.config.dataRange ?? 0)"
              :options="[
                { label: '最近时间范围', value: 0 },
                { label: '具体日期', value: 1 },
              ]"
              @update:value="
                (next) => {
                  setValue('dataRange', next);
                  setValue('dataRangeValue', next === 1 ? undefined : 30);
                }
              "
          /></label>
          <label
            v-if="Number(form.config.dataRange ?? 0) === 0"
            class="qi-clean-rule-item qi-clean-rule-split"
            ><span class="required">维持时间范围</span
            ><span class="qi-clean-rule-pair"
              ><span class="qi-clean-rule-stepper"
                ><button type="button" aria-label="减少" @click="stepRange(-1)">−</button
                ><InputNumber
                  :value="numberOf('dataRangeValue')"
                  :min="0"
                  :max="100000"
                  :precision="0"
                  :controls="false"
                  aria-label="维持时间范围"
                  @update:value="(next) => setValue('dataRangeValue', next)"
                /><button type="button" aria-label="增加" @click="stepRange(1)">+</button></span
              ><Select
                :value="Number(form.config.dataRangeType ?? 1)"
                :options="relativeUnits"
                aria-label="时间单位"
                @update:value="(next) => setValue('dataRangeType', next)" /></span
          ></label>
          <label v-else class="qi-clean-rule-item qi-clean-rule-split"
            ><span class="required">具体日期</span
            ><span class="qi-clean-rule-pair"
              ><DatePicker
                :value="textOf('dataRangeValue')"
                value-format="YYYY-MM-DD"
                aria-label="具体日期"
                @update:value="(next) => setValue('dataRangeValue', next || undefined)"
              /><Select
                value="before"
                :options="[{ label: '之前', value: 'before' }]"
                aria-label="日期方向" /></span
          ></label>
        </div>
        <div class="qi-clean-rule-row qi-clean-rule-row-2">
          <label class="qi-clean-rule-item"
            ><span>处理方式</span
            ><RadioGroup
              :value="Number(form.config.handleType ?? 1)"
              :options="[
                { label: '标记为过期', value: 0 },
                { label: '删除记录', value: 1 },
              ]"
              @update:value="(next) => setValue('handleType', next)"
          /></label>
          <label
            v-if="Number(form.config.handleType ?? 1) === 0"
            class="qi-clean-rule-item qi-clean-rule-split"
            ><span class="required">标记字段</span
            ><Select
              :value="textOf('handleColumns')"
              :options="updateColumns"
              aria-label="标记字段"
              placeholder="请选择清洗字段"
              @update:value="(next) => setValue('handleColumns', next)"
          /></label>
        </div>
        <div
          v-if="Number(form.config.handleType ?? 1) === 0"
          class="qi-clean-rule-row qi-clean-rule-row-2"
        >
          <label class="qi-clean-rule-item"
            ><span class="required">标记值</span
            ><Input
              :value="textOf('handleValue')"
              :maxlength="256"
              aria-label="标记值"
              placeholder="请输入标记值"
              @update:value="(next) => setValue('handleValue', next)"
          /></label>
        </div>
      </template>

      <!-- 枚举值映射标准化 -->
      <template v-else-if="strategyKey === 'MENU_CUSTOM'">
        <div class="qi-clean-rule-actions">
          <Button type="primary" :auto-insert-space="false" @click="addMapping"
            ><ReferenceIcon name="plus" />新增规则</Button
          ><Button
            type="primary"
            :auto-insert-space="false"
            @click="importInput?.click()"
            ><ReferenceIcon name="plus" />导入规则</Button
          ><input
            ref="importInput"
            type="file"
            accept=".csv,.txt"
            hidden
            @change="importMappings"
          />
        </div>
        <table class="qi-clean-rule-table">
          <thead>
            <tr><th>原值</th><th>标准值</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in mappings" :key="index">
              <td>
                <Input
                  :value="item.value"
                  :maxlength="128"
                  :aria-label="`第${index + 1}行原值`"
                  @update:value="(next) => updateRow(mappings, index, 'value', next)"
                />
              </td>
              <td>
                <Input
                  :value="item.name"
                  :maxlength="256"
                  :aria-label="`第${index + 1}行标准值`"
                  @update:value="(next) => updateRow(mappings, index, 'name', next)"
                />
              </td>
              <td>
                <Button type="link" danger @click="removeRow(mappings, index)"
                  ><ReferenceIcon name="delete" />删除</Button
                >
              </td>
            </tr>
            <tr v-if="mappings.length === 0">
              <td colspan="3" class="qi-clean-rule-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- 按组合字段去重 -->
      <template v-else-if="strategyKey === 'KEEP_LATEST_OR_FIRST'">
        <div v-if="keepLatest" class="qi-clean-rule-actions">
          <Button class="qi-tint" :auto-insert-space="false" @click="addSort"
            ><ReferenceIcon name="plus" />新增排序字段</Button
          >
        </div>
        <table v-if="keepLatest" class="qi-clean-rule-table">
          <thead>
            <tr><th>序号</th><th>字段名称</th><th>排序顺序</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="(item, index) in sorts" :key="index">
              <td>{{ index + 1 }}</td>
              <td>
                <Select
                  :value="item.columns || undefined"
                  :options="fieldOptions"
                  :aria-label="`第${index + 1}行排序字段`"
                  placeholder="请选择清洗字段"
                  @update:value="(next) => updateRow(sorts, index, 'columns', next)"
                />
              </td>
              <td>
                <Select
                  :value="item.type"
                  :options="[
                    { label: '降序', value: 0 },
                    { label: '升序', value: 1 },
                  ]"
                  :aria-label="`第${index + 1}行排序顺序`"
                  @update:value="(next) => updateRow(sorts, index, 'type', next)"
                />
              </td>
              <td>
                <Button type="link" danger @click="removeRow(sorts, index)"
                  ><ReferenceIcon name="delete" />删除</Button
                >
              </td>
            </tr>
          </tbody>
        </table>
        <div class="qi-clean-rule-row">
          <label class="qi-clean-rule-item"
            ><span>去重策略</span
            ><RadioGroup
              :value="keepLatest ? 'latest' : 'first'"
              :options="[
                { label: '保留首条记录', value: 'first' },
                { label: '保留最新记录', value: 'latest' },
              ]"
              @update:value="(next) => chooseStrategy(next === 'latest')"
          /></label>
        </div>
      </template>

      <!-- 其余规则：由治理清洗函数参数契约生成表单 -->
      <div
        v-for="field in genericFields"
        :key="field.key"
        class="qi-clean-rule-row"
      >
        <label class="qi-clean-rule-item"
          ><span :class="{ required: field.required }">{{ field.label }}</span>
          <Select
            v-if="field.kind === 'select'"
            :value="selectValue(field)"
            :options="fieldTypeOptions(field)"
            :placeholder="`默认：${fieldTypeOptions(field).find((item) => item.value === field.defaultValue)?.label ?? ''}`"
            allow-clear
            :aria-label="field.label"
            @update:value="(next) => setValue(field.key, next)"
          />
          <RadioGroup
            v-else-if="field.kind === 'boolean'"
            :value="typeof value(field.key) === 'boolean' ? (value(field.key) as boolean) : undefined"
            :options="[
              { label: '是', value: true },
              { label: '否', value: false },
            ]"
            @update:value="(next) => setValue(field.key, next)"
          />
          <InputNumber
            v-else-if="field.kind === 'integer'"
            :value="numberOf(field.key)"
            :min="field.min"
            :max="field.max"
            :precision="0"
            :placeholder="field.defaultValue === undefined ? `请输入${field.min}至${field.max}的整数` : `默认：${field.defaultValue}`"
            :aria-label="field.label"
            @update:value="(next) => setValue(field.key, next)"
          />
          <Select
            v-else-if="field.kind === 'tags'"
            :value="list(field.key)"
            mode="tags"
            :placeholder="`输入${field.label}并回车`"
            :aria-label="field.label"
            @update:value="(next) => setValue(field.key, next)"
          />
          <Input
            v-else
            :value="textOf(field.key)"
            :maxlength="field.maxLength"
            :placeholder="field.defaultValue === undefined ? `请输入${field.label}` : `默认：${field.defaultValue}`"
            :aria-label="field.label"
            @update:value="(next) => setValue(field.key, next)"
          />
        </label>
      </div>
    </div>
    <template #footer>
      <Button
        type="primary"
        :auto-insert-space="false"
        :loading="saving"
        @click="submit"
        >确定</Button
      >
      <Button :auto-insert-space="false" @click="emit('back')">返回</Button>
      <Button :auto-insert-space="false" @click="emit('close')">关闭</Button>
    </template>
  </Modal>
</template>

<style>
.qi-clean-rule-dialog .ant-modal-body {
  max-height: calc(94dvh - 120px);
  padding: 15px 60px 28px 40px;
  overflow: auto;
}

.qi-clean-rule-dialog .ant-modal-title {
  font-weight: 600;
}

.qi-clean-rule-dialog .ant-radio-wrapper-checked {
  color: #2d63ff;
}

.qi-clean-rule-form {
  display: grid;
  gap: 15px;
}

.qi-clean-rule-heading {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 400;
}

.qi-clean-rule-heading::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 3px;
}

.qi-clean-rule-row {
  display: grid;
  gap: 0 20px;
}

.qi-clean-rule-row-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.qi-clean-rule-row-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.qi-clean-rule-item {
  display: grid;
  grid-template-columns: 117px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  min-height: 32px;
}

.qi-clean-rule-item .ant-radio-group {
  display: flex;
  gap: 8px 30px;
  align-items: center;
  min-height: 32px;
}

.qi-clean-rule-stepper {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 36px;
}

.qi-clean-rule-stepper button {
  color: #606266;
  cursor: pointer;
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
}

.qi-clean-rule-stepper .ant-input-number {
  text-align: center;
  border-radius: 0;
}

.qi-clean-rule-item > span:first-child {
  min-height: 32px;
  line-height: 32px;
  text-align: right;
}

.qi-clean-rule-item .required::before {
  margin-right: 4px;
  color: #f56c6c;
  content: '*';
}

.qi-clean-rule-split {
  grid-template-columns: 117px minmax(0, 1fr);
}

.qi-clean-rule-pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.qi-clean-rule-pair .ant-input-number,
.qi-clean-rule-pair .ant-picker,
.qi-clean-rule-form .ant-select {
  width: 100%;
}

.qi-clean-rule-actions {
  display: flex;
  gap: 12px;
}

.qi-clean-rule-actions .ant-btn {
  height: 27px;
  padding: 0 11px;
  font-size: 12px;
  border-radius: 2px;
}

.qi-clean-rule-actions .ant-btn-primary .qi-icon {
  filter: brightness(0) invert(1);
}

.qi-clean-rule-actions .qi-tint.ant-btn {
  color: #2d63ff;
  background: #ecf3ff;
  border-color: #b3c9ff;
}

.qi-clean-rule-dialog .ant-modal-close:focus-visible {
  outline: none;
}

.qi-clean-rule-table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
}

.qi-clean-rule-table th,
.qi-clean-rule-table td {
  padding: 8px 12px;
  text-align: left;
  border-bottom: 1px solid #ebeef5;
}

.qi-clean-rule-table th {
  height: 35px;
  font-size: 14px;
  font-weight: 600;
  color: #606266;
  background: #f1f1f5;
}

.qi-clean-rule-empty {
  color: #909399;
  text-align: center;
}

.qi-clean-rule-dialog .ant-modal-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
