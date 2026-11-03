<script lang="ts" setup>
import type { GovernanceMetaObjectApi } from '#/api/governance/metaobject';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  message,
  Popconfirm,
  Select,
  Table,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import {
  getMetaChangePage,
  getMetaColumnList,
  getMetaSnapshot,
  getMetaSnapshotList,
  getMetaTableDetail,
} from '#/api/governance/metaobject';
import { useObjectRequest } from '#/hooks/use-object-request';
import { prettyJson } from '#/views/governance/common/detail-helpers';
import TextPreview from '#/views/governance/common/text-preview.vue';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  CHANGE_OBJECT_TYPE_OPTIONS,
  CHANGE_SOURCE_OPTIONS,
  CHANGE_TYPE_OPTIONS,
  displayComment,
  formatSize,
  formatTime,
  OBJECT_STATUS_OPTIONS,
  optionColor,
  optionLabel,
  ORIGIN_OPTIONS,
  PUBLISH_STATUS_OPTIONS,
} from '../data';
import ColumnBizForm from './column-biz-form.vue';
import TableBizForm from './table-biz-form.vue';
import { useMetaTagEditor } from './use-meta-tag-editor';

defineOptions({ name: 'GovernanceMetaObjectDetail' });

const emit = defineEmits<{ (e: 'success'): void }>();

const detail = ref<GovernanceMetaObjectApi.MetaTable>();
const columns = ref<GovernanceMetaObjectApi.MetaColumn[]>([]);
const snapshots = ref<GovernanceMetaObjectApi.Snapshot[]>([]);
const changes = ref<GovernanceMetaObjectApi.Change[]>([]);
const changePagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const loading = ref(false);
const activeTab = ref('base');
const identity = ref('closed');
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const router = useRouter();
const canEdit = computed(
  () =>
    detail.value?.publishStatus !== 1 &&
    hasAccessByCodes(['governance:meta-table:update']),
);
const request = useObjectRequest(
  () => identity.value,
  () => {
    detail.value = undefined;
    columns.value = [];
    snapshots.value = [];
    changes.value = [];
    loading.value = false;
  },
);
const openRequest = useObjectRequest(() => identity.value);
const changesRequest = useObjectRequest(
  () =>
    `${identity.value}/${changePagination.pageNo}/${changePagination.pageSize}`,
);
const previewRequest = useObjectRequest(() => identity.value);

async function loadAll(tableId: number | string) {
  const current = request.begin();
  loading.value = true;
  try {
    const [detailData, columnList, snapshotList] = await Promise.all([
      getMetaTableDetail(tableId),
      getMetaColumnList(tableId),
      getMetaSnapshotList(tableId),
    ]);
    if (!current()) return;
    detail.value = detailData;
    columns.value = columnList;
    snapshots.value = snapshotList;
    await Promise.all([
      loadChanges(),
      detailData.id ? loadTags() : Promise.resolve(),
    ]);
  } finally {
    if (current()) loading.value = false;
  }
}

async function loadChanges() {
  if (!detail.value?.id) {
    return;
  }
  const current = changesRequest.begin();
  const result = await getMetaChangePage({
    pageNo: changePagination.pageNo,
    pageSize: changePagination.pageSize,
    tableId: detail.value.id,
  });
  if (current()) {
    changes.value = result.list ?? [];
    changePagination.total = Number(result.total ?? 0);
  }
}

function handleChangePageChange(page: number, pageSize: number) {
  changePagination.pageNo = page;
  changePagination.pageSize = pageSize;
  loadChanges();
}

/** 快照查看 */
const [TextPreviewModal, textPreviewModalApi] = useVbenModal({
  connectedComponent: TextPreview,
  destroyOnClose: true,
});

async function handleViewSnapshot(row: GovernanceMetaObjectApi.Snapshot) {
  const current = previewRequest.begin();
  const data = await getMetaSnapshot(row.id!);
  if (!current()) return;
  textPreviewModalApi
    .setData({
      title: `结构快照 - ${row.structVersion}`,
      text: prettyJson(data.snapshot ?? ''),
    })
    .open();
}

/** 业务属性编辑 */
const [TableBizModal, tableBizModalApi] = useVbenModal({
  connectedComponent: TableBizForm,
  destroyOnClose: true,
});
const [ColumnBizModal, columnBizModalApi] = useVbenModal({
  connectedComponent: ColumnBizForm,
  destroyOnClose: true,
});

function handleEditTableBiz() {
  tableBizModalApi.setData(detail.value).open();
}

