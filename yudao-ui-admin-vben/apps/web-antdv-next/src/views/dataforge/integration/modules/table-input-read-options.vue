<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅由明确用户操作修改设计器隔离草稿。 */
import type {
  TableInputBatchRead,
  TableInputTimeCondition,
} from './table-input-config';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  DatePicker,
  Input,
  InputNumber,
  Modal,
  RadioGroup,
  Select,
  Switch,
  Table,
  TextArea,
} from 'antdv-next';

import {
  createTableInputBatchRead,
  isTableInputNumericField,
  isTableInputTimeField,
  TABLE_INPUT_BASELINE_TYPES,
  TABLE_INPUT_OPERATORS,
  TABLE_INPUT_READ_MODES,
  TABLE_INPUT_TIME_FORMATS,
  tableInputPickerValue,
  validateTableInputConfig,
  validateTableInputTimeCondition,
} from './table-input-config';
import TableInputSqlEditor from './table-input-sql-editor.vue';

const props = withDefaults(defineProps<{
  batchEnabled?: boolean;
  conditionModalWidth?: number;
  conditionTableHeight?: number;
  config: Record<string, any>;
  disabled?: boolean;
  fields: FoundationDatabaseApi.DatabaseTableColumn[];
  fixedBaselineWidth?: number;
  /** Matches the qData reference dialog: plain "+ 新增" button, even condition columns, no support hint. */
  referenceLayout?: boolean;
  showReadMode?: boolean;
  showStartRequired?: boolean;
  showWhere?: boolean;
  timeFormatWidth?: number;
  timeIncrementLabel?: string;
  wherePlaceholder?: string;
}>(), {
  batchEnabled: true,
  conditionModalWidth: 600,
  conditionTableHeight: undefined,
  fixedBaselineWidth: undefined,
  referenceLayout: false,
  showReadMode: true,
  showStartRequired: true,
  showWhere: true,
  timeFormatWidth: undefined,
  timeIncrementLabel: '时间范围增量',
  wherePlaceholder: '',
});

