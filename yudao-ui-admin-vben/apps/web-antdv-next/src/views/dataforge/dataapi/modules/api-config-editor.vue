<script lang="ts" setup>
import type { ApiConfigValidationOptions } from './api-config';

import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Button,
  Checkbox,
  Input,
  InputNumber,
  message,
  RadioGroup,
  Select,
  Table,
  TextArea,
} from 'antdv-next';

import { fetchDataApiData, testDataApi } from '#/api/dataforge/dataapi';

import { defaultApiConfig, validateApiConfig } from './api-config';

defineOptions({ name: 'DataforgeApiConfigEditor' });

const props = withDefaults(defineProps<{ disabled?: boolean }>(), {
  disabled: false,
});
const emit = defineEmits<{
  columnsChange: [columns: DataforgeDataApiApi.ResponseColumnItem[]];
}>();
const model = defineModel<DataforgeDataApiApi.DataApi>({ required: true });
type ParamField =
  | 'bodyFormData'
  | 'bodyUrlencoded'
  | 'requestHeader'
  | 'requestParam';
type Column = DataforgeDataApiApi.ResponseColumnItem;

const access = useAccessStore();
const user = useUserStore();
const testing = ref(false);
const fetching = ref(false);
const responseVisible = ref(false);
const testResponse = ref('');
const parsedRowCount = ref<number>();
const operationError = ref('');
const columnsRefreshHint = ref('');
let requestGeneration = 0;
let applyingColumns = false;
let alive = true;
let columnsRequestDefinition = requestDefinition(model.value);

/** 字段声明只沿用到相同请求与响应解析定义，名称与参数说明不影响实际取数。 */
function requestDefinition(config: DataforgeDataApiApi.DataApi) {
  const params = (items?: DataforgeDataApiApi.RequestParamItem[]) =>
    (items ?? []).map(({ name, value }) => ({ name, value }));
  let body: DataforgeDataApiApi.RequestParamItem[] | string | undefined;
  if (config.requestMethod === 'POST') {
    if (config.bodyType === 'form-data') {
      body = params(config.bodyFormData);
    } else if (config.bodyType === 'urlencoded') {
      body = params(config.bodyUrlencoded);
    } else if (config.bodyType === 'raw') {
      body = config.bodyRaw;
    }
  }
  return JSON.stringify({
    requestUrl: config.requestUrl?.trim(),
    requestMethod: config.requestMethod,
    requestHeader: params(config.requestHeader),
    requestParam: params(config.requestParam),
    bodyType: config.requestMethod === 'POST' ? config.bodyType : undefined,
    body,
    dataKey: config.dataKey,
    dataType: config.dataType,
    ifPage: config.ifPage,
    pageKey: config.ifPage === 1 ? config.pageKey : undefined,
    pageSizeKey: config.ifPage === 1 ? config.pageSizeKey : undefined,
  });
}

const parameterSections = computed(() => [
  {
    field: 'requestHeader' as const,
    title: '请求头',
    nameHint: '参数名，如 Authorization',
  },
  { field: 'requestParam' as const, title: 'Query 参数', nameHint: '参数名' },
]);
const bodyParamField = computed<ParamField>(() =>
  model.value.bodyType === 'form-data' ? 'bodyFormData' : 'bodyUrlencoded',
);
const FIELD_TYPE_OPTIONS = [
  { label: '字符串', value: 12 },
  { label: '整数', value: 4 },
  { label: '小数', value: 8 },
];
const BODY_TYPE_OPTIONS = [
  { label: '无请求体', value: 'none' },
  { label: 'form-data', value: 'form-data' },
  { label: 'urlencoded', value: 'urlencoded' },
  { label: 'JSON', value: 'raw' },
];
const columnTableColumns = [
  { title: '字段名', key: 'fieldName', width: 160 },
  { title: '类型', key: 'fieldType', width: 115 },
  { title: '长度', key: 'displaySize', width: 85 },
  { title: '小数位', key: 'precisionSize', width: 85 },
  { title: '主键', key: 'pk', width: 65 },
  { title: '可空', key: 'nullable', width: 65 },
  { title: '说明', key: 'description', width: 140 },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' as const },
];
const columnKeys = new WeakMap<Column, string>();
let nextColumnKey = 0;