function handleEditColumnBiz(row: GovernanceMetaObjectApi.MetaColumn) {
  columnBizModalApi.setData(row).open();
}

async function handleBizSuccess() {
  if (detail.value?.id) {
    await loadAll(detail.value.id);
  }
  emit('success');
}

/** 标签读取失败时保留当前展示，并禁止按空集覆盖。 */
const {
  boundTags,
  clearsAll: tagClearsAll,
  dirty: tagDirty,
  error: tagError,
  load: loadTags,
  loading: tagLoading,
  ready: tagsReady,
  save: saveTags,
  saving: tagSaving,
  selectedTagIds,
  tagOptions,
} = useMetaTagEditor(() => detail.value?.id);

async function handleSaveTags() {
  if (canEdit.value && (await saveTags())) message.success('标签已更新');
}

async function handleOpenStandard(row: GovernanceMetaObjectApi.MetaColumn) {
  if (!row.standardElementId) return;
  await router.push({
    name: 'GovernanceStandardElementDetail',
    params: { id: String(row.standardElementId) },
  });
  await modalApi.close();
}

async function handleViewPublished() {
  if (!detail.value?.publishedSnapshotId) return;
  await handleViewSnapshot({
    id: detail.value.publishedSnapshotId,
    structVersion: detail.value.publishedVersion,
  });
}

/** 字段列表列定义 */
const columnTableColumns = [
  { title: '#', dataIndex: 'ordinalPosition', key: 'ordinal', width: 50 },
  { title: '字段名称', dataIndex: 'columnName', key: 'columnName', width: 160 },
  { title: '注释', key: 'comment', width: 180 },
  {
    title: '业务名称',
    dataIndex: 'businessName',
    key: 'businessName',
    width: 120,
  },
  { title: '类型', dataIndex: 'dataType', key: 'dataType', width: 100 },
  { title: '长度', dataIndex: 'dataLength', key: 'dataLength', width: 70 },
  {
    title: '精度',
    dataIndex: 'dataPrecision',
    key: 'dataPrecision',
    width: 60,
  },
  { title: '小数位', dataIndex: 'dataScale', key: 'dataScale', width: 60 },
  { title: '可空', key: 'nullableFlag', width: 55 },
  { title: '主键', key: 'pkFlag', width: 55 },
  { title: '敏感', key: 'sensitiveFlag', width: 55 },
  {
    title: '标准数据元',
    key: 'standardElementId',
    width: 190,
  },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' as const },
];

/** 快照列表列定义 */
const snapshotTableColumns = [
  {
    title: '版本号',
    dataIndex: 'structVersion',
    key: 'structVersion',
    width: 100,
  },
  { title: '来源', key: 'changeSource', width: 80 },
  { title: '变更摘要', dataIndex: 'changeSummary', key: 'changeSummary' },
  { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'actions', width: 140, fixed: 'right' as const },
];