const errors = ref<string[]>([]);
const conditionErrors = ref<string[]>([]);
const conditionOpen = ref(false);
const editingIndex = ref<number>();
let editingConfig: Record<string, any> | undefined;
const draft = ref<TableInputTimeCondition>({
  field: '',
});
const readMode = computed(() => props.config.readMode ?? 'FULL');
const timeFormat = computed(() => props.config.timeFormat ?? 'yyyy-MM-dd');
const modeOptions = computed(() => TABLE_INPUT_READ_MODES.map((option) =>
  option.value === 'TIME_INCREMENT' ? { ...option, label: props.timeIncrementLabel } : option,
));
const pickerFormat = computed(() =>
  timeFormat.value === 'yyyy-MM-dd HH:mm:ss'
    ? 'YYYY-MM-DD HH:mm:ss'
    : 'YYYY-MM-DD',
);
const batch = computed<TableInputBatchRead>(() => ({
  ...createTableInputBatchRead(),
  ...props.config.batchRead,
}));
type BatchModeDraft = Pick<TableInputBatchRead, 'end' | 'field' | 'size' | 'start'>;
let batchModeDrafts: Partial<Record<'ID' | 'TIME', BatchModeDraft>> = {};
const conditions = computed<TableInputTimeCondition[]>(() =>
  Array.isArray(props.config.timeConditions) ? props.config.timeConditions : [],
);
const timeFields = computed(() => props.fields.filter(isTableInputTimeField));
const numberFields = computed(() =>
  props.fields.filter(isTableInputNumericField),
);
const batchFields = computed(() =>
  batch.value.mode === 'TIME' ? timeFields.value : numberFields.value,
);
// The reference layout uses five equal data columns and a wider action column.
const conditionWidths = computed(() =>
  props.referenceLayout
    ? [176, 176, 176, 176, 176, 240]
    : [150, 100, 130, 200, 180, 126],
);
const conditionScrollWidth = computed(() =>
  conditionWidths.value.reduce((total, width) => total + width, 0),
);
const conditionColumns = computed(() => {
  const [field, operator, baselineType, baselineValue, cursorTime, actions] =
    conditionWidths.value;
  return [
    { title: '字段名称', key: 'field', dataIndex: 'field', width: field },
    { title: '运算符', key: 'operator', dataIndex: 'operator', width: operator },
    {
      title: '基准类型',
      key: 'baselineType',
      dataIndex: 'baselineType',
      width: baselineType,
    },
    {
      title: '基准值',
      key: 'baselineValue',
      dataIndex: 'baselineValue',
      width: baselineValue,
      ellipsis: true,
    },
    {
      title: '游标时间',
      key: 'cursorTime',
      dataIndex: 'cursorTime',
      width: cursorTime,
      sorter: (a: TableInputTimeCondition, b: TableInputTimeCondition) =>
        (a.cursorTime ?? '').localeCompare(b.cursorTime ?? ''),
    },
    { title: '操作', key: 'actions', fixed: 'right' as const, width: actions },
  ];
});
const conditionRows = computed(() =>
  conditions.value.map((condition, index) => ({
    ...condition,
    rowKey: `condition-${index}`,
    index,
  })),
);
function fieldOptions(
  fields: FoundationDatabaseApi.DatabaseTableColumn[],
  value?: string,
) {
  const options = fields.map((field) => ({
    value: field.fieldName,
    label: field.fieldName,
  }));
  if (value && !options.some((option) => option.value === value))
    options.push({ value, label: `${value}（当前字段不可用）` });
  return options;
}
function set(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function setBatch(key: keyof TableInputBatchRead, value: unknown) {
  if (props.disabled) return;
  if (key === 'mode' && (value === 'ID' || value === 'TIME') && value !== batch.value.mode) {
    const previous = batch.value;
    batchModeDrafts[previous.mode] = {
      field: previous.field, start: previous.start, end: previous.end, size: previous.size,
    };
    props.config.batchRead = {
      ...previous, mode: value,
      ...(batchModeDrafts[value] ?? { field: undefined, start: undefined, end: undefined, size: '1' }),
    };
    return;
  }
  props.config.batchRead = { ...batch.value, [key]: value };
}
function openCondition(index?: number) {
  if (props.disabled) return;
  editingConfig = props.config;
  editingIndex.value = index;
  conditionErrors.value = [];
  draft.value =
    index === undefined
      ? { field: '' }
      : { ...conditions.value[index]! };
  conditionOpen.value = true;
}
function saveCondition() {
  if (props.disabled || editingConfig !== props.config) return;
  conditionErrors.value = validateTableInputTimeCondition(
    draft.value,
    props.fields,
    timeFormat.value,
  );
  if (conditionErrors.value.length > 0) return;
  const next = [...conditions.value];
  if (editingIndex.value === undefined) next.push({ ...draft.value });
  else next[editingIndex.value] = { ...draft.value };
  set('timeConditions', next);
  conditionOpen.value = false;
}
function removeCondition(index: number) {
  if (!props.disabled)
    set(
      'timeConditions',
      conditions.value.filter((_condition, position) => index !== position),
    );
}
function validate() {
  const validationConfig = props.batchEnabled === false
    ? new Proxy(props.config, {
        get: (target, key) => key === 'batchRead' ? undefined : Reflect.get(target, key),
      })
    : props.config;
  errors.value = validateTableInputConfig(
    validationConfig,
    props.fields,
  );
  return errors.value.length === 0;
}
watch(
  () => [props.config, props.disabled],
  () => {
    conditionOpen.value = false;
    conditionErrors.value = [];
    errors.value = [];
  },
);
watch(() => props.config, () => { batchModeDrafts = {}; });
defineExpose({ validate });
</script>

<template>
  <div
    class="qi-table-input-options"
    :class="{ 'qi-table-input-reference': referenceLayout }"
  >
    <Alert
      v-if="errors.length"
      :message="errors.join('；')"
      type="error"
      show-icon
    />
    <div class="qi-table-input-grid">
      <div v-if="showReadMode" class="qi-table-input-field qi-table-input-full">
        <label><em>*</em>读取模式</label>
        <RadioGroup
          :value="readMode"
          :options="modeOptions"
          :disabled="disabled"
          aria-label="读取模式"
          @update:value="(value) => set('readMode', value)"
        />
      </div>
      <template v-if="readMode === 'ID_INCREMENT'">
        <div class="qi-table-input-field">
          <label><em>*</em>id字段</label><Select
            :value="config.incrementColumn"
            :options="fieldOptions(numberFields, config.incrementColumn)"
            :disabled="disabled"
            show-search
            option-filter-prop="label"
            allow-clear
            placeholder="请选择id字段"
            aria-label="id字段"
            @update:value="(value) => set('incrementColumn', value)"
          />
        </div>
        <div class="qi-table-input-field">
          <label><em v-if="showStartRequired">*</em>开始值</label><Input
            :value="config.startValue"
            :disabled="disabled"
            placeholder="请输入开始值"
            aria-label="开始值"
            @update:value="(value) => set('startValue', value)"
          />
        </div>
      </template>
      <template v-if="readMode === 'TIME_INCREMENT'">
        <div class="qi-table-input-field">
          <label>时间格式</label><Select
            :style="timeFormatWidth ? { width: `${timeFormatWidth}px`, maxWidth: '100%' } : undefined"
            :value="timeFormat"
            :options="
              TABLE_INPUT_TIME_FORMATS.map((value) => ({ label: value, value }))
            "
            :disabled="disabled"
            aria-label="时间格式"
            @update:value="(value) => set('timeFormat', value)"
          />
        </div>
        <div class="qi-table-input-full qi-table-condition-toolbar">
          <Button :disabled="disabled" @click="openCondition()">+ 新增</Button>
        </div>
        <Table
          class="qi-table-input-full qi-table-condition-table"
          :class="{ 'qi-table-condition-table-fixed': conditionTableHeight }"
          :style="conditionTableHeight ? { height: `${conditionTableHeight}px`, '--qi-condition-body-height': `${conditionTableHeight - 36}px` } : undefined"
          :columns="conditionColumns"
          :data-source="conditionRows"
          :pagination="false"
          :scroll="{ x: conditionScrollWidth, ...(conditionTableHeight ? { y: conditionTableHeight - 36 } : {}) }"
          row-key="rowKey"
          size="small"
          aria-label="时间增量条件"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'baselineType'">
{{
              TABLE_INPUT_BASELINE_TYPES.find(
                (type) => type.value === record.baselineType,
              )?.label || record.baselineType
            }}
</template>
            <template v-else-if="column.key === 'baselineValue'">
{{
              record.baselineType === 'TIME_RANGE'
                ? '自动获取当前时间'
                : record.baselineValue || '-'
            }}
</template>
            <template v-else-if="column.key === 'operator'">
{{
              record.baselineType === 'TIME_RANGE'
                ? '范围'
                : record.operator || '-'
            }}
</template>
            <template v-else-if="column.key === 'cursorTime'">
{{
              record.cursorTime || '-'
            }}
</template>
            <span
              v-else-if="column.key === 'actions'"
              class="qi-table-condition-actions"
              ><Button
                type="link"
                size="small"
                :disabled="disabled"
                @click="openCondition(record.index)"
                >编辑</Button><Button
                type="link"
                size="small"
                :disabled="disabled"
                @click="removeCondition(record.index)"
                >删除</Button></span>
          </template>
          <template #emptyText>
<div class="qi-table-input-empty">
              <img
                src="/assets/qdata-integration/node-empty-reference.png"
                alt=""
                aria-hidden="true"
              /><span>暂无记录</span>
            </div>
</template>
        </Table>
      </template>
      <div v-if="showWhere" class="qi-table-input-field qi-table-input-full">
        <label>where条件</label><TextArea
          :value="config.whereCondition"
          :disabled="disabled || config.ifSql === 1"
          :rows="4"
          :maxlength="256"
          :show-count="{
            formatter: ({ count, maxLength }) =>
              `${count} / ${maxLength}个字符`,
          }"
          :placeholder="wherePlaceholder || '例如 id > 10 and id < 1000，请不要以分号结尾，仅支持单条查询条件表达式，禁止insert/update/delete/drop/alter等语句'"
          aria-label="where条件"
          @update:value="(value) => set('whereCondition', value)"
        />
      </div>
    </div>
    <section v-if="batchEnabled !== false && readMode === 'FULL'" class="qi-table-input-batch">
      <div class="qi-table-input-section-heading">
        <h3>分批读取</h3>
        <label><em>*</em>启用分批读取<Switch
            :checked="batch.enabled"
            :disabled="disabled"
            aria-label="启用分批读取"
            @update:checked="(value) => setBatch('enabled', value)"
        /></label>
      </div>
      <div v-if="batch.enabled" class="qi-table-input-grid">
        <div class="qi-table-input-field">
          <label><em>*</em>分批字段</label><Select
            :value="batch.field"
            :options="fieldOptions(batchFields, batch.field)"
            :disabled="disabled"
            show-search
            allow-clear
            option-filter-prop="label"
            placeholder="请选择分批字段"
            aria-label="分批字段"
            @update:value="(value) => setBatch('field', value)"
          />
        </div>
        <div class="qi-table-input-field">
          <label><em>*</em>分批方式</label><RadioGroup
            :value="batch.mode"
            :options="[
              { label: '按ID分批', value: 'ID' },
              { label: '按时间分批', value: 'TIME' },
            ]"
            :disabled="disabled"
            aria-label="分批方式"
            @update:value="(value) => setBatch('mode', value)"
          />
        </div>
        <template v-if="batch.mode === 'ID'">
          <div class="qi-table-input-field">
            <label><em>*</em>起始ID值</label><Input
              :value="batch.start"
              :disabled="disabled"
              placeholder="请输入起始值"
              aria-label="起始ID值"
              @update:value="(value) => setBatch('start', value)"
            />
          </div>
          <div class="qi-table-input-field">
            <label>结束ID值</label><Input
              :value="batch.end"
              :disabled="disabled"
              placeholder="请输入结束值"
              aria-label="结束ID值"
              @update:value="(value) => setBatch('end', value)"
            />
          </div>
          <div class="qi-table-input-field">
            <label><em>*</em>每批ID跨度</label><Input
              :value="batch.size"
              :disabled="disabled"
              placeholder="请输入每批ID跨度"
              aria-label="每批ID跨度"
              @update:value="(value) => setBatch('size', value)"
            />
          </div>
        </template>
        <template v-else>
          <div class="qi-table-input-field">
            <label><em>*</em>时间单位</label><RadioGroup
              :value="batch.timeUnit"
              :options="[
                { label: '时', value: 'HOUR' },
                { label: '分', value: 'MINUTE' },
                { label: '秒', value: 'SECOND' },
              ]"
              :disabled="disabled"
              aria-label="时间单位"
              @update:value="(value) => setBatch('timeUnit', value)"
            />
          </div>
          <div class="qi-table-input-field">
            <label><em>*</em>每批时间跨度</label><div class="qi-table-input-unit-control">
