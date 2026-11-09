<script lang="ts" setup>
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  onDeactivated,
  reactive,
  ref,
  useTemplateRef,
  watch,
} from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  Select,
  Table,
  Tag,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';
import { getApiCategoryList } from '#/api/supply/apicategory';
import {
  createDataApi,
  getDataApi,
  getDataApiDatabaseList,
  getDataApiTableColumns,
  getDataApiTableList,
  testDataApi,
  updateDataApi,
} from '#/api/supply/dataapi';
import EtlTargetBatchPicker from '#/components/etl-target-batch-picker/index.vue';
import FormWizardSteps from '#/components/form-wizard-steps/index.vue';

import { sameId } from '../utils/id';
import { createLatestGuard } from '../utils/latest';
import {
  API_TYPE_OPTIONS,
  AUTH_TYPE_OPTIONS,
  CONFIG_TYPE_OPTIONS,
  FIELD_MODEL_OPTIONS,
  FIELD_MODEL_RULE_PLACEHOLDER,
  IP_RULE_TYPE_OPTIONS,
  METHOD_OPTIONS,
  PARAM_TYPE_OPTIONS,
  RESULT_FORMAT_OPTIONS,
} from './data';
import { etlBatchConfigError, etlBatchCursor } from './etl-batch';
import DatabaseSelect from './modules/database-select.vue';

defineOptions({ name: 'SupplyDataApiWizard', inheritAttrs: false });

const emit = defineEmits<{ (e: 'saved'): void }>();

const visible = defineModel<boolean>('visible', { default: false });
const step = ref(0);
const saving = ref(false);
const initializing = ref(false);
const busy = computed(() => saving.value || initializing.value);
const content = useTemplateRef<HTMLDivElement>('content');
const steps = [
  { title: '属性配置', hint: '填写 API 基础信息，配置认证方式和安全策略' },
  { title: '参数配置', hint: '选择数据源和配置方式，设置请求参数及返回字段' },
  { title: '测试', hint: '填写测试参数，验证 API 返回结果后保存服务' },
];
watch(step, async () => {
  await nextTick();
  if (content.value) content.value.scrollTop = 0;
});
const form = reactive<SupplyDataApiApi.DataApi>({});
watch(
  () => form.configType,
  (type) => {
    testResult.value = undefined;
    testGuard.invalidate();
    testing.value = false;
    if (type !== 'ETL_BATCH') return;
    form.apiType = 'QUERY';
    form.resultFormat = 'LIST';
    form.cacheEnabled = 0;
    if (!form.authType || form.authType === 'NONE') form.authType = 'APPKEY';
  },
);
function requestApi() {
  const api = JSON.parse(JSON.stringify(form)) as SupplyDataApiApi.DataApi;
  if (api.configType === 'ETL_BATCH')
    api.requestParams = [
      {
        name: 'afterRow',
        paramType: 'STRING',
        nullable: 1,
        defaultValue: '0',
        description: '非负十进制游标，使用上一页 nextAfterRow',
      },
    ];
  return api;
}

/** 下拉数据 */
const categoryTree = ref<any[]>([]);
const databaseList = ref<SupplyDataApiApi.DatabaseSimple[]>([]);
const databaseCatalog = ref<FoundationDatabaseTypeApi.DatabaseType[]>([]);
const databasesLoading = ref(false);
const databasesError = ref('');
const tableList = ref<{ label: string; value: string }[]>([]);
const openGuard = createLatestGuard();
const tableGuard = createLatestGuard();
const testGuard = createLatestGuard();
const columnGuard = createLatestGuard();
const databaseGuard = createLatestGuard();
const saveGuard = createLatestGuard();
const access = useAccessStore();
function invalidateRequests() {
  for (const guard of [
    openGuard,
    tableGuard,
    testGuard,
    columnGuard,
    databaseGuard,
    saveGuard,
  ])
    guard.invalidate();
  testing.value = false;
  saving.value = false;
  initializing.value = false;
  testResult.value = undefined;
  columnPickVisible.value = false;
  databasesLoading.value = false;
  databasesError.value = '';
  databaseList.value = [];
  databaseCatalog.value = [];
}
watch(visible, (open) => {
  if (!open) invalidateRequests();
});
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    access.accessCodes?.join('\0'),
  ],
  () => {
    visible.value = false;
    invalidateRequests();
    categoryTree.value = [];
    tableList.value = [];
  },
);
onBeforeUnmount(invalidateRequests);
onDeactivated(() => {
  visible.value = false;
  invalidateRequests();
});