function columnKey(column: Column) {
  let key = columnKeys.get(column);
  if (!key) {
    key = `api-column-${++nextColumnKey}`;
    columnKeys.set(column, key);
  }
  return key;
}

function addParam(field: ParamField) {
  if (props.disabled) return;
  (model.value[field] ??= []).push({ name: '', value: '', description: '' });
}

function removeParam(field: ParamField, index: number) {
  if (!props.disabled) model.value[field]?.splice(index, 1);
}

function addColumn() {
  if (props.disabled) return;
  (model.value.dataColumns ??= []).push({
    fieldName: '',
    fieldType: 12,
    displaySize: 100,
    precisionSize: 0,
    nullable: true,
    pk: false,
  });
}

function removeColumn(index: number) {
  if (!props.disabled) model.value.dataColumns?.splice(index, 1);
}

function setPrimaryKey(column: Column, checked: boolean) {
  if (props.disabled) return;
  column.pk = checked;
  if (checked) column.nullable = false;
}

function validate(options?: ApiConfigValidationOptions) {
  const error = validateApiConfig(model.value, options);
  if (error) message.warning(error);
  return !error;
}

function invalidateRequests() {
  ++requestGeneration;
  testing.value = false;
  fetching.value = false;
  responseVisible.value = false;
  testResponse.value = '';
  parsedRowCount.value = undefined;
  operationError.value = '';
  columnsRefreshHint.value = '';
}

function requestSnapshot() {
  const config = defaultApiConfig(model.value);
  // 试调接口沿用保存 VO 的名称约束，不创建或更新独立接口资源。
  config.apiName = config.apiName?.trim() || '接口试调';
  return config;
}

async function handleTest() {
  if (props.disabled || !validate({ requireColumns: false })) return;
  const generation = ++requestGeneration;
  testing.value = true;
  fetching.value = false;
  responseVisible.value = false;
  operationError.value = '';
  try {
    const response = await testDataApi(requestSnapshot());
    if (!alive || generation !== requestGeneration) return;
    // 保留原始文本，避免格式化 JSON 时将大整数或精确小数转换成浮点数。
    testResponse.value = response;
    responseVisible.value = true;
  } catch {
    if (alive && generation === requestGeneration) {
      operationError.value = '测试请求失败，请检查接口配置后重试';
    }
  } finally {
    if (alive && generation === requestGeneration) testing.value = false;
  }
}

async function handleFetchColumns() {
  if (props.disabled || !validate({ requireColumns: false })) return;
  const generation = ++requestGeneration;
  fetching.value = true;
  testing.value = false;
  operationError.value = '';
  columnsRefreshHint.value = '';
  const snapshot = requestSnapshot();
  const definition = requestDefinition(snapshot);
  try {
    const result = await fetchDataApiData(snapshot);
    if (!alive || generation !== requestGeneration) return;
    parsedRowCount.value = result.rows?.length ?? 0;
    if (!result.columns?.length) {
      operationError.value = '未解析到返回字段，请检查数据节点与返回数据类型';
      return;
    }
    const preserveDeclarations = definition === columnsRequestDefinition;
    const previous = new Map(
      (model.value.dataColumns ?? []).map((column) => [
        column.fieldName,
        column,
      ]),
    );
    if (!preserveDeclarations && previous.size > 0) {
      columnsRefreshHint.value =
        '接口请求或响应解析配置已变化，已按新响应重新生成字段，请重新确认类型、主键和可空设置。';
    }
    applyingColumns = true;
    try {
      // 响应决定字段集合；同一取数定义下只保留同名字段已声明的元数据。
      model.value.dataColumns = result.columns.map((column) => {
        const configured = preserveDeclarations
          ? previous.get(column.fieldName)
          : undefined;
        if (!configured) return { ...column };
        return {
          ...column,
          fieldType: configured.fieldType ?? column.fieldType,
          displaySize: configured.displaySize ?? column.displaySize,
          precisionSize: configured.precisionSize ?? column.precisionSize,
          pk: configured.pk ?? column.pk,
          nullable: configured.pk
            ? false
            : (configured.nullable ?? column.nullable),
          description: configured.description ?? column.description,
        };
      });
      columnsRequestDefinition = definition;
    } finally {
      applyingColumns = false;
    }
    message.success(
      `已获取 ${result.columns.length} 个返回字段，请确认字段类型`,
    );
  } catch {
    if (alive && generation === requestGeneration) {
      operationError.value = '获取字段失败，请检查接口配置后重试';
    }
  } finally {
    if (alive && generation === requestGeneration) fetching.value = false;
  }
}

