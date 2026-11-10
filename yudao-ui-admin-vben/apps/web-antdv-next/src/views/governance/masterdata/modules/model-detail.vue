<script setup lang="ts">
import type { TableColumnsType } from 'antdv-next';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Table,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import { getMasterModel } from '#/api/governance/masterdata';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import ListPagination from '#/components/list-pagination/index.vue';
import { createObjectRequestGuard } from '#/hooks/object-request-guard';
import { parseResourceId, sameResourceId } from '#/utils/resource-id';

import { fieldTypeLabel, MODEL_STATUS_OPTIONS } from '../data';

defineOptions({ name: 'GovernanceMasterModelProfile' });

const props = defineProps<{ modelId?: GovernanceMasterDataApi.Id }>();
const emit = defineEmits<{ back: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const modelId = computed(() => parseResourceId(props.modelId));
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['governance:master-model:query']),
);
const identity = computed(() =>
  JSON.stringify([
    modelId.value,
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    canQuery.value,
  ]),
);
const request = createObjectRequestGuard(() => identity.value);
const detail = ref<GovernanceMasterDataApi.Model>();
const loading = ref(false);
const error = ref('');
const activeTab = ref('fields');
const pagination = reactive({ current: 1, pageSize: 10 });
type Field = GovernanceMasterDataApi.Column & { rowKey: string };
const status = computed(() =>
  MODEL_STATUS_OPTIONS.find((item) => item.value === detail.value?.status),
);
const columns = computed<Field[]>(() =>
  (detail.value?.columns ?? []).map((field, index) => ({
    ...field,
    rowKey: field.id === undefined ? `field-${index}` : String(field.id),
  })),
);
const descriptionColumns = { xs: 1, sm: 1, md: 2, lg: 3, xl: 3, xxl: 3 };
const fieldColumns: TableColumnsType<Field> = [
  { title: '字段名', dataIndex: 'name', key: 'name', width: 180 },
  {
    title: '属性名称',
    dataIndex: 'commentStr',
    key: 'commentStr',
    width: 180,
  },
  {
    title: '类型',
    key: 'fieldType',
    width: 140,
    render: (_value, record) =>
      fieldTypeLabel(record.fieldType) === '-' && record.fieldType !== undefined
        ? `未知类型（${record.fieldType}）`
        : fieldTypeLabel(record.fieldType),
  },
  {
    title: '长度',
    key: 'fieldLength',
    width: 90,
    render: (_value, record) => record.fieldLength ?? '-',
  },
  {
    title: '小数位',
    key: 'fieldScale',
    width: 90,
    render: (_value, record) => record.fieldScale ?? '-',
  },
  {
    title: '主键',
    key: 'pk',
    width: 80,
    render: (_value, record) => flagLabel(record.pk),
  },
  {
    title: '可空',
    key: 'nullable',
    width: 80,
    render: (_value, record) => flagLabel(record.nullable),
  },
];

function flagLabel(value?: number) {
  if (value === 1) return '是';
  if (value === 0) return '否';
  return '-';
}

function changePage(current: number, pageSize: number) {
  pagination.current = pageSize === pagination.pageSize ? current : 1;
  pagination.pageSize = pageSize;
}

async function load() {
  const current = request.begin();
  const id = modelId.value;
  detail.value = undefined;
  loading.value = false;
  error.value = '';
  activeTab.value = 'fields';
  pagination.current = 1;
  pagination.pageSize = 10;
  if (!canQuery.value) {
    error.value = '无主数据模型查询权限或登录会话已失效。';
    return;
  }
  if (!id) {
    error.value = '无效的主数据模型编号。';
    return;
  }
  loading.value = true;
  try {
    const result = await getMasterModel(id);
    if (!current()) return;
    if (!sameResourceId(result?.id, id)) {
      error.value = '模型详情身份不匹配，请返回列表后重新打开。';
      return;
    }
    detail.value = result;
  } catch {
    if (current()) error.value = '主数据模型详情加载失败，请重试。';
  } finally {
    if (current()) loading.value = false;
  }
}

watch(identity, () => void load(), { immediate: true, flush: 'sync' });
onBeforeUnmount(() => request.dispose());
</script>

<template>
  <div class="master-model-profile" :aria-busy="loading">
    <section class="master-model-profile__summary" aria-label="主数据模型概要">
      <div class="master-model-profile__head">
        <div class="master-model-profile__identity">
          <span v-if="detail?.id" class="master-model-profile__id">{{
            detail.id
          }}</span>
          <h1 class="master-model-profile__name">
            {{ detail?.tableCn || '主数据模型详情' }}
          </h1>
          <Tag v-if="detail" :color="status?.color ?? 'default'">
            {{ status?.label ?? `未知状态（${detail.status ?? '-'}）` }}
          </Tag>
        </div>
        <DetailBackButton @click="emit('back')" />
      </div>
      <p v-if="loading" role="status">正在加载主数据模型详情…</p>
      <Alert v-if="error && (!/(?:加载|读取|获取).*失败/.test(error) || /保存|冲突|未确认/.test(error))" type="error" :message="error" show-icon>
        <template v-if="modelId && canQuery" #action>
          <Button size="small" :loading="loading" @click="load">
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            重试
          </Button>
        </template>
      </Alert>
      <Descriptions
        v-if="detail"
        class="master-model-profile__info"
        :column="descriptionColumns"
        bordered
        size="small"
      >
        <DescriptionsItem label="模型编码">
{{
          detail.tableName || '-'
        }}
</DescriptionsItem>
        <DescriptionsItem label="所属主数据域">
{{
          detail.domainName || '-'
        }}
</DescriptionsItem>
        <DescriptionsItem label="目标数据源">
{{
          detail.databaseName || '-'
        }}