/** 变更明细列定义 */
const changeTableColumns = [
  { title: '对象', key: 'objectType', width: 60 },
  { title: '类型', key: 'changeType', width: 60 },
  { title: '来源', key: 'changeSource', width: 60 },
  { title: '属性', dataIndex: 'property', key: 'property', width: 110 },
  {
    title: '修改前',
    dataIndex: 'beforeValue',
    key: 'beforeValue',
    ellipsis: true,
  },
  {
    title: '修改后',
    dataIndex: 'afterValue',
    key: 'afterValue',
    ellipsis: true,
  },
  { title: '版本', key: 'version', width: 130 },
  { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
];

const getTitle = computed(() =>
  detail.value
    ? `表元数据 - ${detail.value.tableName}（${detail.value.structVersion}）`
    : '表元数据详情',
);

const baseFields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'dbName', label: '所属库', value: row?.dbName },
    {
      key: 'sourceSystemName',
      label: '来源系统',
      value: row?.sourceSystemName,
    },
    { key: 'layer', label: '所属分层', value: row?.layer },
    {
      key: 'sourceComment',
      label: '源库注释',
      value: row?.sourceComment,
    },
    {
      key: 'customComment',
      label: '平台注释',
      value: row?.customComment,
    },
    { key: 'businessName', label: '业务名称', value: row?.businessName },
    {
      key: 'subjectDomainName',
      label: '主题域',
      value: row?.subjectDomainName,
    },
    { key: 'dbType', label: '数据库类型', value: row?.dbType },
    { key: 'tableType', label: '表类型', value: row?.tableType },
    { key: 'engine', label: '存储引擎', value: row?.engine },
    { key: 'columnCount', label: '字段数量', value: row?.columnCount },
    { key: 'primaryKeys', label: '主键', value: row?.primaryKeys },
    {
      key: 'storageSize',
      label: '存储大小',
      value: formatSize(row?.storageSize),
    },
    {
      key: 'masterFlag',
      label: '是否主表',
      value: row?.masterFlag === 1 ? '是' : '否',
    },
    {
      key: 'sensitiveFlag',
      label: '是否敏感表',
      value: row?.sensitiveFlag === 1 ? '是' : '否',
    },
    {
      key: 'catalogLinked',
      label: '目录挂接',
      value: row?.catalogLinked === 1 ? '已挂接' : '未挂接',
    },
    { key: 'rowCount', label: '行数（估算）', value: row?.rowCount },
    {
      key: 'dataUpdateTime',
      label: '数据更新时间',
      value: formatTime(row?.dataUpdateTime),
    },
    {
      key: 'structChangeCount',
      label: '结构变更次数',
      value: row?.structChangeCount ?? 0,
    },
    {
      key: 'tbCreateTime',
      label: '源表创建时间',
      value: formatTime(row?.tbCreateTime),
    },
    {
      key: 'firstSeenTime',
      label: '首次发现',
      value: formatTime(row?.firstSeenTime),
    },
    {
      key: 'lastSeenTime',
      label: '最近确认',
      value: formatTime(row?.lastSeenTime),
    },
    {
      key: 'publishedVersion',
      label: '定版版本',
      value: row?.publishedVersion,
    },
    {
      key: 'publishTime',
      label: '发布时间',
      value: formatTime(row?.publishTime),
    },
    {
      key: 'lastStructChangeTime',
      label: '最近结构变更',
      value: formatTime(row?.lastStructChangeTime),
    },
    {
      key: 'businessDefinition',
      label: '业务定义',
      value: row?.businessDefinition,
      span: 2,
      type: 'textarea',
    },
  ];
});

const [ModalComponent, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  class: 'w-[960px]',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      identity.value = 'closed';
      openRequest.begin();
      void textPreviewModalApi.close();
      detail.value = undefined;
      columns.value = [];
      snapshots.value = [];
      changes.value = [];
      activeTab.value = 'base';
      changePagination.pageNo = 1;
      return;
    }
    const data = modalApi.getData() as {
      id?: number | string;
      referenceVersion?: string;
    };
    if (data?.id) {
      identity.value = `${data.id}/${data.referenceVersion ?? ''}`;
      const current = openRequest.begin();
      await loadAll(data.id);
      if (!current()) return;
      if (data.referenceVersion) {
        activeTab.value = 'versions';
        const referenced = snapshots.value.find(
          (item) => String(item.id) === data.referenceVersion,
        );
        if (referenced) await handleViewSnapshot(referenced);
        else message.warning('此引用的发布快照当前不可见，请核查对象有效性');
      }
    }
  },
});
watch(
  () => [access.accessToken, access.refreshToken],
  () => modalApi.close(),
);
</script>

