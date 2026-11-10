<script setup lang="ts">
import type { SupplyApiCategoryApi } from '#/api/supply/apicategory';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { computed, h, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Descriptions,
  DescriptionsItem,
  Table,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import { getApiCategoryList } from '#/api/supply/apicategory';
import { getDataApi } from '#/api/supply/dataapi';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import ListPagination from '#/components/list-pagination/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import { formatSupplyDateTime } from '../utils/format';
import {
  API_STATUS_OPTIONS,
  API_TYPE_OPTIONS,
  AUTH_TYPE_OPTIONS,
  CONFIG_TYPE_OPTIONS,
  FIELD_MODEL_OPTIONS,
  IP_RULE_TYPE_OPTIONS,
  METHOD_OPTIONS,
  optionColor,
  optionLabel,
  PARAM_TYPE_OPTIONS,
  RESULT_FORMAT_OPTIONS,
} from './data';

import '#/components/detail-fill-table.css';

defineOptions({ name: 'SupplyDataApiDetailPage' });

const route = useRoute();
const router = useRouter();
const { closeCurrentTab } = useTabs();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const id = computed(() => parseResourceId(route.params.id));
const canQuery = computed(() => hasAccessByCodes(['supply:data-api:query']));
const canQueryCategory = computed(() =>
  hasAccessByCodes(['supply:api-category:query']),
);
const detail = ref<SupplyDataApiApi.DataApi>();
const loading = ref(false);
const error = ref('');
const activeTab = ref('params');
const categories = ref<SupplyApiCategoryApi.ApiCategory[]>([]);
const categoryState = ref<'failed' | 'loading' | 'ready' | 'unavailable'>(
  'unavailable',
);
const guard = createLatestWins();
let active = true;

const categoryName = computed(() => {
  const categoryId = detail.value?.categoryId;
  if (categoryId === undefined || categoryId === null) return '-';
  const category = categories.value.find(
    (item) => String(item.id) === String(categoryId),
  );
  if (category?.name) return category.name;
  let suffix = '';
  if (categoryState.value === 'loading') suffix = '（名称加载中）';
  else if (categoryState.value === 'failed') suffix = '（名称暂不可用）';
  return `类目 ${categoryId}${suffix}`;
});
const requestRows = computed(() =>
  (detail.value?.requestParams ?? []).map((item, index) => ({
    ...item,
    _key: `${id.value}:param:${index}:${item.name ?? ''}`,
  })),
);
const responseRows = computed(() =>
  (detail.value?.responseFields ?? []).map((item, index) => ({
    ...item,
    _key: `${id.value}:field:${index}:${item.name ?? ''}`,
  })),
);
const requestPage = reactive({ current: 1, pageSize: 10 });
const responsePage = reactive({ current: 1, pageSize: 10 });

function changeRequestPage(current: number, pageSize: number) {
  Object.assign(requestPage, { current, pageSize });
}

function changeResponsePage(current: number, pageSize: number) {
  Object.assign(responsePage, { current, pageSize });
}
const requestColumns = [
  { title: '参数名称', dataIndex: 'name', key: 'name', width: 180 },
  { title: '参数类型', key: 'paramType', width: 100 },
  { title: '是否必填', key: 'nullable', width: 100 },
  {
    title: '示例值',
    dataIndex: 'exampleValue',
    key: 'exampleValue',
    width: 180,
  },
  {
    title: '默认值',
    dataIndex: 'defaultValue',
    key: 'defaultValue',
    width: 180,
  },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    width: 240,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
];
const responseColumns = [
  { title: '字段名称', dataIndex: 'name', key: 'name', width: 160 },
  { title: '输出名称', dataIndex: 'outputName', key: 'outputName', width: 160 },
  { title: '中文名称', dataIndex: 'cnName', key: 'cnName', width: 160 },
  { title: '数据类型', dataIndex: 'dataType', key: 'dataType', width: 120 },
  { title: '时间格式', dataIndex: 'timeFormat', key: 'timeFormat', width: 180 },
  {
    title: '示例值',
    dataIndex: 'exampleValue',
    key: 'exampleValue',
    width: 180,
  },
  { title: '数据模型', key: 'model', width: 120 },
  { title: '模型规则', dataIndex: 'modelRule', key: 'modelRule', width: 200 },
  { title: '是否脱敏', key: 'desensitize', width: 100 },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    width: 240,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
];
const infoFields = computed(() => {
  const row = detail.value;
  if (!row) return [];
  const configFields: {
    key: string;
    label: string;
    span?: 'filled';
    value?: number | string;
  }[] = [];
  if (row.configType === 'TABLE' || row.configType === 'SQL') {
    configFields.push({
      key: 'databaseId',
      label: '数据源编号',
      value: row.databaseId,
    });
  }
  if (row.configType === 'TABLE') {
    configFields.push({
      key: 'tableName',
      label: '数据表名',
      value: row.tableName,
    });
  } else if (row.configType === 'SQL') {
    configFields.push({
      key: 'sqlText',
      label: 'SQL脚本',
      value: row.sqlText,
      span: 'filled',
    });
  } else if (row.configType === 'FORWARD') {
    configFields.push(
      {
        key: 'forwardUrl',
        label: '转发地址',
        value: row.forwardUrl,
        span: 'filled',
      },
      {
        key: 'forwardMethod',
        label: '转发请求方式',
        value: displayOption(METHOD_OPTIONS, row.forwardMethod || row.method),
      },
    );
  } else if (row.configType === 'ETL_BATCH') {
    configFields.push({
      key: 'etlBatchId',
      label: 'ETL批次编号',
      value: row.etlBatchId,
    });
  }
  return [
    {
      key: 'authType',
      label: '认证方式',
      value: displayOption(AUTH_TYPE_OPTIONS, row.authType),
    },
    {
      key: 'ipRuleType',
      label: 'IP规则类型',
      value: displayOption(IP_RULE_TYPE_OPTIONS, row.ipRuleType),
    },
    { key: 'ipWhitelist', label: 'IP名单', value: row.ipWhitelist },
    {
      key: 'cacheEnabled',
      label: '结果缓存',
      value: enabledText(row.cacheEnabled),
    },
    { key: 'cacheSeconds', label: '缓存时长（秒）', value: row.cacheSeconds },
    {
      key: 'alarmEnabled',
      label: '告警通知',
      value: enabledText(row.alarmEnabled),
    },
    {
      key: 'limitEnabled',
      label: '限流',
      value: enabledText(row.limitEnabled),
    },
    { key: 'limitSeconds', label: '限流窗口（秒）', value: row.limitSeconds },
    { key: 'limitCount', label: '窗口请求上限', value: row.limitCount },
    {
      key: 'breakerEnabled',
      label: '熔断',
      value: enabledText(row.breakerEnabled),
    },
    {
      key: 'breakerErrorCount',
      label: '熔断异常阈值',
      value: row.breakerErrorCount,
    },
    {
      key: 'breakerWindowSeconds',
      label: '熔断窗口（秒）',
      value: row.breakerWindowSeconds,
    },
    {
      key: 'breakerOpenSeconds',
      label: '熔断时长（秒）',
      value: row.breakerOpenSeconds,
    },
    {
      key: 'maxConcurrency',
      label: '最大并发',
      value: row.maxConcurrency || '不限制',
    },
    {
      key: 'timeoutSeconds',
      label: '查询超时（秒）',
      value: row.timeoutSeconds ?? '平台默认',
    },
    ...configFields,
    {
      key: 'remark',
      label: '备注',
      value: row.remark,
      span: 'filled' as const,
    },
  ];
});

function displayOption(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.some((item) => item.value === value)
    ? optionLabel(options, value)
    : (value ?? '-');
}

function enabledText(value?: number) {
  if (value === 1) return '开启';
  if (value === 0) return '关闭';
  return value ?? '-';
}

function resetDetail() {
  guard.invalidate();
  detail.value = undefined;
  loading.value = false;
  error.value = '';
  categories.value = [];
  categoryState.value = 'unavailable';
  activeTab.value = 'params';
  changeRequestPage(1, 10);
  changeResponsePage(1, 10);
}

async function loadCategories(isCurrent: () => boolean) {
  if (!canQueryCategory.value) return;
  categoryState.value = 'loading';
  try {
    const rows = await getApiCategoryList({});
    if (isCurrent()) {
      categories.value = rows;
      categoryState.value = 'ready';
    }
  } catch {
    if (isCurrent()) categoryState.value = 'failed';
  }
}

async function loadDetail() {
  resetDetail();
  if (!canQuery.value) {
    error.value = '无API查询权限';
    return;
  }
  const apiId = id.value;
  if (!apiId) {
    error.value = '无效的API编号';
    return;
  }
  const ticket = guard.next();
  const isCurrent = () =>
    active && ticket.isCurrent() && canQuery.value && id.value === apiId;
  loading.value = true;
  void loadCategories(isCurrent);
  try {
    const row = await getDataApi(apiId);
    if (!isCurrent()) return;
    if (parseResourceId(row?.id) !== apiId) {
      error.value = 'API详情身份不匹配，请返回列表后重新打开';
      return;
    }
    detail.value = row;
  } catch {
    if (isCurrent()) error.value = 'API详情加载失败，请返回列表后重试';
  } finally {
    if (isCurrent()) loading.value = false;
  }
}

async function handleBack() {
  const current = router.currentRoute.value;
  await router.push('/supply/data-api');
  await closeCurrentTab(current);
}

// 身份变化时同步失效，防止旧请求在ID、用户、会话或权限往返切换后写回。
watch(
  () => [
    id.value,
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    access.accessCodes.join('\0'),
    canQuery.value,
    canQueryCategory.value,
  ],
  () => void loadDetail(),
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(() => {
  active = false;
  resetDetail();
});
</script>

<template>
  <Page auto-content-height content-class="api-detail-content">
    <div class="api-profile" :aria-busy="loading">
      <section class="api-profile__summary" aria-label="API概要">
        <div class="api-profile__head">
          <div class="api-profile__identity">
            <span v-if="detail" class="api-profile__id">{{ detail.id }}</span>
            <h1 class="api-profile__name">
              {{ detail?.name || 'API服务详情' }}
            </h1>
            <Tag
              v-if="detail"
              :color="optionColor(API_STATUS_OPTIONS, detail.status)"
            >
              {{ displayOption(API_STATUS_OPTIONS, detail.status) }}
            </Tag>
            <Tag v-if="detail?.version" color="blue">{{ detail.version }}</Tag>
            <Tag v-if="detail?.unpublishedDraft" color="warning">
              未发布草稿
            </Tag>
          </div>
          <DetailBackButton @click="handleBack" />
        </div>
        <Alert v-if="error" type="error" :message="error" show-icon />
        <p v-if="loading" role="status">正在加载API详情…</p>
        <Descriptions
          v-if="detail"
          class="api-profile__info"
          :column="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3, xxl: 3 }"
          bordered
          size="small"
        >
          <DescriptionsItem label="API名称">
            {{ detail.name || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="API版本">
            {{ detail.version || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="所属类目">
            <Tag color="processing">{{ categoryName }}</Tag>
          </DescriptionsItem>
          <DescriptionsItem label="请求方式">
            <Tag color="blue">
              {{ displayOption(METHOD_OPTIONS, detail.method) }}
            </Tag>
          </DescriptionsItem>
          <DescriptionsItem label="返回格式">
            {{ displayOption(RESULT_FORMAT_OPTIONS, detail.resultFormat) }}
          </DescriptionsItem>
          <DescriptionsItem label="接口类型">
            {{ displayOption(API_TYPE_OPTIONS, detail.apiType) }}
          </DescriptionsItem>
          <DescriptionsItem label="配置方式">
            {{ displayOption(CONFIG_TYPE_OPTIONS, detail.configType) }}
          </DescriptionsItem>
          <DescriptionsItem label="创建时间">
            {{ formatSupplyDateTime(detail.createTime) || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="更新时间">
            {{ formatSupplyDateTime(detail.updateTime) || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="API路径" span="filled">
            <span class="font-mono">{{ detail.path || '-' }}</span>
          </DescriptionsItem>
          <DescriptionsItem label="描述" span="filled">
            {{ detail.description || '-' }}
          </DescriptionsItem>
        </Descriptions>
      </section>
      <section
        v-if="detail"
        class="api-profile__attributes"
        :class="{ 'api-profile__attributes--info': activeTab === 'info' }"
        aria-label="API参数与详细信息"
      >
        <Tabs v-model:active-key="activeTab" class="api-profile__tabs">
          <TabPane key="params" tab="请求参数">
            <Table
              class="api-profile__table supply-font-table detail-fill-table"
              :columns="requestColumns"
              :data-source="requestRows"
              :pagination="{
                current: requestPage.current,
                pageSize: requestPage.pageSize,
                placement: ['none'],
                onChange: changeRequestPage,
              }"
              :scroll="{ x: 980 }"
              :styles="{ content: { overflowY: 'auto' } }"
              row-key="_key"
              size="small"
            >
              <template #bodyCell="{ column, record, text }">
                <template v-if="column.key === 'paramType'">
                  {{ displayOption(PARAM_TYPE_OPTIONS, record.paramType) }}
                </template>
                <template v-else-if="column.key === 'nullable'">
                  {{
                    record.nullable === 0
                      ? '必填'
                      : record.nullable === 1
                        ? '可选'
                        : (record.nullable ?? '-')
                  }}
                </template>
                <template v-else>{{ text ?? '-' }}</template>
              </template>
            </Table>
            <ListPagination
              :current="requestPage.current"
              :page-size="requestPage.pageSize"
              :page-size-options="[10, 20, 50, 100]"
              :total="requestRows.length"
              aria-label="请求参数分页"
              @change="changeRequestPage"
            />
          </TabPane>
          <TabPane key="fields" tab="返回字段">
            <Table
              class="api-profile__table supply-font-table detail-fill-table"
              :columns="responseColumns"
              :data-source="responseRows"
              :pagination="{
                current: responsePage.current,
                pageSize: responsePage.pageSize,
                placement: ['none'],
                onChange: changeResponsePage,
              }"
              :scroll="{ x: 1720 }"
              :styles="{ content: { overflowY: 'auto' } }"
              row-key="_key"
              size="small"
            >
              <template #bodyCell="{ column, record, text }">
                <template v-if="column.key === 'model'">
                  {{ displayOption(FIELD_MODEL_OPTIONS, record.model ?? '') }}
                </template>
                <template v-else-if="column.key === 'desensitize'">
                  {{
                    record.desensitize === 1
                      ? '是'
                      : record.desensitize === 0
                        ? '否'
                        : (record.desensitize ?? '-')
                  }}
                </template>
                <template v-else>{{ text ?? '-' }}</template>
              </template>
            </Table>
            <ListPagination
              :current="responsePage.current"
              :page-size="responsePage.pageSize"
              :page-size-options="[10, 20, 50, 100]"
              :total="responseRows.length"
              aria-label="返回字段分页"
              @change="changeResponsePage"
            />
          </TabPane>
          <TabPane key="info" tab="详细信息">
            <Descriptions
              class="api-profile__info api-profile__details"
              :column="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3, xxl: 3 }"
              bordered
              size="small"
            >
              <DescriptionsItem
                v-for="field in infoFields"
                :key="field.key"
                :label="field.label"
                :span="field.span ?? 1"
              >
                {{ field.value ?? '-' }}
              </DescriptionsItem>
            </Descriptions>
          </TabPane>
        </Tabs>
      </section>
    </div>
  </Page>
</template>

<style scoped>
.supply-font-table :deep(.ant-table-thead > tr > th),
.supply-font-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

:deep(.api-detail-content) {
  display: flex;
  flex-direction: column;
}

.api-profile {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.api-profile__summary,
.api-profile__attributes {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.api-profile__summary {
  flex-shrink: 0;
}

.api-profile__attributes {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 300px;
  padding-top: 8px;
}

.api-profile__attributes:not(.api-profile__attributes--info) {
  padding-bottom: 16px;
}

.api-profile__head,
.api-profile__identity {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.api-profile__head {
  justify-content: space-between;
  margin-bottom: 20px;
}

.api-profile__identity {
  flex-wrap: wrap;
  min-width: 0;
}

.api-profile__id {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border-radius: calc(var(--radius) - 4px);
}

.api-profile__name {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.api-profile__info :deep(.ant-descriptions-item-label) {
  width: 140px;
  color: hsl(var(--muted-foreground));
}

.api-profile__info :deep(.ant-descriptions-item-content) {
  overflow-wrap: anywhere;
}

.api-profile__details :deep(.ant-descriptions-item-content) {
  white-space: pre-wrap;
}

.api-profile__tabs,
.api-profile__table {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.api-profile__tabs :deep(.ant-tabs-body-holder) {
  flex: 1;
  min-height: 0;
}

.api-profile__tabs :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

.api-profile__tabs :deep(.ant-tabs-body),
.api-profile__tabs :deep(.ant-tabs-content) {
  height: 100%;
}

.api-profile__tabs :deep(.ant-tabs-content-active) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.api-profile__attributes--info,
.api-profile__attributes--info .api-profile__tabs,
.api-profile__attributes--info :deep(.ant-tabs-body-holder) {
  flex: 1 0 auto;
}

.api-profile__attributes--info :deep(.ant-tabs-body),
.api-profile__attributes--info :deep(.ant-tabs-content) {
  height: auto;
}

.api-profile__table :deep(.ant-spin),
.api-profile__table :deep(.ant-spin-container),
.api-profile__table :deep(.ant-table) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.api-profile__table :deep(.ant-table-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.api-profile__table :deep(.ant-table-content) {
  flex: 1;
  min-height: 0;
  scrollbar-color: hsl(var(--muted-foreground) / 20%) transparent;
  scrollbar-width: thin;
}

.api-profile__table :deep(.ant-table-content::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.api-profile__table :deep(.ant-table-content::-webkit-scrollbar-track) {
  background: transparent;
}

.api-profile__table :deep(.ant-table-content::-webkit-scrollbar-thumb) {
  background: hsl(var(--muted-foreground) / 20%);
  border-radius: 999px;
}

.api-profile__table :deep(.ant-table-tbody > tr.ant-table-placeholder > td) {
  border-bottom: 0;
}

.api-profile__table :deep(.ant-table) {
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

@media (max-width: 640px) {
  .api-profile__summary,
  .api-profile__attributes {
    padding: 12px;
  }

  .api-profile__attributes:not(.api-profile__attributes--info) {
    padding-bottom: 12px;
  }

  .api-profile__head {
    align-items: flex-start;
  }

  .api-profile__info :deep(.ant-descriptions-item-label) {
    width: 100px;
  }
}
</style>