</DescriptionsItem>
        <DescriptionsItem label="描述" span="filled">
<span class="whitespace-pre-wrap">{{
            detail.description || '-'
          }}</span>
</DescriptionsItem>
      </Descriptions>
    </section>
    <section
      v-if="detail"
      class="master-model-profile__attributes"
      :class="{
        'master-model-profile__attributes--info': activeTab === 'info',
        'master-model-profile__attributes--paged': activeTab === 'fields',
      }"
      aria-label="主数据模型字段与详细信息"
    >
      <Tabs v-model:active-key="activeTab" class="master-model-profile__tabs">
        <TabPane key="fields" :tab="`字段定义（${columns.length} 个）`">
          <Table
            class="master-model-profile__table"
            :columns="fieldColumns"
            :data-source="columns"
            :pagination="{
              current: pagination.current,
              pageSize: pagination.pageSize,
              placement: ['none'],
              onChange: changePage,
            }"
            :scroll="{ x: 840 }"
            :styles="{ content: { overflowY: 'auto' } }"
            row-key="rowKey"
            size="small"
          />
          <ListPagination
            :current="pagination.current"
            :page-size="pagination.pageSize"
            :total="columns.length"
            @change="changePage"
          />
        </TabPane>
        <TabPane key="info" tab="详细信息">
          <Descriptions
            class="master-model-profile__info master-model-profile__details"
            :column="descriptionColumns"
            bordered
            size="small"
          >
            <DescriptionsItem label="模型名称">
{{
              detail.tableCn || '-'
            }}
</DescriptionsItem>
            <DescriptionsItem label="模型编码 / 物理表名">
{{
              detail.tableName || '-'
            }}
</DescriptionsItem>
            <DescriptionsItem label="所属主数据域">
{{
              detail.domainName || '-'
            }}
</DescriptionsItem>
            <DescriptionsItem label="目标数据源">
{{
              detail.databaseName || '-'
            }}
</DescriptionsItem>
            <DescriptionsItem label="发布状态">
<Tag :color="status?.color ?? 'default'">
{{
                status?.label ?? `未知状态（${detail.status ?? '-'}）`
              }}
</Tag>
</DescriptionsItem>
            <DescriptionsItem label="版本">
{{
              detail.version === undefined ? '-' : `V${detail.version}`
            }}
</DescriptionsItem>
            <DescriptionsItem label="创建时间" span="filled">
{{
              formatDateTime(detail.createTime) || '-'
            }}
</DescriptionsItem>
            <DescriptionsItem label="模型描述" span="filled">
{{
              detail.description || '-'
            }}
</DescriptionsItem>
          </Descriptions>
        </TabPane>
      </Tabs>
    </section>
  </div>
</template>

<style scoped>
.master-model-profile {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.master-model-profile__summary,
.master-model-profile__attributes {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.master-model-profile__summary {
  flex-shrink: 0;
}

.master-model-profile__attributes {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 300px;
  padding-top: 8px;
}

.master-model-profile__attributes--paged {
  padding-bottom: 16px;
}

.master-model-profile__head,
.master-model-profile__identity {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.master-model-profile__head {
  justify-content: space-between;
  margin-bottom: 20px;
}

.master-model-profile__identity {
  min-width: 0;
}

.master-model-profile__id {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border-radius: calc(var(--radius) - 4px);
}

.master-model-profile__name {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.master-model-profile__info :deep(.ant-descriptions-item-label) {
  width: 140px;
  color: hsl(var(--muted-foreground));
}

.master-model-profile__info :deep(.ant-descriptions-item-content) {
  overflow-wrap: anywhere;
}

.master-model-profile__details :deep(.ant-descriptions-item-content) {
  white-space: pre-wrap;
}

.master-model-profile__tabs {
  display: flex;
  flex: 1;
  min-height: 0;
}

.master-model-profile__tabs :deep(.ant-tabs-body-holder) {
  flex: 1;
  min-height: 0;
}

.master-model-profile__tabs :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

.master-model-profile__tabs :deep(.ant-tabs-body),
.master-model-profile__tabs :deep(.ant-tabs-content) {
  height: 100%;
}

.master-model-profile__tabs :deep(.ant-tabs-content-active) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.master-model-profile__attributes--info,
.master-model-profile__attributes--info .master-model-profile__tabs,
.master-model-profile__attributes--info :deep(.ant-tabs-body-holder) {
  flex: 1 0 auto;
}

.master-model-profile__attributes--info :deep(.ant-tabs-body),
.master-model-profile__attributes--info :deep(.ant-tabs-content) {
  height: auto;
}

.master-model-profile__table,
.master-model-profile__table :deep(.ant-spin),
.master-model-profile__table :deep(.ant-spin-container),
.master-model-profile__table :deep(.ant-table),
.master-model-profile__table :deep(.ant-table-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.master-model-profile__table :deep(.ant-table-content) {
  flex: 1;
  min-height: 0;
  overflow-y: auto !important;
  scrollbar-color: hsl(var(--muted-foreground) / 20%) transparent;
  scrollbar-width: thin;
}

.master-model-profile__table :deep(.ant-table-empty .ant-table-content > table) {
  height: 100%;
}

.master-model-profile__table :deep(.ant-table) {
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.master-model-profile__table :deep(.ant-table-thead > tr > th),
.master-model-profile__table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

@media (max-width: 640px) {
  .master-model-profile__summary,
  .master-model-profile__attributes {
    padding: 12px;
  }

  .master-model-profile__head {
    align-items: flex-start;
  }

  .master-model-profile__info :deep(.ant-descriptions-item-label) {
    width: 100px;
  }
}
</style>