<template>
  <ModalComponent :title="getTitle">
    <TableBizModal @success="handleBizSuccess" />
    <ColumnBizModal @success="handleBizSuccess" />
    <TextPreviewModal />
    <div class="px-2 pb-2">
      <div class="mb-2 flex items-center gap-2">
        <Tag
          :color="optionColor(PUBLISH_STATUS_OPTIONS, detail?.publishStatus)"
        >
          {{ optionLabel(PUBLISH_STATUS_OPTIONS, detail?.publishStatus) }}
        </Tag>
        <Tag :color="optionColor(OBJECT_STATUS_OPTIONS, detail?.objectStatus)">
          {{ optionLabel(OBJECT_STATUS_OPTIONS, detail?.objectStatus) }}
        </Tag>
        <Tag :color="optionColor(ORIGIN_OPTIONS, detail?.origin)">
          {{ optionLabel(ORIGIN_OPTIONS, detail?.origin) }}
        </Tag>
        <span class="text-muted-foreground font-mono text-xs">
          {{ detail?.resourceUrn }}
        </span>
        <div class="ml-auto">
          <Button
            v-if="canEdit"
            :disabled="!detail || loading"
            size="small"
            type="primary"
            @click="handleEditTableBiz"
          >
            <template #icon><IconifyIcon icon="lucide:edit" /></template>
            编辑业务属性
          </Button>
        </div>
      </div>
      <div
        v-if="detail"
        class="mb-3 grid gap-3 rounded border p-3 text-sm sm:grid-cols-3"
      >
        <div>
          <div class="text-muted-foreground mb-1">最新结构</div>
          <strong>{{ detail.structVersion || '-' }}</strong>
          <div>最近确认 {{ formatTime(detail.lastSeenTime) }}</div>
        </div>
        <div>
          <div class="text-muted-foreground mb-1">已发布定版</div>
          <strong>{{ detail.publishedVersion || '尚未定版' }}</strong
          ><Button
            v-if="detail.publishedSnapshotId"
            size="small"
            type="link"
            @click="handleViewPublished"
          >
            <template #icon><IconifyIcon icon="lucide:eye" /></template>
            查看冻结快照
          </Button>
          <div>
            {{
              detail.publishedVersion &&
              detail.structVersion !== detail.publishedVersion
                ? '最新结构已变化，现有发布引用保持原定版。'
                : '发布后供资产等场景稳定引用。'
            }}
          </div>
        </div>
        <div>
          <div class="text-muted-foreground mb-1">治理状态</div>
          <div>
            目录：{{ detail.catalogLinked === 1 ? '已挂接' : '未挂接' }}
          </div>
          <div>
            标准：已关联
            {{ columns.filter((column) => column.standardMappingId).length }} /
            {{ columns.length }} 个字段
          </div>
          <Button size="small" type="link" @click="activeTab = 'columns'">
            <template #icon><IconifyIcon icon="lucide:settings" /></template>
            维护字段治理信息
          </Button>
        </div>
      </div>
      <Tabs v-model:active-key="activeTab">
        <!-- 基本信息 -->
        <TabPane key="base" tab="基本信息">
          <DetailFieldForm :fields="baseFields" label-width="130px" />
          <div class="mt-3">
            <div class="mb-2 font-medium">标签</div>
            <Alert
              v-if="tagError"
              :message="tagError"
              class="mb-2"
              show-icon
              type="error"
            >
              <template #action>
                <Button
                  :disabled="tagSaving"
                  :loading="tagLoading"
                  size="small"
                  @click="loadTags"
                >
                  <template #icon>
                    <IconifyIcon icon="lucide:refresh-cw" />
                  </template>
                  重新加载
                </Button>
              </template>
            </Alert>
            <div class="mb-2 flex flex-wrap gap-1">
              <Tag
                v-for="tag in boundTags"
                :key="tag.id"
                :color="tag.color || 'blue'"
              >
                {{ tag.name }}
              </Tag>
              <span
                v-if="boundTags.length === 0 && tagsReady"
                class="text-muted-foreground text-sm"
              >
                尚未打标
              </span>
            </div>
            <div class="flex gap-2">
              <Select
                v-model:value="selectedTagIds"
                :disabled="!tagsReady || tagSaving || !canEdit"
                :loading="tagLoading"
                :options="tagOptions"
                allow-clear
                class="min-w-[280px] flex-1"
                mode="multiple"
                option-filter-prop="label"
                placeholder="选择标签后保存"
                show-search
              />
              <Popconfirm
                v-if="canEdit && tagClearsAll"
                title="确认清空该表的全部标签？"
                @confirm="handleSaveTags"
              >
                <Button
                  :disabled="!tagsReady || !tagDirty"
                  :loading="tagSaving"
                  danger
                >
                  <template #icon>
                    <IconifyIcon icon="lucide:trash-2" />
                  </template>
                  清空全部标签
                </Button>
              </Popconfirm>
              <Button
                v-else-if="canEdit"
                :disabled="!tagsReady || !tagDirty"
                :loading="tagSaving"
                type="primary"
                @click="handleSaveTags"
              >
                <template #icon>
                  <IconifyIcon icon="lucide:save" />
                </template>
                保存标签
              </Button>
            </div>
          </div>
        </TabPane>

        <!-- 字段列表 -->
        <TabPane key="columns" :tab="`字段列表（${columns.length}）`">
          <Table
            :columns="columnTableColumns"
            :data-source="columns"
            :loading="loading"
            :pagination="false"
            :scroll="{ x: 'max-content', y: 420 }"
            row-key="id"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'comment'">
                {{ displayComment(record as any) }}
              </template>
              <template v-else-if="column.key === 'nullableFlag'">
                <Tag
                  :color="
                    (record as any).nullableFlag === 1 ? 'default' : 'red'
                  "
                >
                  {{ (record as any).nullableFlag === 1 ? '是' : '否' }}
                </Tag>
              </template>
              <template v-else-if="column.key === 'pkFlag'">
                <Tag v-if="(record as any).pkFlag === 1" color="gold">主键</Tag>
                <span v-else>-</span>
              </template>
              <template v-else-if="column.key === 'sensitiveFlag'">
                <Tag v-if="(record as any).sensitiveFlag === 1" color="red">
                  敏感
                </Tag>
                <span v-else>-</span>
              </template>
              <template v-else-if="column.key === 'standardElementId'">
                <template
                  v-if="
                    (record as GovernanceMetaObjectApi.MetaColumn)
                      .standardElementId
                  "
                >
                  <Button
                    size="small"
                    type="link"
                    @click="
                      handleOpenStandard(
                        record as GovernanceMetaObjectApi.MetaColumn,
                      )
                    "
                  >
                    {{
                      (record as GovernanceMetaObjectApi.MetaColumn)
                        .standardElementName ||
                      `标准#${(record as GovernanceMetaObjectApi.MetaColumn).standardElementId}`
                    }}
                  </Button>
                  <div class="text-muted-foreground text-xs">
                    {{
                      (record as GovernanceMetaObjectApi.MetaColumn)
                        .standardElementCode || '-'
                    }}
                  </div>
                </template>
                <span v-else>-</span>
              </template>
              <template v-else-if="column.key === 'actions'">
                <Button
                  v-if="canEdit"
                  size="small"
                  type="link"
                  @click="handleEditColumnBiz(record as any)"
                >
                  <template #icon><IconifyIcon icon="lucide:edit" /></template>
                  编辑
                </Button>
              </template>
            </template>
          </Table>
        </TabPane>

        <!-- 版本与变更 -->
        <TabPane key="versions" tab="版本与变更">
          <div class="mb-2 font-medium">版本快照</div>
          <Table
            :columns="snapshotTableColumns"
            :data-source="snapshots"
            :pagination="false"
            :scroll="{ x: 'max-content', y: 200 }"
            row-key="id"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'changeSource'">
                <Tag
                  :color="
                    optionColor(
                      CHANGE_SOURCE_OPTIONS,
                      (record as any).changeSource,
                    )
                  "
                >
                  {{
                    optionLabel(
                      CHANGE_SOURCE_OPTIONS,
                      (record as any).changeSource,
                    )
                  }}
                </Tag>
              </template>
              <template v-else-if="column.key === 'createTime'">
                {{ formatTime((record as any).createTime) }}
              </template>
              <template v-else-if="column.key === 'actions'">
                <Button
                  size="small"
                  type="link"
                  @click="handleViewSnapshot(record as any)"
                >
                  <template #icon><IconifyIcon icon="lucide:eye" /></template>
                  查看快照
                </Button>
              </template>
            </template>
          </Table>
          <div class="mb-2 mt-4 font-medium">变更明细</div>
          <Table
            :columns="changeTableColumns"
            :data-source="changes"
            :pagination="{
              current: changePagination.pageNo,
              pageSize: changePagination.pageSize,
              total: changePagination.total,
              showSizeChanger: true,
              showTotal: (total: number) => `共 ${total} 条`,
              onChange: handleChangePageChange,
            }"
            row-key="id"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'objectType'">
                {{
                  optionLabel(
                    CHANGE_OBJECT_TYPE_OPTIONS,
                    (record as any).objectType,
                  )
                }}
              </template>
              <template v-else-if="column.key === 'changeType'">
                <Tag
                  :color="
                    optionColor(CHANGE_TYPE_OPTIONS, (record as any).changeType)
                  "
                >
                  {{
                    optionLabel(CHANGE_TYPE_OPTIONS, (record as any).changeType)
                  }}
                </Tag>
              </template>
              <template v-else-if="column.key === 'changeSource'">
                {{
                  optionLabel(
                    CHANGE_SOURCE_OPTIONS,
                    (record as any).changeSource,
                  )
                }}
              </template>
              <template v-else-if="column.key === 'version'">
                <span
                  v-if="
                    (record as any).versionFrom || (record as any).versionTo
                  "
                >
                  {{ (record as any).versionFrom ?? '-' }} →
                  {{ (record as any).versionTo ?? '-' }}
                </span>
                <span v-else>-</span>
              </template>
              <template v-else-if="column.key === 'createTime'">
                {{ formatTime((record as any).createTime) }}
              </template>
            </template>
          </Table>
        </TabPane>
      </Tabs>
    </div>
  </ModalComponent>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