<Input
              :value="batch.size"
              :disabled="disabled"
              aria-label="每批时间跨度"
              @update:value="(value) => setBatch('size', value)"
            /><span>{{ { HOUR: '时', MINUTE: '分', SECOND: '秒' }[batch.timeUnit] }}</span>
</div>
          </div>
          <div class="qi-table-input-field">
            <label><em>*</em>起始时间</label><DatePicker
              :value="tableInputPickerValue(batch.start, true)"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              show-time
              :disabled="disabled"
              placeholder="请选择开始时间"
              aria-label="起始时间"
              @update:value="(value) => setBatch('start', value)"
            />
          </div>
          <div class="qi-table-input-field">
            <label>结束时间</label><DatePicker
              :value="tableInputPickerValue(batch.end, true)"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              show-time
              :disabled="disabled"
              placeholder="请选择结束时间"
              aria-label="结束时间"
              @update:value="(value) => setBatch('end', value)"
            />
          </div>
        </template>
        <div class="qi-table-input-field">
          <label><em>*</em>最大重试次数</label><InputNumber
            :value="batch.maxRetries"
            :min="0"
            :max="100"
            :precision="0"
            :disabled="disabled"
            aria-label="最大重试次数"
            @update:value="(value) => setBatch('maxRetries', value)"
          />
        </div>
        <div class="qi-table-input-field">
          <label><em>*</em>读取重试间隔</label><div class="qi-table-input-unit-control">