watch(
  model,
  (current, previous) => {
    if (current !== previous)
      columnsRequestDefinition = requestDefinition(current);
  },
  { flush: 'sync' },
);
watch(
  model,
  () => {
    if (!applyingColumns) invalidateRequests();
  },
  { deep: true, flush: 'sync' },
);
watch(
  () => model.value.dataColumns,
  (columns) => {
    emit('columnsChange', columns ?? []);
  },
  { deep: true },
);
watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  invalidateRequests,
  { flush: 'sync' },
);
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) invalidateRequests();
  },
);
onBeforeUnmount(() => {
  alive = false;
  invalidateRequests();
});

defineExpose({ validate });
</script>

<template>
  <div class="api-config-editor">
    <section class="editor-section">
      <div class="editor-section__heading">接口连接</div>
      <div class="editor-grid">
        <div class="editor-field editor-field--wide">
          <label class="editor-label editor-label--required">请求地址</label>
          <Input
            v-model:value="model.requestUrl"
            :disabled="disabled"
            allow-clear
            placeholder="https://api.example.com/data"
          />
        </div>
        <div class="editor-field">
          <label class="editor-label">请求方式</label>
          <RadioGroup
            v-model:value="model.requestMethod"
            :disabled="disabled"
            :options="[
              { label: 'GET', value: 'GET' },
              { label: 'POST', value: 'POST' },
            ]"
          />
        </div>
      </div>
    </section>

    <section class="editor-section">
      <div class="editor-section__heading">请求参数</div>
      <div
        v-for="section in parameterSections"
        :key="section.field"
        class="parameter-block"
      >
        <div class="editor-section__toolbar">
          <span>{{ section.title }}</span>
          <Button
            :disabled="disabled"
            size="small"
            @click="addParam(section.field)"
          >
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            添加参数
          </Button>
        </div>
        <div v-if="!model[section.field]?.length" class="editor-hint">
          未配置{{ section.title }}
        </div>
        <div
          v-for="(item, index) in model[section.field]"
          :key="`${section.field}-${index}`"
          class="parameter-row"
        >
          <Input
            v-model:value="item.name"
            :disabled="disabled"
            :placeholder="section.nameHint"
          />
          <Input
            v-model:value="item.value"
            :disabled="disabled"
            placeholder="参数值"
          />
          <Input
            v-model:value="item.description"
            :disabled="disabled"
            placeholder="说明（选填）"
          />
          <Button
            :disabled="disabled"
            danger
            size="small"
            type="text"
            @click="removeParam(section.field, index)"
          >
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            删除
          </Button>
        </div>
      </div>
      <div v-if="model.requestMethod === 'POST'" class="parameter-block">
        <div class="editor-section__toolbar">
          <span>请求体</span>
          <Select
            v-model:value="model.bodyType"
            :disabled="disabled"
            :options="BODY_TYPE_OPTIONS"
            class="body-type-select"
            size="small"
          />
        </div>
        <template
          v-if="
            model.bodyType === 'form-data' || model.bodyType === 'urlencoded'
          "
        >
          <div class="mb-2 flex justify-end">
            <Button
              :disabled="disabled"
              size="small"
              @click="addParam(bodyParamField)"
            >
              <template #icon><IconifyIcon icon="lucide:plus" /></template>
              添加参数
            </Button>
          </div>
          <div
            v-for="(item, index) in model[bodyParamField]"
            :key="`${bodyParamField}-${index}`"
            class="parameter-row"
          >
            <Input
              v-model:value="item.name"
              :disabled="disabled"
              placeholder="参数名"
            />
            <Input
              v-model:value="item.value"
              :disabled="disabled"
              placeholder="参数值"
            />
            <Input
              v-model:value="item.description"
              :disabled="disabled"
              placeholder="说明（选填）"
            />
            <Button
              :disabled="disabled"
              danger
              size="small"
              type="text"
              @click="removeParam(bodyParamField, index)"
            >
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删除
            </Button>
          </div>
        </template>
        <TextArea
          v-else-if="model.bodyType === 'raw'"
          v-model:value="model.bodyRaw"
          :disabled="disabled"
          :rows="5"
          class="editor-code"
          placeholder='{ "key": "value" }'
        />
      </div>
    </section>

    <section class="editor-section">
      <div class="editor-section__heading">响应解析与分页</div>
      <div class="editor-grid">
        <div class="editor-field">
          <label class="editor-label">返回数据类型</label>
          <RadioGroup
            v-model:value="model.dataType"
            :disabled="disabled"
            :options="[
              { label: '对象集合', value: 2 },
              { label: '单对象', value: 1 },
            ]"
          />
        </div>
        <div class="editor-field">
          <label class="editor-label">数据节点</label>
          <Input
            v-model:value="model.dataKey"
            :disabled="disabled"
            allow-clear
            placeholder="如 data.list；留空取整个响应"
          />
        </div>
        <div class="editor-field">
          <label class="editor-label">是否分页</label>
          <RadioGroup
            v-model:value="model.ifPage"
            :disabled="disabled"
            :options="[
              { label: '否', value: 0 },
              { label: '是', value: 1 },
            ]"
          />
        </div>
        <div v-if="model.ifPage === 1" class="editor-field">
          <label class="editor-label editor-label--required">分页参数名</label>
          <div class="flex gap-2">
            <Input
              v-model:value="model.pageKey"
              :disabled="disabled"
              placeholder="页码，如 pageNo"
            />
            <Input
              v-model:value="model.pageSizeKey"
              :disabled="disabled"
              placeholder="条数，如 pageSize"
            />
          </div>
        </div>
      </div>
    </section>

    <section class="editor-section">
      <div class="editor-section__toolbar">
        <div class="editor-section__heading">测试与返回字段</div>
        <div class="editor-actions">
          <Button
            :disabled="disabled"
            :loading="testing"
            size="small"
            @click="handleTest"
          >
            <template #icon>
              <IconifyIcon icon="lucide:flask-conical" />
            </template>
            测试请求
          </Button>
          <Button
            :disabled="disabled"
            :loading="fetching"
            ghost
            size="small"
            type="primary"
            @click="handleFetchColumns"
          >
            <template #icon>
              <IconifyIcon icon="lucide:list-tree" />
            </template>
            获取返回字段
          </Button>
          <Button :disabled="disabled" size="small" @click="addColumn">
            <template #icon>
              <IconifyIcon icon="lucide:plus" />
            </template>
            手工添加
          </Button>
        </div>
      </div>
      <p class="editor-hint">
        按响应首条数据解析字段，默认类型为字符串；请按实际数据确认类型、长度和小数位。
      </p>
      <p v-if="operationError" class="editor-error" role="alert">
        {{ operationError }}
      </p>
      <p v-if="columnsRefreshHint" class="editor-hint">
        {{ columnsRefreshHint }}
      </p>
      <p v-if="parsedRowCount !== undefined" class="editor-hint">
        本次解析到 {{ parsedRowCount }} 条样例数据。
      </p>
      <details v-if="responseVisible" open class="test-response-block">
        <summary>测试响应</summary>
        <pre class="test-response">{{ testResponse }}</pre>
      </details>
      <Table
        :columns="columnTableColumns"
        :data-source="model.dataColumns ?? []"
        :pagination="false"
        :row-key="columnKey"
        :scroll="{ x: 780, y: 280 }"
        size="small"
      >
        <template #bodyCell="{ column, record, index }">
          <Input
            v-if="column.key === 'fieldName'"
            v-model:value="record.fieldName"
            :disabled="disabled"
            size="small"
            placeholder="字段名"
          />
          <Select
            v-else-if="column.key === 'fieldType'"
            v-model:value="record.fieldType"
            :disabled="disabled"
            :options="FIELD_TYPE_OPTIONS"
            class="w-full"
            size="small"
          />
          <InputNumber
            v-else-if="column.key === 'displaySize'"
            v-model:value="record.displaySize"
            :disabled="disabled"
            :min="1"
            class="w-full"
            size="small"
          />
          <InputNumber
            v-else-if="column.key === 'precisionSize'"
            v-model:value="record.precisionSize"
            :disabled="disabled"
            :min="0"
            class="w-full"
            size="small"
          />
          <Checkbox
            v-else-if="column.key === 'pk'"
            :checked="record.pk === true"
            :disabled="disabled"
            @update:checked="setPrimaryKey(record, $event)"
          />
          <Checkbox
            v-else-if="column.key === 'nullable'"
            v-model:checked="record.nullable"
            :disabled="disabled || record.pk === true"
          />
          <Input
            v-else-if="column.key === 'description'"
            v-model:value="record.description"
            :disabled="disabled"
            size="small"
          />
          <Button
            v-else-if="column.key === 'actions'"
            :disabled="disabled"
            danger
            size="small"
            type="text"
            @click="removeColumn(index)"
          >
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            删除
          </Button>
        </template>
      </Table>
    </section>
  </div>
