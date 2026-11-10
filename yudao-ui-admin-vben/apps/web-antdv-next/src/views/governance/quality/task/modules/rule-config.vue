<script setup lang="ts">
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { QualityTaskApi } from '#/api/governance/quality/task';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Checkbox,
  CheckboxGroup,
  Input,
  InputNumber,
  RadioGroup,
  Select,
} from 'antdv-next';

import { getDatabaseTableMeta } from '#/api/dataforge/database';
import { getStandardCodeItemPage } from '#/api/governance/standardcodeitem';
import { getStandardCodeSetPage } from '#/api/governance/standardcodeset';
import { useObjectRequest } from '#/hooks/use-object-request';

import { emptyImage } from '../data';
import AuditParameterEditor from './audit-parameter-editor.vue';
import {
  automaticAuditStrategies,
  extendedAuditStrategies,
} from './audit-rule-parameters';
interface RuleConfig {
  allowedTypes?: string[];
  useRegex?: boolean;
  regex?: string;
  ignoreNullValue?: boolean;
  minValue?: string;
  maxValue?: string;
  includeBoundary?: boolean;
  includeMin?: boolean;
  includeMax?: boolean;
  minLength?: number;
  maxLength?: number;
  scale?: number;
  skipInteger?: boolean;
  fillStrategy?: number;
  conditions?: { leftField: string; operator: string; rightField: string }[];
  condition?: string;
  trueValues?: string[];
  ignoreCase?: boolean;
  useCodeTable?: boolean;
  codeSetId?: QualityTaskApi.Id;
  validValues?: string[];
  valueLabels?: { label: string; value: string }[];
  referenceObjectKey?: string;
  refColumn?: string;
}
const props = defineProps<{
  context?: 'standard' | 'task';
  fieldOptions?: { label: string; value: string }[];
  objects: QualityTaskApi.Object[];
  readonly?: boolean;
  referenceObjects?: {
    fields: { label: string; value: string }[];
    id: QualityTaskApi.Id;
    name: string;
  }[];
}>();
const value = defineModel<QualityTaskApi.Evaluation>('value', {
  required: true,
});
const config = computed(() => value.value.config as RuleConfig);
const lowerIncluded = computed(
  () => config.value.includeMin ?? config.value.includeBoundary ?? true,
);
const upperIncluded = computed(
  () => config.value.includeMax ?? config.value.includeBoundary ?? true,
);
const rangeBoundary = computed({
  get: () => {
    if (!config.value.minValue) return upperIncluded.value;
    if (!config.value.maxValue) return lowerIncluded.value;
    return lowerIncluded.value === upperIncluded.value
      ? lowerIncluded.value
      : undefined;
  },
  set: (included: boolean) => {
    config.value.includeBoundary = included;
    config.value.includeMin = included;
    config.value.includeMax = included;
  },
});
const object = computed(() =>
  props.objects.find((row) => row.clientKey === value.value.objectKey),
);
const fields = ref<FoundationDatabaseApi.DatabaseTableColumn[]>([]);
const refFields = ref<FoundationDatabaseApi.DatabaseTableColumn[]>([]);
const loading = ref(false);
const fieldError = ref(false);
const codeSets = ref<{ label: string; value: QualityTaskApi.Id }[]>([]);
const codeItems = ref<{ label: string; value: string }[]>([]);
const fieldRequest = useObjectRequest(() =>
  JSON.stringify([
    value.value.objectKey,
    object.value?.datasourceConnId,
    object.value?.tableName,
  ]),
);
const refRequest = useObjectRequest(() => config.value.referenceObjectKey);
const codeRequest = useObjectRequest();
const itemRequest = useObjectRequest(() => config.value.codeSetId);
const options = computed(() =>
  props.context === 'standard'
    ? (props.fieldOptions ?? [])
    : fields.value.map((row) => ({
        label: row.remarks ? `${row.fieldName}/${row.remarks}` : row.fieldName,
        value: row.fieldName,
      })),
);
const multiple = computed(() =>
  [
    'COMPOSITE_UNIQUENESS_VALIDATION',
    'GROUP_FIELD_COMPLETENESS',
    'RECORD_COMPLETENESS_VALIDATION',
  ].includes(value.value.strategyKey),
);
const singleton = computed({
  get: () => value.value.columnNames[0],
  set: (column: string | undefined) => {
    value.value.columnNames = column ? [column] : [];
  },
});
const yesNo = [
  { label: '是', value: true },
  { label: '否', value: false },
];
const key = computed(() => value.value.strategyKey);
async function loadFields() {
  const current = fieldRequest.begin();
  fields.value = [];
  fieldError.value = false;
  loading.value = false;
  if (props.context === 'standard' || !object.value) return;
  loading.value = true;
  try {
    const result = await getDatabaseTableMeta(
      object.value.datasourceConnId,
      object.value.tableName,
    );
    if (current()) fields.value = result.columns ?? [];
  } catch {
    if (current()) fieldError.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function changeObject() {
  value.value.columnNames =
    key.value === 'MUTUAL_EXCLUSION_VALIDATION' ? ['', ''] : [];
  config.value.referenceObjectKey = undefined;
  config.value.refColumn = undefined;
  if (
    ['NUMERIC_RELATION_VALIDATION', 'TIME_ORDER_VALIDATION'].includes(key.value)
  )
    config.value.conditions = [
      { leftField: '', operator: '<', rightField: '' },
    ];
  if (key.value === 'MUTUAL_EXCLUSION_VALIDATION') config.value.trueValues = [];
  const dependentFields = new Set([
    'assertions',
    'comparisons',
    'conditionColumn',
    'leftColumn',
    'requiredColumns',
  ]);
  value.value.config = Object.fromEntries(
    Object.entries(value.value.config).filter(
      ([field]) => !dependentFields.has(field),
    ),
  );
}
function changeTimeField(index: number, field: string) {
  const next = config.value.conditions?.[index + 1];
  if (next) next.leftField = field;
}
function addTimeField() {
  const conditions = (config.value.conditions ??= []);
  conditions.push({
    leftField: conditions.at(-1)?.rightField ?? '',
    operator: '<',
    rightField: '',
  });
}
function removeMutexField(index: number) {
  value.value.columnNames.splice(index, 1);
  config.value.trueValues?.splice(index, 1);
}
async function loadRefFields() {
  const current = refRequest.begin();
  refFields.value = [];
  if (props.context === 'standard') return;
  const refObject = props.objects.find(
    (row) => row.clientKey === config.value.referenceObjectKey,
  );
  if (!refObject) return;
  const result = await getDatabaseTableMeta(
    refObject.datasourceConnId,
    refObject.tableName,
  );
  if (current()) refFields.value = result.columns ?? [];
}
async function loadCodeSets(name = '') {
  const current = codeRequest.begin();
  const result = await getStandardCodeSetPage({
    pageNo: 1,
    pageSize: 100,
    name,
    status: 0,
  });
  if (current())
    codeSets.value = result.list.map((row) => ({
      label: row.name ?? String(row.id),
      value: row.id,
    }));
}
async function loadCodeItems() {
  const current = itemRequest.begin();
  codeItems.value = [];
  if (config.value.codeSetId === undefined) return;
  const result = await getStandardCodeItemPage({
    pageNo: 1,
    pageSize: 100,
    codeSetId: config.value.codeSetId,
    status: 0,
  });
  if (current())
    codeItems.value = result.list.map((row) => ({
      value: row.value,
      label: row.label,
    }));
}
function syncValues() {
  config.value.validValues = (config.value.valueLabels ?? []).map(
    (row) => row.value,
  );
}
watch(object, loadFields, { immediate: true });
watch(() => config.value.referenceObjectKey, loadRefFields, {
  immediate: true,
});
watch(() => config.value.codeSetId, loadCodeItems, { immediate: true });
watch(
  () => config.value.useCodeTable,
  (value) => {
    if (value) void loadCodeSets();
    else config.value.codeSetId = undefined;
  },
);
</script>
<template>
  <div
    class="q-rule-config q-form-grid"
    :class="{
      'q-enum-config': context === 'standard' && key === 'ENUM_VALIDATION',
      'q-reference-config':
        context === 'standard' &&
        [
          'FOREIGN_KEY_VALIDATION',
          'MASTER_DATA_REF_VALIDATION',
          'CROSS_TABLE_CONSISTENCY',
        ].includes(key),
    }"
  >
    <div v-if="context !== 'standard'" class="q-field">
      <span class="required">评测对象</span>
      <div>
        <Select
          v-model:value="value.objectKey"
          :disabled="readonly"
          :options="
            objects.map((row) => ({
              label: row.objectName,
              value: row.clientKey,
            }))
          "
          placeholder="请选择评测对象"
          @change="changeObject"
        /><small v-if="object">{{ object.datasourceType }} / {{ object.tableName }}</small>
      </div>
    </div>
    <div
      v-if="
        (context !== 'standard' ||
          [
            'COMPOSITE_UNIQUENESS_VALIDATION',
            'GROUP_FIELD_COMPLETENESS',
          ].includes(key)) &&
        ![
          'TIME_ORDER_VALIDATION',
          'MUTUAL_EXCLUSION_VALIDATION',
          'NUMERIC_RELATION_VALIDATION',
          'CONDITIONAL_REQUIRED_VALIDATION',
          'STATE_CONSISTENCY_VALIDATION',
        ].includes(key) &&
        !automaticAuditStrategies.has(key)
      "
      class="q-field"
    >
      <span class="required">检查字段</span>
      <div>
        <Select
          v-if="multiple"
          v-model:value="value.columnNames"
          :disabled="readonly"
          :loading="loading"
          :options="options"
          mode="multiple"
          :max-tag-count="1"
          placeholder="请选择检查字段"
          show-search
          option-filter-prop="label"
        /><Select
          v-else
          v-model:value="singleton"
          :disabled="readonly"
          :loading="loading"
          :options="options"
          placeholder="请选择检查字段"
          show-search
          option-filter-prop="label"
        />
      </div>
    </div>
    <template v-if="key === 'CHARACTER_VALIDATION'">
      <div class="q-field">
        <span>使用正则</span><Checkbox v-model:checked="config.useRegex" :disabled="readonly">
          使用正则
        </Checkbox>
      </div>
      <div v-if="config.useRegex" class="q-field">
        <span class="required">正则表达式</span><Input
          v-model:value="config.regex"
          :disabled="readonly"
          placeholder="请输入正则表达式"
        />
      </div>
      <div v-else class="q-field">
        <span class="required">允许字符类型</span>
        <div>
          <CheckboxGroup
            v-model:value="config.allowedTypes"
            :disabled="readonly"
            :options="[
              { label: '数字', value: 'digit' },
              { label: '字母', value: 'letter' },
              { label: '空格', value: 'space' },
              { label: '特殊符号', value: 'special' },
            ]"
          /><small>若选中数字和字母，系统自动识别为仅允许字母与数字的组合</small>
        </div>
      </div>
    </template>
    <template v-if="key === 'NUMERIC_RANGE_VALIDATION'">
      <div class="q-field">
        <span>最小值</span><Input
          v-model:value="config.minValue"
          :disabled="readonly"
          placeholder="不填写表示不限制最小值"
        />
      </div>
      <div class="q-field">
        <span>最大值</span><Input
          v-model:value="config.maxValue"
          :disabled="readonly"
          placeholder="不填写表示不限制最大值"
        />
      </div>
      <div class="q-field">
        <span>是否包含边界值</span>
        <div>
          <RadioGroup
            v-model:value="rangeBoundary"
            :disabled="readonly"
            :options="[
              { label: '包含', value: true },
              { label: '不包含', value: false },
            ]"
          /><small v-if="rangeBoundary === undefined">当前边界：{{ lowerIncluded ? '包含' : '不包含' }}最小值，{{
              upperIncluded ? '包含' : '不包含'
            }}最大值</small>
        </div>
      </div>
    </template>
    <div v-if="key === 'GROUP_FIELD_COMPLETENESS'" class="q-field q-span">
      <span>字段完整性</span><RadioGroup
        v-model:value="config.fillStrategy"
        :disabled="readonly"
        :options="[
          { label: '必须全部填写（部分为空为异常）', value: 1 },
          { label: '要么全部为空，要么全部填写（部分填写为异常）', value: 2 },
        ]"
      />
    </div>
    <div v-if="key === 'TIME_ORDER_VALIDATION'" class="q-field q-span">
      <span></span>
      <div class="q-time-chain">
        <Select
          v-if="config.conditions?.[0]"
          v-model:value="config.conditions[0].leftField"
          :options="options"
          :disabled="readonly"
          placeholder="字段"
        />
        <template v-for="(condition, index) in config.conditions" :key="index">
          <Select
            class="q-time-operator"
            v-model:value="condition.operator"
            :disabled="readonly"
            :options="[
              { label: '<', value: '<' },
              { label: '≤', value: '<=' },
            ]"
          /><Select
            v-model:value="condition.rightField"
            :options="options"
            :disabled="readonly"
            placeholder="字段"
            @update:value="
              (field) => changeTimeField(index, String(field ?? ''))
            "
          />
        </template>
        <Button
          v-if="!readonly"
          type="primary"
          danger
          class="q-chain-delete"
          aria-label="删除末尾时间字段"
          :disabled="(config.conditions?.length ?? 0) < 2"
          @click="config.conditions?.pop()"
        >
          <IconifyIcon icon="lucide:trash-2" />
        </Button>
        <Button
          v-if="!readonly"
          type="primary"
          aria-label="添加时间字段"
          @click="addTimeField"
        >
          ＋
        </Button>
      </div>
    </div>
    <template v-if="key === 'MUTUAL_EXCLUSION_VALIDATION'">
      <div class="q-field q-span">
        <span class="required">互斥条件</span><RadioGroup
          v-model:value="config.condition"
          :disabled="readonly"
          :options="[
            { label: 'NOT_NULL', value: 'NOT_NULL' },
            { label: 'IS_TRUE', value: 'IS_TRUE' },
          ]"
        />
      </div>
      <div class="q-field q-span">
        <span class="required">检查字段</span>
        <div class="q-mutex-fields">
          <div
            v-for="(_, index) in value.columnNames"
            :key="index"
            class="q-mutex-row"
          >
            <Select
              v-model:value="value.columnNames[index]"
              :options="options"
              :disabled="readonly"
              :loading="loading"
              placeholder="请选择检查字段"
              show-search
              option-filter-prop="label"
            />
            <Input
              v-if="config.condition === 'IS_TRUE'"
              :value="config.trueValues?.[index]"
              :disabled="readonly"
              placeholder="请输入 TRUE 值"
              @update:value="
                (next) => {
                  config.trueValues ??= [];
                  config.trueValues[index] = next;
                }
              "
            />
            <Button
              v-if="!readonly && value.columnNames.length > 2"
              danger
              type="text"
              :aria-label="`删除互斥字段 ${index + 1}`"
              @click="removeMutexField(index)"
            >
              <IconifyIcon icon="lucide:trash-2" />
            </Button>
            <Button
              v-if="!readonly && index === value.columnNames.length - 1"
              type="primary"
              aria-label="添加互斥字段"
              @click="value.columnNames.push('')"
            >
              ＋
            </Button>
          </div>
        </div>
      </div>
    </template>
    <template v-if="key === 'LENGTH_VALIDATION'">
      <div class="q-field">
        <span>最小长度</span><InputNumber
          v-model:value="config.minLength"
          :disabled="readonly"
          :min="0"
          :precision="0"
          placeholder="不填写表示不限制最小长度"
        />
      </div>
      <div class="q-field">
        <span>最大长度</span><InputNumber
          v-model:value="config.maxLength"
          :disabled="readonly"
          :min="0"
          :precision="0"
          placeholder="不填写表示不限制最大长度"
        />
      </div>
    </template>
    <template v-if="key === 'DECIMAL_PRECISION_VALIDATION'">
      <div class="q-field">
        <span>小数位数</span><InputNumber
          v-model:value="config.scale"
          :disabled="readonly"
          :min="0"
          :max="38"
          :precision="0"
        />
      </div>
      <div class="q-field">
        <span>忽略整数值</span><RadioGroup
          v-model:value="config.skipInteger"
          :disabled="readonly"
          :options="yesNo"
        />
      </div>
    </template>
    <template v-if="key === 'ENUM_VALIDATION'">
      <div class="q-field">
        <span>关联代码表</span><RadioGroup
          v-model:value="config.useCodeTable"
          :disabled="readonly"
          :options="yesNo"
        />
      </div>
      <div v-if="context === 'standard'" class="q-field">
        <span>忽略空值</span><RadioGroup
          v-model:value="config.ignoreNullValue"
          :disabled="readonly"
          :options="yesNo"
        />
      </div>
      <div class="q-field">
        <span>是否区分大小写</span><RadioGroup
          v-model:value="config.ignoreCase"
          :disabled="readonly"
          :options="[
            { label: '是', value: false },
            { label: '否', value: true },
          ]"
        />
      </div>
      <div v-if="config.useCodeTable" class="q-field q-enum-table-select">
        <span class="required">选择代码表</span><Select
          v-model:value="config.codeSetId"
          :disabled="readonly"
          :options="codeSets"
          show-search
          :filter-option="false"
          placeholder="请选择代码表"
          @search="loadCodeSets"
          @focus="loadCodeSets()"
        />
      </div>
      <div class="q-span enum-value-table">
        <Button
          v-if="!config.useCodeTable && !readonly"
          type="primary"
          size="small"
          @click="
            config.valueLabels ??= [];
            config.valueLabels.push({ value: '', label: '' });
          "
        >
          ＋ 新增
        </Button>
        <table class="q-table">
          <thead>
            <tr>
              <th>代码值</th>
              <th>代码名称</th>
              <th
                v-if="!readonly && !config.useCodeTable"
                class="enum-value-actions"
              >
                操作
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-if="
                !(config.useCodeTable
                  ? codeItems.length
                  : config.valueLabels?.length)
              "
            >
              <td :colspan="!readonly && !config.useCodeTable ? 3 : 2">
                <div class="q-empty">
                  <img :src="emptyImage" alt="" /><span>暂无记录</span>
                </div>
              </td>
            </tr>
            <tr
              v-for="(entry, index) in config.useCodeTable
                ? codeItems
                : config.valueLabels"
              :key="index"
            >
              <td>
                <span v-if="readonly || config.useCodeTable">{{
                  entry.value
                }}</span><Input
                  v-else
                  v-model:value="entry.value"
                  placeholder="请输入代码值"
                  @change="syncValues"
                />
              </td>
              <td>
                <span v-if="readonly || config.useCodeTable">{{
                  entry.label
                }}</span><Input
                  v-else
                  v-model:value="entry.label"
                  placeholder="请输入代码名称"
                />
              </td>
              <td
                v-if="!readonly && !config.useCodeTable"
                class="enum-value-actions"
              >
                <Button
                  type="link"
                  danger
                  @click="
                    config.valueLabels?.splice(index, 1);
                    syncValues();
                  "
                >
                  <template #icon>
                    <IconifyIcon icon="lucide:trash-2" />
                  </template>
                  删除
                </Button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template
      v-if="
        [
          'FOREIGN_KEY_VALIDATION',
          'MASTER_DATA_REF_VALIDATION',
          'CROSS_TABLE_CONSISTENCY',
        ].includes(key)
      "
    >
      <template v-if="context === 'standard'">
        <div class="q-field">
          <span class="required">评测对象</span><Select
            v-model:value="value.objectKey"
            :disabled="readonly"
            :options="
              referenceObjects?.map((row) => ({
                label: row.name,
                value: String(row.id),
              }))
            "
            placeholder="请选择评测对象"
            @change="value.columnNames = []"
          />
        </div>
        <div class="q-field">
          <span></span><Select
            v-model:value="singleton"
            :disabled="readonly"
            :options="
              referenceObjects?.find(
                (row) => String(row.id) === value.objectKey,
              )?.fields ?? []
            "
            placeholder="字段"
          />
        </div>
      </template>
      <div class="q-field">
        <span class="required">检查对象</span><Select
          v-model:value="config.referenceObjectKey"
          :disabled="readonly"
          :options="
            context === 'standard'
              ? referenceObjects?.map((row) => ({
                  label: row.name,
                  value: String(row.id),
                }))
              : objects.map((row) => ({
                  label: row.objectName,
                  value: row.clientKey,
                }))
          "
          placeholder="请选择检查对象"
          @change="config.refColumn = undefined"
        />
      </div>
      <div class="q-field">
        <span class="required">引用字段</span><Select
          v-model:value="config.refColumn"
          :disabled="readonly"
          :options="
            context === 'standard'
              ? (referenceObjects?.find(
                  (row) => String(row.id) === config.referenceObjectKey,
                )?.fields ?? [])
              : refFields.map((row) => ({
                  label: row.remarks
                    ? `${row.fieldName}/${row.remarks}`
                    : row.fieldName,
                  value: row.fieldName,
                }))
          "
          placeholder="请选择引用字段"
        />
      </div>
    </template>
    <AuditParameterEditor
      v-if="extendedAuditStrategies.has(key)"
      v-model:config="value.config"
      :strategy-key="key"
      :readonly="readonly"
      :field-options="options"
      :reference-field-options="
        context === 'standard'
          ? (referenceObjects?.find(
              (row) => String(row.id) === config.referenceObjectKey,
            )?.fields ?? [])
          : refFields.map((row) => ({
              label: row.remarks
                ? `${row.fieldName}/${row.remarks}`
                : row.fieldName,
              value: row.fieldName,
            }))
      "
    />
    <div
      v-if="
        [
          'CHARACTER_VALIDATION',
          'LENGTH_VALIDATION',
          'DECIMAL_PRECISION_VALIDATION',
          'ENUM_VALIDATION',
        ].includes(key) &&
        !(context === 'standard' && key === 'ENUM_VALIDATION')
      "
      class="q-field"
    >
      <span>忽略空值</span><RadioGroup
        v-model:value="config.ignoreNullValue"
        :disabled="readonly"
        :options="yesNo"
      />
    </div>
  </div>
</template>

<style scoped>
.enum-value-table {
  overflow-x: auto;
}

.enum-value-table .q-table {
  min-width: 560px;
}

.enum-value-actions {
  position: sticky;
  right: 0;
  z-index: 1;
  width: 120px;
  min-width: 120px;
  white-space: nowrap;
  background: #fff;
}

th.enum-value-actions {
  background: #f1f1f5;
}
</style>