<InputNumber
            :value="batch.retryIntervalSeconds"
            :min="1"
            :max="86400"
            :precision="0"
            :disabled="disabled"
            aria-label="读取重试间隔"
            @update:value="(value) => setBatch('retryIntervalSeconds', value)"
          /><span>秒</span>
</div>
        </div>
      </div>
    </section>
    <p
      v-if="
        !referenceLayout &&
        (readMode !== 'FULL' || (batchEnabled !== false && batch.enabled))
      "
      class="qi-table-input-support"
    >
      {{ batchEnabled === false ? '增量配置可保存；当前执行通道尚未接入持久游标，任务检查会提示。' : '增量与分批配置可保存；当前执行通道尚未接入持久游标和受管分批执行，任务检查会提示。' }}
    </p>
    <Modal
      v-model:open="conditionOpen"
      title="属性字段编辑"
      class="qi-node-modal qi-node-modal-reference qi-table-input-condition-modal"
      :wrap-class-name="referenceLayout ? 'qi-table-condition-modal-reference' : undefined"
      :width="conditionModalWidth ?? 600"
      :mask-closable="false"
      :destroy-on-hidden="true"
      :ok-button-props="{ disabled, autoInsertSpace: false }"
      :cancel-button-props="{ autoInsertSpace: false }"
      ok-text="保存"
      cancel-text="关闭"
      @ok="saveCondition"
    >
      <div class="qi-table-condition-editor">
        <Alert
          v-if="conditionErrors.length"
          :message="conditionErrors.join('；')"
          type="error"
          show-icon
        />
        <label><span><em>*</em>基准类型</span><Select
            v-model:value="draft.baselineType"
            :options="TABLE_INPUT_BASELINE_TYPES"
            :disabled="disabled"
            placeholder="请选择基准类型"
            aria-label="基准类型"
        /></label>
        <label><span><em>*</em>字段名称</span><Select
            v-model:value="draft.field"
            :options="fieldOptions(timeFields, draft.field)"
            :disabled="disabled"
            show-search
            allow-clear
            option-filter-prop="label"
            placeholder="请输入字段名称"
            aria-label="时间条件字段名称"
        /></label>
        <label v-if="draft.baselineType !== 'TIME_RANGE'"><span><em>*</em>运算符</span><Select
            v-model:value="draft.operator"
            :options="
              TABLE_INPUT_OPERATORS.map((value) => ({ label: value, value }))
            "
            :disabled="disabled"
            placeholder="请选择运算符"
            aria-label="时间条件运算符"
        /></label>
        <label v-if="draft.baselineType === 'FIXED'"><span><em>*</em>基准值</span><DatePicker
            :style="fixedBaselineWidth ? { width: `${fixedBaselineWidth}px`, maxWidth: '100%' } : undefined"
            :value="tableInputPickerValue(draft.baselineValue, pickerFormat.includes('HH'))"
            :format="pickerFormat"
            :value-format="pickerFormat"
            :show-time="pickerFormat.includes('HH')"
            :disabled="disabled"
            placeholder="请选择固定时间"
            aria-label="固定时间基准值"
            @update:value="(value) => { if (!disabled) draft.baselineValue = value == null ? undefined : String(value); }"
        /></label>
        <template v-else-if="draft.baselineType === 'TIME_RANGE'">
          <label><span>基准值</span><Input value="自动获取当前时间，无需填写" disabled /></label>
          <label><span><em>*</em>游标时间</span><DatePicker
              :value="tableInputPickerValue(draft.cursorTime, pickerFormat.includes('HH'))"
              :format="pickerFormat"
              :value-format="pickerFormat"
              :show-time="pickerFormat.includes('HH')"
              :disabled="disabled"
              placeholder="请选择游标时间"
              aria-label="游标时间"
              @update:value="(value) => { if (!disabled) draft.cursorTime = value == null ? undefined : String(value); }"
          /></label>
        </template>
        <label v-else-if="draft.baselineType === 'SQL'" class="qi-table-condition-sql"><span><em>*</em>基准值</span><TableInputSqlEditor
            v-model="draft.baselineValue"
            height="290px"
            render-validation-decorations="off"
            :folding="!referenceLayout"
            :placeholder="referenceLayout ? '请输入sql' : undefined"
            :readonly="disabled"
            aria-label="SQL基准表达式"
        /></label>
        <label v-else><span>基准值</span><Input value="自动获取当前时间，无需填写" disabled /></label>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.qi-table-input-options {
  display: grid;
  gap: 16px;
}