</template>

<style scoped>
.api-config-editor {
  min-width: 0;
  container-type: inline-size;
}

.editor-section {
  padding: 16px 0;
  border-bottom: 1px solid hsl(var(--border));
}

.editor-section:first-child {
  padding-top: 0;
}

.editor-section:last-child {
  border-bottom: 0;
}

.editor-section__heading {
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 600;
}

.editor-section__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.editor-section__toolbar .editor-section__heading {
  margin: 0;
}

.editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
}

.editor-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.editor-field--wide {
  grid-column: 1 / -1;
}

.editor-label {
  font-size: 14px;
  font-weight: 500;
}

.editor-label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}

.editor-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.editor-hint {
  margin: 8px 0 12px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.editor-error {
  margin: 8px 0;
  font-size: 12px;
  color: hsl(var(--destructive));
}

.parameter-block {
  padding: 12px;
  margin-bottom: 12px;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.parameter-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.body-type-select {
  width: 160px;
}

.editor-code,
.test-response {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.test-response-block {
  margin: 12px 0;
}

.test-response-block summary {
  margin-bottom: 8px;
  cursor: pointer;
}

.test-response {
  max-height: 300px;
  padding: 12px;
  margin: 0;
  overflow: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 50%);
  border-radius: 6px;
}

@container (max-width: 620px) {
  .editor-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .parameter-row {
    flex-wrap: wrap;
  }

  .parameter-row > :deep(.ant-input) {
    flex: 1 1 40%;
  }
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