async function open(id?: number | string, defaultCategoryId?: number | string) {
  invalidateRequests();
  step.value = 0;
  Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
  Object.assign(form, {
    version: 'v1.0.0',
    method: 'POST',
    resultFormat: 'LIST',
    configType: 'TABLE',
    status: 0,
    apiType: 'QUERY',
    authType: 'NONE',
    ipRuleType: 'WHITE',
    limitEnabled: 0,
    limitSeconds: 60,
    limitCount: 5,
    cacheEnabled: 0,
    cacheSeconds: 60,
    breakerEnabled: 0,
    breakerErrorCount: 10,
    breakerWindowSeconds: 60,
    breakerOpenSeconds: 60,
    maxConcurrency: undefined,
    timeoutSeconds: undefined,
    alarmEnabled: 0,
    requestParams: [],
    responseFields: [],
    categoryId: id ? undefined : defaultCategoryId,
  });
  testParams.value = {};
  afterRow.value = '0';
  testResult.value = undefined;
  visible.value = true;
  initializing.value = true;
  const isStale = openGuard.next();
  try {
    const categories = await getApiCategoryList({});
    if (isStale()) {
      return;
    }
    categoryTree.value = handleTree(categories);
    void loadDatabases();
    if (id) {
      const data = await getDataApi(id);
      if (isStale()) {
        return;
      }
      Object.assign(form, data);
      if (form.databaseId) {
        void loadTables(false);
      }
    }
  } catch (error) {
    if (!isStale()) visible.value = false;
    throw error;
  } finally {
    if (!isStale()) initializing.value = false;
  }
}

defineExpose({ open });

async function loadDatabases() {
  const isStale = databaseGuard.next();
  databasesLoading.value = true;
  databasesError.value = '';
  try {
    const [list, catalog] = await Promise.all([
      getDataApiDatabaseList(),
      getDatabaseTypeSimpleList(),
    ]);
    if (isStale()) return;
    databaseList.value = list;
    databaseCatalog.value = catalog;
  } catch {
    if (isStale()) return;
    databaseList.value = [];
    databaseCatalog.value = [];
    databasesError.value = '数据源列表加载失败，请重试';
  } finally {
    if (!isStale()) databasesLoading.value = false;
  }
}

function handleDatabaseChange() {
  testGuard.invalidate();
  testing.value = false;
  columnGuard.invalidate();
  columnPickVisible.value = false;
  columnPickLoading.value = false;
  tableColumns.value = [];
  void loadTables();
}

async function loadTables(clear = true) {
  tableGuard.invalidate();
  if (clear) {
    form.tableName = undefined;
    testResult.value = undefined;
  }
  tableList.value = [];
  if (!form.databaseId) {
    return;
  }
  const databaseId = form.databaseId;
  const isStale = tableGuard.next();
  try {
    const list = await getDataApiTableList(databaseId);
    if (isStale() || !sameId(form.databaseId, databaseId)) return;
    tableList.value = list.map((name) => ({ label: name, value: name }));
  } catch {
    if (!isStale() && sameId(form.databaseId, databaseId)) tableList.value = [];
  }
}

/** ==================== 步骤校验与切换 ==================== */

function validateStep1() {
  if (!form.categoryId) {
    message.warning('请选择 API 类目');
    return false;
  }
  if (!form.name?.trim()) {
    message.warning('请输入 API 名称');
    return false;
  }
  if (!form.path?.trim()) {
    message.warning('请输入 API 地址');
    return false;
  }
  if (!form.version?.trim()) {
    message.warning('请输入 API 版本');
    return false;
  }
  return true;
}

function validateStep2() {
  const batchError = etlBatchConfigError(form);
  if (batchError) {
    message.warning(batchError);
    return false;
  }
  if (form.configType === 'TABLE' && (!form.databaseId || !form.tableName)) {
    message.warning('请选择数据源与数据表');
    return false;
  }
  if (
    form.configType === 'SQL' &&
    (!form.databaseId || !form.sqlText?.trim())
  ) {
    message.warning('请配置数据源与 SQL 脚本');
    return false;
  }
  if (form.configType === 'FORWARD' && !form.forwardUrl?.trim()) {
    message.warning('请配置转发地址');
    return false;
  }
  if (form.apiType && form.apiType !== 'QUERY' && form.configType !== 'SQL') {
    message.warning('插入/更新/删除/多脚本接口类型仅支持 SQL 脚本式');
    return false;
  }
  return true;
}

/** SQL 快捷标签：在脚本末尾插入动态条件标签模板 */
function insertSqlTag(tag: 'if' | 'where') {
  const templates: Record<string, string> = {
    if: `\n<if test="param != null and param != ''">\n  AND column = #{param}\n</if>`,
    where: `\n<where>\n  <if test="param != null">\n    AND column = #{param}\n  </if>\n</where>`,
  };
  form.sqlText = (form.sqlText ?? '') + templates[tag];
}

function handleStepChange(next: number) {
  if (busy.value || next === step.value || next < 0 || next >= steps.length)
    return;
  if (next > step.value) {
    if (!validateStep1()) return;
    if (next > 1 && !validateStep2()) return;
  }
  step.value = next;
}

/** ==================== 参数配置 ==================== */

function addParam() {
  form.requestParams!.push({
    name: '',
    nullable: 0,
    description: '',
    paramType: 'STRING',
    exampleValue: '',
    defaultValue: '',
  });
}

function addField() {
  form.responseFields!.push({
    name: '',
    outputName: '',
    cnName: '',
    description: '',
    dataType: '',
    timeFormat: '',
    exampleValue: '',
    model: '',
    modelRule: '',
    desensitize: 0,
  });
}