.qi-table-input-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.qi-table-input-field {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  min-width: 0;
}

.qi-table-input-field > label {
  padding-top: 6px;
  text-align: right;
  white-space: nowrap;
}

.qi-table-input-full {
  grid-column: 1 / -1;
}

.qi-table-input-unit-control {
  display: flex;
  min-width: 0;
}

.qi-table-input-unit-control > span {
  display: grid;
  flex: 0 0 60px;
  place-items: center;
  color: #909399;
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
  border-left: 0;
}

.qi-table-input-unit-control :deep(.ant-input),
.qi-table-input-unit-control :deep(.ant-input-number) {
  min-width: 0;
  border-start-end-radius: 0;
  border-end-end-radius: 0;
}

.qi-table-input-options em,
.qi-table-condition-editor em {
  margin-right: 4px;
  font-style: normal;
  color: #ff4d4f;
}

.qi-table-input-field :deep(.ant-select),
.qi-table-input-field :deep(.ant-input-number),
.qi-table-input-field :deep(.ant-picker),
.qi-table-condition-editor :deep(.ant-select),
.qi-table-condition-editor :deep(.ant-picker) {
  width: 100%;
  min-width: 0;
}

.qi-table-input-field :deep(.ant-radio-group) {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding-top: 5px;
}