/** SQL 解析：提取 #{参数名} 生成请求参数行 */
function parseSqlParams() {
  if (!form.sqlText?.trim()) {
    message.warning('请先输入 SQL 脚本');
    return;
  }
  const names = new Set<string>();
  for (const match of form.sqlText.matchAll(/#\{(\w+)}/g)) {
    names.add(match[1]!);
  }
  const existing = new Set(
    (form.requestParams ?? []).map((param) => param.name),
  );
  let added = 0;
  names.forEach((name) => {
    if (!existing.has(name)) {
      form.requestParams!.push({
        name,
        nullable: 0,
        description: '',
        paramType: 'STRING',
        exampleValue: '',
        defaultValue: '',
      });
      added++;
    }
  });
  message.success(`解析到 ${names.size} 个参数，新增 ${added} 个`);
}

/** ==================== 列选择弹窗 ==================== */

const columnPickVisible = ref(false);
const columnPickMode = ref<'field' | 'param'>('param');
const columnPickLoading = ref(false);
const tableColumns = ref<SupplyDataApiApi.TableColumn[]>([]);
const pickedColumnKeys = ref<string[]>([]);

const columnPickColumns = [
  { title: '列名', dataIndex: 'name', key: 'name' },
  { title: '数据类型', dataIndex: 'dataType', key: 'dataType', width: 110 },
  { title: '数据长度', dataIndex: 'length', key: 'length', width: 90 },
  { title: '小数位', dataIndex: 'scale', key: 'scale', width: 80 },
  { title: '主键', key: 'pk', width: 60 },
  { title: '允许为空', key: 'nullable', width: 90 },
  { title: '列描述', dataIndex: 'comment', key: 'comment' },
];

async function openColumnPick(mode: 'field' | 'param') {
  if (!form.databaseId || !form.tableName) {
    message.warning('请先选择数据源与数据表');
    return;
  }
  columnPickMode.value = mode;
  pickedColumnKeys.value = [];
  columnPickVisible.value = true;
  columnPickLoading.value = true;
  const isStale = columnGuard.next();
  try {
    const columns = await getDataApiTableColumns(
      form.databaseId,
      form.tableName,
    );
    if (!isStale()) {
      tableColumns.value = columns;
    }
  } finally {
    if (!isStale()) {
      columnPickLoading.value = false;
    }
  }
}

function confirmColumnPick() {
  const picked = tableColumns.value.filter((column) =>
    pickedColumnKeys.value.includes(column.name!),
  );
  if (columnPickMode.value === 'param') {
    const existing = new Set(
      (form.requestParams ?? []).map((param) => param.name),
    );
    picked.forEach((column) => {
      if (existing.has(column.name)) {
        return;
      }
      form.requestParams!.push({
        name: column.name,
        nullable: column.nullable ?? 1,
        description: column.comment ?? '',
        paramType: 'STRING',
        exampleValue: column.defaultValue ?? '',
        defaultValue: '',
      });
    });
  } else {
    const existing = new Set(
      (form.responseFields ?? []).map((field) => field.name),
    );
    picked.forEach((column) => {
      if (existing.has(column.name)) {
        return;
      }
      form.responseFields!.push({
        name: column.name,
        outputName: '',
        cnName: column.comment ?? '',
        description: column.comment ?? '',
        dataType: column.dataType ?? '',
        timeFormat: '',
        exampleValue: column.defaultValue ?? '',
        model: '',
        modelRule: '',
        desensitize: 0,
      });
    });
  }
  columnPickVisible.value = false;
}

/** ==================== 测试 ==================== */

const testParams = ref<Record<string, any>>({});
const testing = ref(false);
const testResult = ref<SupplyDataApiApi.ExecuteResult>();
const afterRow = ref('0');

// 配置或参数变化立即作废旧试调，包含改回原值及同一事件内再次试调的情况。
watch(
  [form, testParams, afterRow],
  () => {
    testGuard.invalidate();
    testResult.value = undefined;
    testing.value = false;
  },
  { deep: true, flush: 'sync' },
);

const testResultColumns = computed(() => {
  const first = testResult.value?.rows?.[0];
  if (!first) {
    return [];
  }
  return Object.keys(first).map((key) => ({
    title: key,
    dataIndex: key,
    key,
    ellipsis: true,
  }));
});

async function handleTest() {
  if (!validateStep2()) {
    return;
  }
  if (testing.value) return;
  let requestParams = JSON.parse(JSON.stringify(testParams.value)) as Record<
    string,
    unknown
  >;
  if (form.configType === 'ETL_BATCH') {
    try {
      requestParams = { afterRow: etlBatchCursor(afterRow.value) };
    } catch (error) {
      message.warning((error as Error).message);
      return;
    }
  }
  testing.value = true;
  const isStale = testGuard.next();
  try {
    const result = await testDataApi({
      api: requestApi(),
      params: requestParams,
      pageNo: 1,
      pageSize: 10,
    });
    if (isStale()) {
      return;
    }
    // 先补全当前配置的返回字段，再展示本次结果，避免配置监听清空成功结果。
    if ((form.responseFields ?? []).length === 0 && result.rows?.[0]) {
      form.responseFields = Object.keys(result.rows[0]).map((name) => ({
        name,
        cnName: '',
        dataType: '',
        description: '',
      }));
    }
    testResult.value = result;
    message.success(`接口调用成功，耗时 ${result.durationMs} 毫秒`);
  } finally {
    if (!isStale()) testing.value = false;
  }
}

/** ==================== 保存 ==================== */

async function handleSave(close = false) {
  if (saving.value) return;
  if (!validateStep1() || !validateStep2()) {
    return;
  }
  saving.value = true;
  const isStale = saveGuard.next();
  try {
    const data = requestApi();
    if (data.id) {
      await updateDataApi(data);
    } else {
      const id = await createDataApi(data);
      if (isStale()) return;
      form.id = id;
    }
    if (isStale()) return;
    message.success(
      form.status === 1 ? '已保存为未发布草稿，需发布后网关才生效' : '保存成功',
    );
    emit('saved');
    if (close) {
      visible.value = false;
    }
  } finally {
    if (!isStale()) saving.value = false;
  }
}

const getTitle = computed(() =>
  form.id ? `API 服务修改 - ${form.name ?? ''}` : 'API 服务新增',
);
</script>

<template>
  <section
    v-if="visible"
    :aria-label="getTitle"
    :aria-busy="busy"
    class="wizard"
  >
    <aside class="wizard__rail">
      <FormWizardSteps
        :current="step"
        :steps="steps"
        :busy="busy"
        aria-label="API 服务配置步骤"
        @change="handleStepChange"
      />
    </aside>
    <div class="wizard__main">
      <header class="wizard__header">
        <div class="wizard__title">{{ steps[step]?.title }}</div>
        <div class="wizard__hint">{{ steps[step]?.hint }}</div>
      </header>
      <div ref="content" class="wizard__content" :inert="busy || undefined">
        <Alert
          v-if="form.unpublishedDraft"
          class="mb-3"
          show-icon
          type="warning"
          message="当前编辑的是未发布草稿。网关仍执行已发布快照，需在列表中点击发布后才对外生效。"
        />

        <!-- 步骤一：属性配置 -->
        <div v-show="step === 0" class="wizard__body">
          <div class="wizard__section">基础信息</div>
          <div class="form-grid">
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >API 类目</label
              >
              <TreeSelect
                v-model:value="form.categoryId"
                :field-names="{
                  children: 'children',
                  label: 'name',
                  value: 'id',
                }"
                :tree-data="categoryTree"
                allow-clear
                placeholder="请选择 API 类目"
                show-search
                tree-default-expand-all
                tree-node-filter-prop="name"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >API 名称</label
              >
              <Input
                v-model:value="form.name"
                allow-clear
                placeholder="如：站点流量实时数据服务"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >API 地址</label
              >
              <Input
                v-model:value="form.path"
                allow-clear
                placeholder="如：/api/water/level/hourly/list"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >API 版本</label
              >
              <Input
                v-model:value="form.version"
                allow-clear
                placeholder="如：v1.0.0"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >请求方式</label
              >
              <Select v-model:value="form.method" :options="METHOD_OPTIONS" />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >返回格式</label
              >
              <Select
                v-model:value="form.resultFormat"
                :disabled="form.configType === 'ETL_BATCH'"
                :options="
                  RESULT_FORMAT_OPTIONS.map(({ label, value }) => ({
                    label,
                    value,
                  }))
                "
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >接口类型</label
              >
              <Select
                v-model:value="form.apiType"
                :options="API_TYPE_OPTIONS"
                :disabled="form.configType === 'ETL_BATCH'"
              />
              <div class="form-item__tip">非查询类仅支持 SQL 脚本式</div>
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >认证方式</label
              >
              <RadioGroup
                v-model:value="form.authType"
                :options="
                  AUTH_TYPE_OPTIONS.filter(
                    (item) =>
                      form.configType !== 'ETL_BATCH' || item.value !== 'NONE',
                  ).map(({ label, value }) => ({ label, value }))
                "
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">状态</label>
              <RadioGroup
                v-model:value="form.status"
                :options="[
                  { label: '下线', value: 0 },
                  { label: '上线', value: 1 },
                ]"
              />
            </div>
            <div class="form-item form-item--full">
              <label class="form-item__label">描述</label>
              <TextArea
                v-model:value="form.description"
                :maxlength="500"
                :rows="3"
                show-count
              />
            </div>
          </div>

          <div class="wizard__section">安全策略</div>
          <div class="form-grid">
            <div class="form-item">
              <label class="form-item__label">IP 规则类型</label>
              <RadioGroup
                v-model:value="form.ipRuleType"
                :options="IP_RULE_TYPE_OPTIONS"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">IP 名单</label>
              <Input
                v-model:value="form.ipWhitelist"
                :maxlength="500"
                placeholder="如 192.168.1.10,192.168.1.11"
              />
              <div class="form-item__tip">
                多个 IP 用英文逗号隔开，留空不限制
              </div>
            </div>
            <div class="form-item">
              <label class="form-item__label">是否限流</label>
              <RadioGroup
                v-model:value="form.limitEnabled"
                :options="[
                  { label: '否', value: 0 },
                  { label: '是', value: 1 },
                ]"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">结果缓存</label>
              <div class="flex items-center gap-2">
                <RadioGroup
                  v-model:value="form.cacheEnabled"
                  :disabled="form.configType === 'ETL_BATCH'"
                  :options="[
                    { label: '关闭', value: 0 },
                    { label: '开启', value: 1 },
                  ]"
                />
                <template v-if="form.cacheEnabled === 1">
                  <span>缓存</span>
                  <InputNumber
                    v-model:value="form.cacheSeconds"
                    :min="1"
                    class="w-24"
                  />
                  <span>秒</span>
                </template>
              </div>
              <div class="form-item__tip">仅查询类支持结果缓存</div>
            </div>
            <div
              v-if="form.limitEnabled === 1"
              class="form-item form-item--full"
            >
              <label class="form-item__label">限流配置</label>
              <div class="flex items-center gap-2">
                <span>每</span>
                <InputNumber
                  v-model:value="form.limitSeconds"
                  :min="1"
                  class="w-28"
                />
                <span>秒内限制请求</span>
                <InputNumber
                  v-model:value="form.limitCount"
                  :min="1"
                  class="w-28"
                />
                <span>次</span>
              </div>
            </div>
            <div class="form-item">
              <label class="form-item__label">熔断降级</label>
              <RadioGroup
                v-model:value="form.breakerEnabled"
                :options="[
                  { label: '关闭', value: 0 },
                  { label: '开启', value: 1 },
                ]"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">告警通知</label>
              <RadioGroup
                v-model:value="form.alarmEnabled"
                :options="[
                  { label: '关闭', value: 0 },
                  { label: '开启', value: 1 },
                ]"
              />
              <div class="form-item__tip">调用失败时向创建人发送站内信</div>
            </div>
            <div
              v-if="form.breakerEnabled === 1"
              class="form-item form-item--full"
            >
              <label class="form-item__label">熔断配置</label>
              <div class="flex items-center gap-2">
                <InputNumber
                  v-model:value="form.breakerWindowSeconds"
                  :min="1"
                  class="w-24"
                />
                <span>秒内异常达到</span>
                <InputNumber
                  v-model:value="form.breakerErrorCount"
                  :min="1"
                  class="w-24"
                />
                <span>次则熔断</span>
                <InputNumber
                  v-model:value="form.breakerOpenSeconds"
                  :min="1"
                  class="w-24"
                />
                <span>秒</span>
              </div>
            </div>
            <div class="form-item">
              <label class="form-item__label">最大并发</label>
              <div class="flex items-center gap-2">
                <InputNumber
                  v-model:value="form.maxConcurrency"
                  :max="1000"
                  :min="0"
                  :precision="0"
                  class="w-32"
                  placeholder="不限制"
                />
                <span class="text-xs text-gray-400">
                  同时执行中的请求上限（全部节点合计），空或 0 不限制
                </span>
              </div>
            </div>
            <div class="form-item">
              <label class="form-item__label">查询超时</label>
              <div class="flex items-center gap-2">
                <InputNumber
                  v-model:value="form.timeoutSeconds"
                  :max="600"
                  :min="1"
                  :precision="0"
                  class="w-32"
                  placeholder="平台默认"
                />
                <span>秒</span>
                <span class="text-xs text-gray-400">
                  超时由数据库取消执行；为空使用平台默认值，不超过平台上限
                </span>
              </div>
            </div>
            <div class="form-item form-item--full">
              <label class="form-item__label">备注</label>
              <TextArea
                v-model:value="form.remark"
                :maxlength="500"
                :rows="3"
                show-count
              />
            </div>
          </div>
        </div>

        <!-- 步骤二：参数配置 -->
        <div v-show="step === 1" class="wizard__body">
          <div class="wizard__section">数据源配置</div>
          <div class="form-grid">
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >配置方式</label
              >
              <Select
                v-model:value="form.configType"
                :options="CONFIG_TYPE_OPTIONS"
              />
            </div>
            <template
              v-if="form.configType === 'TABLE' || form.configType === 'SQL'"
            >
              <div class="form-item">
                <label class="form-item__label form-item__label--required"
                  >数据源</label
                >
                <DatabaseSelect
                  v-model:value="form.databaseId"
                  :databases="databaseList"
                  :catalog="databaseCatalog"
                  :loading="databasesLoading"
                  :error="databasesError"
                  :disabled="busy"
                  @change="handleDatabaseChange"
                  @retry="loadDatabases"
                />
              </div>
              <div v-if="form.configType === 'TABLE'" class="form-item">
                <label class="form-item__label form-item__label--required"
                  >数据源表</label
                >
                <Select
                  v-model:value="form.tableName"
                  :options="tableList"
                  allow-clear
                  placeholder="搜索并选择数据表"
                  show-search
                />
              </div>
            </template>
            <template v-else-if="form.configType === 'FORWARD'">
              <div class="form-item">
                <label class="form-item__label form-item__label--required"
                  >转发地址</label
                >
                <Input
                  v-model:value="form.forwardUrl"
                  placeholder="http(s):// 开头的目标接口地址"
                />
              </div>
              <div class="form-item">
                <label class="form-item__label">转发请求方式</label>
                <Select
                  v-model:value="form.forwardMethod"
                  :options="METHOD_OPTIONS"
                  allow-clear
                />
                <div class="form-item__tip">留空时使用 API 请求方式</div>
              </div>
            </template>
            <div
              v-else-if="form.configType === 'ETL_BATCH'"
              class="form-item form-item--full"
            >
              <label class="form-item__label form-item__label--required"
                >ETL 目标批次 ID</label
              >
              <Input
                :value="form.etlBatchId == null ? '' : String(form.etlBatchId)"
                placeholder="从 ETL 运行的目标批次详情复制，保持原始完整数字"
                @update:value="(id) => (form.etlBatchId = id)"
              />
              <EtlTargetBatchPicker
                :disabled="saving"
                @select="(item) => (form.etlBatchId = item.id)"
              />
              <Alert
                type="info"
                show-icon
                message="发布时冻结批次身份。每次调用均复核批次准入、归档状态、当前行权限和脱敏；仅认证查询、游标列表，禁止结果缓存。批次被回放替换后需重新选择并发布。"
              />
            </div>
          </div>

          <template v-if="form.configType === 'SQL'">
            <div class="wizard__section">
              <span
                >SQL 脚本（参数用 #{参数名} 占位，支持 &lt;where&gt;/&lt;if&gt;
                动态条件标签；多脚本类型用分号分隔，同一事务执行）</span
              >
              <div class="flex gap-2">
                <Button size="small" @click="insertSqlTag('if')">
                  <template #icon>
                    <IconifyIcon icon="lucide:code-xml" />
                  </template>
                  if 标签
                </Button>
                <Button size="small" @click="insertSqlTag('where')">
                  <template #icon>
                    <IconifyIcon icon="lucide:code-xml" />
                  </template>
                  where 标签
                </Button>
                <Button size="small" type="primary" @click="parseSqlParams">
                  <template #icon>
                    <IconifyIcon icon="lucide:scan-text" />
                  </template>
                  SQL 解析
                </Button>
              </div>
            </div>
            <TextArea
              v-model:value="form.sqlText"
              :rows="8"
              class="wizard__sql"
              placeholder='如：SELECT * FROM hyd_discharge <where><if test="station_code != null"> AND station_code = #{station_code}</if></where>'
              spellcheck="false"
            />
          </template>

          <div v-if="form.configType !== 'ETL_BATCH'" class="wizard__section">
            <span>请求参数</span>
            <div class="flex gap-2">
              <Button
                v-if="form.configType === 'TABLE'"
                size="small"
                @click="openColumnPick('param')"
              >
                <template #icon>
                  <IconifyIcon icon="lucide:list-plus" />
                </template>
                从表选择
              </Button>
              <Button size="small" type="primary" @click="addParam">
                <template #icon><IconifyIcon icon="lucide:plus" /></template>
                新增参数
              </Button>
            </div>
          </div>
          <div v-if="form.configType !== 'ETL_BATCH'" class="wizard__rows">
            <div class="wizard__row wizard__row--head">
              <span class="w-40">参数名称</span>
              <span class="w-24">允许为空</span>
              <span class="flex-1">描述</span>
              <span class="w-28">参数类型</span>
              <span class="w-32">示例值</span>
              <span class="w-32">默认值</span>
              <span class="w-10">操作</span>
            </div>
            <div
              v-for="(param, index) in form.requestParams ?? []"
              :key="index"
              class="wizard__row"
            >
              <Input
                v-model:value="param.name"
                class="w-40"
                placeholder="参数名称"
              />
              <span class="w-24">
                <Checkbox
                  :checked="param.nullable === 1"
                  @update:checked="(v: boolean) => (param.nullable = v ? 1 : 0)"
                />
              </span>
              <Input
                v-model:value="param.description"
                class="flex-1"
                placeholder="描述"
              />
              <Select
                v-model:value="param.paramType"
                :options="PARAM_TYPE_OPTIONS"
                class="w-28"
              />
              <Input
                v-model:value="param.exampleValue"
                class="w-32"
                placeholder="示例值"
              />
              <Input
                v-model:value="param.defaultValue"
                class="w-32"
                placeholder="默认值"
              />
              <span class="w-10">
                <IconifyIcon
                  class="cursor-pointer text-red-500"
                  icon="lucide:trash-2"
                  @click="form.requestParams!.splice(Number(index), 1)"
                />
              </span>
            </div>
            <div
              v-if="(form.requestParams ?? []).length === 0"
              class="wizard__empty"
            >
              暂无请求参数
            </div>
          </div>

          <div class="wizard__section">
            <span>返回字段</span>
            <div class="flex gap-2">
              <Button
                v-if="form.configType === 'TABLE'"
                size="small"
                @click="openColumnPick('field')"
              >
                <template #icon>
                  <IconifyIcon icon="lucide:list-plus" />
                </template>
                从表选择
              </Button>
              <Button size="small" type="primary" @click="addField">
                <template #icon><IconifyIcon icon="lucide:plus" /></template>
                新增字段
              </Button>
            </div>
          </div>
          <div class="wizard__rows wizard__rows--scroll">
            <div class="wizard__row wizard__row--head wizard__row--wide">
              <span class="w-36">列名</span>
              <span class="w-32">输出名称(映射)</span>
              <span class="w-28">中文名称</span>
              <span class="flex-1">描述</span>
              <span class="w-24">数据类型</span>
              <span class="w-32">数据模型</span>
              <span class="w-32">模型规则</span>
              <span class="w-14">脱敏</span>
              <span class="w-28">示例值</span>
              <span class="w-10">操作</span>
            </div>
            <div
              v-for="(field, index) in form.responseFields ?? []"
              :key="index"
              class="wizard__row wizard__row--wide"
            >
              <Input
                v-model:value="field.name"
                class="w-36"
                placeholder="列名"
              />
              <Input
                v-model:value="field.outputName"
                class="w-32"
                placeholder="如 stationCode"
              />
              <Input
                v-model:value="field.cnName"
                class="w-28"
                placeholder="中文名称"
              />
              <Input
                v-model:value="field.description"
                class="flex-1"
                placeholder="描述"
              />
              <Input
                v-model:value="field.dataType"
                class="w-24"
                placeholder="数据类型"
              />
              <Select
                v-model:value="field.model"
                :options="FIELD_MODEL_OPTIONS"
                class="w-32"
              />
              <Input
                v-model:value="field.modelRule"
                :placeholder="
                  FIELD_MODEL_RULE_PLACEHOLDER[field.model ?? ''] ?? '模型规则'
                "
                class="w-32"
              />
              <span class="flex w-14 justify-center">
                <Checkbox
                  :checked="field.desensitize === 1"
                  @update:checked="
                    (v: boolean) => (field.desensitize = v ? 1 : 0)
                  "
                />
              </span>
              <Input
                v-model:value="field.exampleValue"
                class="w-28"
                placeholder="示例值"
              />
              <span class="w-10">
                <IconifyIcon
                  class="cursor-pointer text-red-500"
                  icon="lucide:trash-2"
                  @click="form.responseFields!.splice(Number(index), 1)"
                />
              </span>
            </div>
            <div
              v-if="(form.responseFields ?? []).length === 0"
              class="wizard__empty"
            >
              暂无返回字段（为空时按数据表全部列返回；配置数据模型可实现字段隐藏/映射/拼接/替换/截取/分割/格式化/脱敏）
            </div>
          </div>
        </div>

        <!-- 步骤三：测试 -->
        <div v-show="step === 2" class="wizard__body">
          <div class="wizard__section">API 调用</div>
          <div class="form-grid">
            <div class="form-item">
              <label class="form-item__label">API 名称</label>
              <Input :value="form.name" disabled />
            </div>
            <div class="form-item">
              <label class="form-item__label">API 版本</label>
              <Input :value="form.version" disabled />
            </div>
            <div class="form-item">
              <label class="form-item__label">请求类型</label>
              <div class="flex h-8 items-center">
                <Tag color="geekblue">{{ form.method }}</Tag>
              </div>
            </div>
            <div class="form-item">
              <label class="form-item__label">调用地址</label>
              <Input
                :value="`/services/${form.version}${form.path ?? ''}`"
                disabled
              />
            </div>
          </div>

          <div class="wizard__section">请求数据</div>
          <div v-if="form.configType === 'ETL_BATCH'" class="form-item">
            <label class="form-item__label">afterRow 游标</label>
            <Input v-model:value="afterRow" placeholder="0" />
            <div class="form-item__tip">首批为 0，本次最多读取 10 行</div>
          </div>
          <div v-else class="wizard__rows">
            <div class="wizard__row wizard__row--head">
              <span class="w-40">参数名称</span>
              <span class="w-24">允许为空</span>
              <span class="flex-1">描述</span>
              <span class="w-28">参数类型</span>
              <span class="w-52">参数值</span>
            </div>
            <div
              v-for="param in form.requestParams ?? []"
              :key="param.name"
              class="wizard__row"
            >
              <span class="w-40">{{ param.name }}</span>
              <span class="w-24">{{ param.nullable === 1 ? '是' : '否' }}</span>
              <span class="text-muted-foreground flex-1">{{
                param.description || '-'
              }}</span>
              <span class="w-28">{{ param.paramType }}</span>
              <Input
                v-model:value="testParams[param.name!]"
                :placeholder="param.exampleValue || '请输入参数值'"
                class="w-52"
              />
            </div>
            <div
              v-if="(form.requestParams ?? []).length === 0"
              class="wizard__empty"
            >
              无请求参数
            </div>
          </div>
          <div class="mt-3">
            <Button
              v-access:code="'supply:data-api:test'"
              :loading="testing"
              type="primary"
              @click="handleTest"
            >
              <template #icon><IconifyIcon icon="lucide:play" /></template>
              只读试调
            </Button>
            <Button
              v-if="form.configType === 'ETL_BATCH' && testResult?.hasMore"
              :disabled="testing"
              class="ml-2"
              @click="
                () => {
                  afterRow = String(testResult?.nextAfterRow ?? '0');
                  void handleTest();
                }
              "
            >
              读取下一批
            </Button>
          </div>

          <div class="wizard__section">
            返回数据
            <span v-if="testResult" class="text-muted-foreground text-xs">
              {{ form.configType === 'ETL_BATCH' ? '本次' : '共' }}
              {{ testResult.total ?? testResult.rows?.length ?? 0 }} 条，耗时
              {{ testResult.durationMs }} 毫秒
            </span>
          </div>
          <Table
            class="supply-font-table"
            v-if="testResult?.rows?.length"
            :columns="testResultColumns"
            :data-source="testResult.rows"
            :pagination="false"
            :scroll="{ x: 'max-content' }"
            size="small"
          />
          <pre v-else-if="testResult?.raw" class="wizard__raw">{{
            testResult.raw
          }}</pre>
          <div v-else class="wizard__empty">暂无数据</div>
        </div>
      </div>

      <!-- 底部操作 -->
      <div class="wizard__footer">
        <span class="text-muted-foreground text-xs">
          第 {{ step + 1 }} / {{ steps.length }} 步 · {{ steps[step]?.title }}
        </span>
        <div class="flex shrink-0 items-center gap-2">
          <Button :disabled="saving" @click="visible = false">返回列表</Button>
          <Button
            v-if="step > 0"
            :disabled="busy"
            @click="handleStepChange(step - 1)"
          >
            上一步
          </Button>
          <Button
            v-if="step < 2"
            :disabled="busy"
            type="primary"
            @click="handleStepChange(step + 1)"
          >
            下一步
          </Button>
          <Button
            v-if="step > 0"
            :loading="saving"
            :disabled="initializing"
            type="primary"
            @click="handleSave(true)"
          >
            保存
          </Button>
        </div>
      </div>
    </div>

    <!-- 列选择弹窗 -->
    <Modal
      v-model:open="columnPickVisible"
      :title="
        columnPickMode === 'param'
          ? '请求参数（从表选择列）'
          : '返回字段（从表选择列）'
      "
      width="900px"
      @ok="confirmColumnPick"
    >
      <Table
        class="supply-font-table"
        :columns="columnPickColumns"
        :data-source="tableColumns"
        :loading="columnPickLoading"
        :pagination="false"
        :row-selection="{
          selectedRowKeys: pickedColumnKeys,
          onChange: (keys: any[]) => (pickedColumnKeys = keys as string[]),
        }"
        :scroll="{ y: 380 }"
        row-key="name"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'pk'">
            {{ (record as any).pk === 1 ? '是' : '否' }}
          </template>
          <template v-else-if="column.key === 'nullable'">
            {{ (record as any).nullable === 1 ? '是' : '否' }}
          </template>
        </template>
      </Table>
    </Modal>
  </section>