.qi-table-input-field :deep(.ant-radio-wrapper) {
  margin-inline-end: 0;
}

.qi-table-condition-toolbar {
  display: flex;
  justify-content: flex-start;
}

.qi-table-condition-table {
  min-width: 0;
}

.qi-table-condition-table-fixed {
  overflow: hidden;
}

.qi-table-condition-table-fixed :deep(.ant-table-body) {
  min-height: var(--qi-condition-body-height);
}

.qi-table-condition-table :deep(.ant-table-thead > tr > th) {
  color: #666;
  background: #f1f1f5;
}

.qi-table-condition-actions {
  display: inline-flex;
  white-space: nowrap;
}

.qi-table-input-empty {
  display: grid;
  gap: 24px;
  justify-items: center;
  padding: 36px 0;
  color: #909399;
}

.qi-table-input-empty img {
  width: 180px;
  height: 132px;
  object-fit: contain;
}

.qi-table-input-section-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.qi-table-input-section-heading h3 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 400;
}

.qi-table-input-section-heading h3::before {
  width: 5px;
  height: 16px;
  content: '';
  background: #2f65ff;
  border-radius: 3px;
}

.qi-table-input-section-heading > label {
  display: flex;
  gap: 8px;
  align-items: center;
}

.qi-table-input-support {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.qi-table-condition-editor {
  display: grid;
  gap: 16px;
  padding: 12px 16px 16px 0;
}

.qi-table-condition-editor > label {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.qi-table-condition-editor > label > span {
  padding-top: 6px;
  text-align: right;
}

/* Reference layout: light primary "+ 新增" button. */
.qi-table-input-reference .qi-table-condition-toolbar :deep(.ant-btn) {
  height: 27px;
  padding: 0 11px;
  font-size: 13px;
  color: #2d63ff;
  background: #ecf2ff;
  border-color: #a9c1ff;
  border-radius: 2px;
}

.qi-table-input-reference .qi-table-condition-toolbar :deep(.ant-btn:not(:disabled):hover) {
  color: #fff;
  background: #2d63ff;
  border-color: #2d63ff;
}

/* Reference layout: flat header without separators or rounded corners. */
.qi-table-input-reference .qi-table-condition-table :deep(.ant-table),
.qi-table-input-reference .qi-table-condition-table :deep(.ant-table-container),
.qi-table-input-reference .qi-table-condition-table :deep(.ant-table-header),
.qi-table-input-reference .qi-table-condition-table :deep(.ant-table-thead > tr > th) {
  border-radius: 0 !important;
}

.qi-table-input-reference .qi-table-condition-table :deep(.ant-table-thead > tr > th) {
  padding: 7px 10px;
  font-weight: 600;
  color: #606266;
  background: #f1f1f5;
}

.qi-table-input-reference .qi-table-condition-table :deep(.ant-table-thead > tr > th::before) {
  display: none;
}

.qi-table-input-reference .qi-table-condition-table {
  border-bottom: 1px solid #ebeef5;
}

/* Sort icon sits right after the column title, as in the reference table. */
.qi-table-input-reference .qi-table-condition-table :deep(.ant-table-column-sorters) {
  gap: 6px;
  justify-content: flex-start;
}

.qi-table-input-reference .qi-table-condition-table :deep(.ant-table-column-title) {
  flex: none;
}

/* Reference condition dialog: wider label column and flush right edge. */
.qi-table-condition-modal-reference .qi-table-condition-editor {
  padding: 0 0 10px;
}

.qi-table-condition-modal-reference .qi-table-condition-editor > label {
  grid-template-columns: 108px minmax(0, 1fr);
}

.qi-table-condition-modal-reference .qi-table-condition-sql :deep(.qi-sql-editor-surface) {
  border: 0;
}

/* Reference SQL editor: grey line-number gutter, filled current line, readable placeholder. */
.qi-table-condition-modal-reference .qi-table-condition-sql :deep(.monaco-editor .margin) {
  background-color: #f5f5f7;
}

.qi-table-condition-modal-reference .qi-table-condition-sql :deep(.monaco-editor .view-overlays .current-line) {
  background: #e8f1ff;
  border: 0;
}

.qi-table-condition-modal-reference .qi-table-condition-sql :deep(.monaco-editor .editorPlaceholder) {
  color: #606266;
}

@media (max-width: 767px) {
  .qi-table-input-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 479px) {
  .qi-table-input-field,
  .qi-table-condition-editor > label {
    grid-template-columns: minmax(0, 1fr);
    gap: 6px;
  }

  .qi-table-input-field > label,
  .qi-table-condition-editor > label > span {
    text-align: left;
  }
}
</style>