</template>

<style scoped>
.supply-font-table :deep(.ant-table-thead > tr > th),
.supply-font-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.wizard {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  min-height: 0;
}

.wizard__rail {
  flex-shrink: 0;
  padding: 26px max(32px, 8%);
  background: hsl(var(--background));
}

.wizard__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  background: hsl(var(--background));
}

.wizard__header {
  flex-shrink: 0;
  padding: 24px 32px 0;
}

.wizard__title {
  padding-left: 10px;
  font-size: 16px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.wizard__hint {
  margin-top: 12px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.wizard__content {
  flex: 1;
  min-height: 0;
  padding: 24px 32px 40px;
  overflow: auto;
}

.wizard__section {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding-left: 10px;
  margin: 24px 0;
  font-size: 14px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.wizard__body > .wizard__section:first-child {
  margin-top: 8px;
}

.wizard__sql {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.wizard__rows {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.wizard__rows--scroll {
  overflow-x: auto;
}

.wizard__row--wide {
  min-width: 1180px;
}

.wizard__row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px 10px;
  font-size: 12px;
  border-bottom: 1px solid hsl(var(--border));
}

.wizard__row:last-child {
  border-bottom: none;
}

.wizard__row--head {
  font-size: 12px;
  font-weight: 600;
  background: hsl(var(--muted) / 50%);
}

.wizard__empty {
  padding: 24px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  text-align: center;
}

.wizard__raw {
  max-height: 320px;
  padding: 10px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  background: hsl(var(--muted) / 40%);
  border-radius: 6px;
}

.wizard__footer {
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 18px 32px;
  border-top: 1px solid hsl(var(--border));
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 48px;
}

.form-item {
  display: grid;
  grid-template-columns: 126px minmax(0, 1fr);
  gap: 8px 12px;
  align-content: start;
  align-items: start;
  min-width: 0;
}

.form-item > :not(.form-item__label) {
  grid-column: 2;
  min-width: 0;
}

.form-item--full {
  grid-column: 1 / -1;
}

.form-item__label {
  padding-top: 5px;
  font-size: 14px;
  font-weight: 500;
  text-align: right;
}

.form-item__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}

.form-item__tip {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

@media (max-width: 1024px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }
}

@media (max-width: 768px) {
  .wizard__rail {
    padding: 12px;
    overflow-x: auto;
  }

  .wizard__header {
    padding: 18px 20px 0;
  }

  .wizard__content {
    padding: 16px 20px 24px;
  }

  .wizard__footer {
    flex-wrap: wrap;
    padding: 16px 20px;
  }

  .form-item {
    grid-template-columns: minmax(0, 1fr);
  }

  .form-item > :not(.form-item__label) {
    grid-column: 1;
  }

  .form-item__label {
    padding-top: 0;
    text-align: left;
  }
}
</style>
