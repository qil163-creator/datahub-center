<script lang="ts" setup>
import type { GovernanceMetaObjectApi } from '#/api/governance/metaobject';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  message,
  Popconfirm,
  Select,
  Spin,
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
import DetailBackButton from '#/components/detail-back-button/index.vue';
import ListPagination from '#/components/list-pagination/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';
import { parseResourceId } from '#/utils/resource-id';
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

const props = defineProps<{
  embedded?: boolean;
  referenceVersion?: string;
  tableId?: number | string;
}>();
const emit = defineEmits<{ back: []; success: [] }>();

const detail = ref<GovernanceMetaObjectApi.MetaTable>();
const columns = ref<GovernanceMetaObjectApi.MetaColumn[]>([]);
const snapshots = ref<GovernanceMetaObjectApi.Snapshot[]>([]);
const changes = ref<GovernanceMetaObjectApi.Change[]>([]);
const changePagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const columnPagination = reactive({ pageNo: 1, pageSize: 10 });
const snapshotPagination = reactive({ pageNo: 1, pageSize: 10 });
const loading = ref(false);
const detailError = ref('');
const changesError = ref('');
const changesLoading = ref(false);
const activeTab = ref('base');
const identity = ref('closed');
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const router = useRouter();
const activated = ref(true);
const tableId = computed(() => parseResourceId(props.tableId));
const canReadEmbedded = computed(
  () =>
    activated.value &&
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['governance:meta-table:query']),
);
const requestIdentity = computed(() =>
  props.embedded
    ? JSON.stringify([
        tableId.value,
        props.referenceVersion,
        user.userInfo?.userId,
        user.userRoles,
        access.accessToken,
        access.refreshToken,
        access.accessCodes,
        canReadEmbedded.value,
        activated.value,
      ])
    : identity.value,
);
const canEdit = computed(
  () =>
    (!props.embedded || canReadEmbedded.value) &&
    detail.value?.publishStatus !== 1 &&
    hasAccessByCodes(['governance:meta-table:update']),
);
const descriptionColumns = { xs: 1, sm: 2, xl: 3 };
const visibleColumns = computed(() =>
  props.embedded
    ? columns.value.slice(
        (columnPagination.pageNo - 1) * columnPagination.pageSize,
        columnPagination.pageNo * columnPagination.pageSize,
      )
    : columns.value,
);
const visibleSnapshots = computed(() =>
  props.embedded
    ? snapshots.value.slice(
        (snapshotPagination.pageNo - 1) * snapshotPagination.pageSize,
        snapshotPagination.pageNo * snapshotPagination.pageSize,
      )
    : snapshots.value,
);
function resetDetail() {
  detail.value = undefined;
  columns.value = [];
  snapshots.value = [];
  changes.value = [];
  loading.value = false;
  detailError.value = '';
  changesError.value = '';
  changesLoading.value = false;
  if (props.embedded) {
    activeTab.value = 'base';
    columnPagination.pageNo = 1;
    snapshotPagination.pageNo = 1;
    changePagination.pageNo = 1;
    changePagination.total = 0;
    closeChildModals();
  }
}
const request = useObjectRequest(() => requestIdentity.value, resetDetail);
const openRequest = useObjectRequest(() => requestIdentity.value);
const changesRequest = useObjectRequest(
  () =>
    `${requestIdentity.value}/${changePagination.pageNo}/${changePagination.pageSize}`,
);
const previewRequest = useObjectRequest(() => requestIdentity.value);

async function loadAll(tableId: number | string) {
  if (props.embedded && (!canReadEmbedded.value || !parseResourceId(tableId)))
    return false;
  const current = request.begin();
  loading.value = true;
  detailError.value = '';
  try {
    const [detailData, columnList, snapshotList] = await Promise.all([
      getMetaTableDetail(tableId),
      getMetaColumnList(tableId),
      getMetaSnapshotList(tableId),
    ]);
    if (!current()) return false;
    if (
      props.embedded &&
      (parseResourceId(detailData?.id) !== String(tableId) ||
        columnList.some(
          (row) =>
            row.tableId !== undefined &&
            parseResourceId(row.tableId) !== String(tableId),
        ) ||
        snapshotList.some(
          (row) =>
            row.tableId !== undefined &&
            parseResourceId(row.tableId) !== String(tableId),
        ))
    )
      throw new Error('元数据响应与当前表不匹配');
    detail.value = detailData;
    columns.value = columnList;
    snapshots.value = snapshotList;
    await Promise.all([
      loadChanges(),
      detailData.id ? loadTags() : Promise.resolve(),
    ]);
    return current();
  } catch (error) {
    if (!current()) return false;
    if (!props.embedded) throw error;
    detail.value = undefined;
    columns.value = [];
    snapshots.value = [];
    changes.value = [];
    detailError.value = '表元数据加载失败或响应对象不匹配，请重试。';
    return false;
  } finally {
    if (current()) loading.value = false;
  }
}

async function loadChanges() {
  if (!detail.value?.id) {
    return;
  }
  if (props.embedded && !canReadEmbedded.value) return;
  const current = changesRequest.begin();
  const id = detail.value.id;
  changesLoading.value = true;
  changesError.value = '';
  try {
    const result = await getMetaChangePage({
      pageNo: changePagination.pageNo,
      pageSize: changePagination.pageSize,
      tableId: id,
    });
    if (!current()) return;
    if (
      props.embedded &&
      result.list?.some(
        (row) =>
          row.tableId !== undefined &&
          parseResourceId(row.tableId) !== String(id),
      )
    )
      throw new Error('变更记录与当前表不匹配');
    changes.value = result.list ?? [];
    changePagination.total = Number(result.total ?? 0);
  } catch (error) {
    if (!current()) return;
    if (!props.embedded) throw error;
    changes.value = [];
    changesError.value = '变更明细读取失败，请重试。';
  } finally {
    if (current()) changesLoading.value = false;
  }
}

function handleChangePageChange(page: number, pageSize: number) {
  changePagination.pageNo =
    props.embedded && pageSize !== changePagination.pageSize ? 1 : page;
  changePagination.pageSize = pageSize;
  void loadChanges();
}

function handleColumnPageChange(page: number, pageSize: number) {
  columnPagination.pageNo = pageSize === columnPagination.pageSize ? page : 1;
  columnPagination.pageSize = pageSize;
}

function handleSnapshotPageChange(page: number, pageSize: number) {
  snapshotPagination.pageNo =
    pageSize === snapshotPagination.pageSize ? page : 1;
  snapshotPagination.pageSize = pageSize;
}

/** 快照查看 */
const [TextPreviewModal, textPreviewModalApi] = useVbenModal({
  connectedComponent: TextPreview,
  destroyOnClose: true,
});

async function handleViewSnapshot(row: GovernanceMetaObjectApi.Snapshot) {
  if (props.embedded && !canReadEmbedded.value) return;
  const current = previewRequest.begin();
  let data: GovernanceMetaObjectApi.Snapshot;
  try {
    data = await getMetaSnapshot(row.id!);
  } catch (error) {
    if (current() && !props.embedded) throw error;
    if (current()) message.error('结构快照读取失败，请重试。');
    return;
  }
  if (!current()) return;
  if (
    props.embedded &&
    (parseResourceId(data.id) !== parseResourceId(row.id) ||
      (data.tableId !== undefined &&
        parseResourceId(data.tableId) !== tableId.value))
  ) {
    message.error('结构快照与当前对象不匹配。');
    return;
  }
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
  if (props.embedded && (!canEdit.value || !detail.value)) return;
  tableBizModalApi.setData(detail.value).open();
}

function handleEditColumnBiz(row: GovernanceMetaObjectApi.MetaColumn) {
  if (props.embedded && !canEdit.value) return;
  columnBizModalApi.setData(row).open();
}

async function handleBizSuccess() {
  const current = openRequest.begin();
  if (props.embedded && !canReadEmbedded.value) return;
  if (detail.value?.id) {
    await loadAll(detail.value.id);
  }
  if (current()) emit('success');
}

function closeChildModals() {
  tableBizModalApi.setState({ isOpen: false });
  columnBizModalApi.setState({ isOpen: false });
  textPreviewModalApi.setState({ isOpen: false });
  void tableBizModalApi.close();
  void columnBizModalApi.close();
  void textPreviewModalApi.close();
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
} = useMetaTagEditor(() =>
  props.embedded && !canReadEmbedded.value ? undefined : detail.value?.id,
);

async function handleSaveTags() {
  const current = openRequest.begin();
  if (canEdit.value && (await saveTags()) && current())
    message.success('标签已更新');
}

async function handleOpenStandard(row: GovernanceMetaObjectApi.MetaColumn) {
  if (!row.standardElementId) return;
  if (props.embedded && !canReadEmbedded.value) return;
  await router.push({
    name: 'GovernanceStandardElementDetail',
    params: { id: String(row.standardElementId) },
  });
  if (!props.embedded) await modalApi.close();
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
    if (props.embedded) return;
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
async function loadEmbedded() {
  if (!props.embedded || !activated.value) return;
  if (!tableId.value) {
    detailError.value = '元数据表编号无效。';
    return;
  }
  if (
    props.referenceVersion !== undefined &&
    !parseResourceId(props.referenceVersion)
  ) {
    detailError.value = '引用的快照编号无效。';
    return;
  }
  if (!canReadEmbedded.value) {
    detailError.value = '缺少元数据查询权限或登录信息已失效。';
    return;
  }
  const current = openRequest.begin();
  if (!(await loadAll(tableId.value)) || !current()) return;
  if (!props.referenceVersion) return;
  activeTab.value = 'versions';
  const referenced = snapshots.value.find(
    (item) => String(item.id) === props.referenceVersion,
  );
  if (referenced) await handleViewSnapshot(referenced);
  else message.warning('此引用的发布快照当前不可见，请核查对象有效性');
}
watch(
  () => (props.embedded ? requestIdentity.value : undefined),
  () => {
    if (props.embedded) void loadEmbedded();
  },
  { immediate: true },
);
watch(
  () => columns.value.length,
  (total) => {
    columnPagination.pageNo = Math.min(
      columnPagination.pageNo,
      Math.max(1, Math.ceil(total / columnPagination.pageSize)),
    );
  },
);
watch(
  () => snapshots.value.length,
  (total) => {
    snapshotPagination.pageNo = Math.min(
      snapshotPagination.pageNo,
      Math.max(1, Math.ceil(total / snapshotPagination.pageSize)),
    );
  },
);
watch(
  () => [access.accessToken, access.refreshToken],
  () => {
    if (!props.embedded) void modalApi.close();
  },
);
onDeactivated(() => {
  if (props.embedded) activated.value = false;
});
onActivated(() => {
  if (props.embedded) activated.value = true;
});
onBeforeUnmount(() => {
  if (props.embedded) {
    activated.value = false;
    closeChildModals();
  }
});
</script>

<template>
  <component
    :is="embedded ? 'div' : ModalComponent"
    :title="embedded ? undefined : getTitle"
    :class="{ 'metadata-profile': embedded }"
  >
    <TableBizModal @success="handleBizSuccess" />
    <ColumnBizModal @success="handleBizSuccess" />
    <TextPreviewModal />
    <div :class="embedded ? 'metadata-profile__content' : 'px-2 pb-2'">
      <section :class="{ 'metadata-profile__summary': embedded }">
        <header v-if="embedded" class="metadata-profile__head">
          <div class="metadata-profile__identity">
            <span v-if="detail?.id" class="metadata-profile__id">{{
              detail.id
            }}</span>
            <h1 class="metadata-profile__name">
              {{ detail?.tableName || '表元数据详情' }}
            </h1>
            <Tag
              :color="
                optionColor(PUBLISH_STATUS_OPTIONS, detail?.publishStatus)
              "
            >
              {{ optionLabel(PUBLISH_STATUS_OPTIONS, detail?.publishStatus) }}
            </Tag>
            <Tag
              :color="optionColor(OBJECT_STATUS_OPTIONS, detail?.objectStatus)"
            >
              {{ optionLabel(OBJECT_STATUS_OPTIONS, detail?.objectStatus) }}
            </Tag>
            <Tag :color="optionColor(ORIGIN_OPTIONS, detail?.origin)">
              {{ optionLabel(ORIGIN_OPTIONS, detail?.origin) }}
            </Tag>
          </div>
          <DetailBackButton @click="emit('back')" />
        </header>
        <Alert
          v-if="embedded && detailError"
          :message="detailError"
          class="mb-3"
          type="error"
          show-icon
        >
          <template #action>
            <Button
              v-if="tableId && canReadEmbedded"
              size="small"
              @click="loadEmbedded"
              >
重试
</Button>
          </template>
        </Alert>
        <Spin
          v-if="embedded && loading && !detail"
          class="metadata-profile__loading"
        />
        <Descriptions
          v-if="embedded && detail"
          class="metadata-profile__info metadata-profile__overview"
          :column="descriptionColumns"
          bordered
          size="small"
        >
          <DescriptionsItem label="所属库">
{{
            detail.dbName || '-'
          }}
</DescriptionsItem>
          <DescriptionsItem label="来源系统">
{{
            detail.sourceSystemName || '-'
          }}
</DescriptionsItem>
          <DescriptionsItem label="数据库类型">
{{
            detail.dbType || '-'
          }}
</DescriptionsItem>
          <DescriptionsItem label="描述" span="filled">
{{
            displayComment(detail)
          }}
</DescriptionsItem>
        </Descriptions>
        <div
          v-if="!embedded || detail"
          class="mb-2 flex flex-wrap items-center gap-2"
        >
          <Tag
            v-if="!embedded"
            :color="optionColor(PUBLISH_STATUS_OPTIONS, detail?.publishStatus)"
          >
            {{ optionLabel(PUBLISH_STATUS_OPTIONS, detail?.publishStatus) }}
          </Tag>
          <Tag
            v-if="!embedded"
            :color="optionColor(OBJECT_STATUS_OPTIONS, detail?.objectStatus)"
          >
            {{ optionLabel(OBJECT_STATUS_OPTIONS, detail?.objectStatus) }}
          </Tag>
          <Tag
            v-if="!embedded"
            :color="optionColor(ORIGIN_OPTIONS, detail?.origin)"
          >
            {{ optionLabel(ORIGIN_OPTIONS, detail?.origin) }}
          </Tag>
          <span
            class="metadata-profile__urn text-muted-foreground font-mono text-xs"
          >
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
            <strong>{{ detail.publishedVersion || '尚未定版' }}</strong><Button
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
              {{
                columns.filter((column) => column.standardMappingId).length
              }}
              / {{ columns.length }} 个字段
            </div>
            <Button size="small" type="link" @click="activeTab = 'columns'">
              <template #icon><IconifyIcon icon="lucide:settings" /></template>
              维护字段治理信息
            </Button>
          </div>
        </div>
      </section>
      <section
        v-if="!embedded || detail"
        :class="{
          'metadata-profile__attributes': embedded,
          'metadata-profile__attributes--flow':
            embedded && activeTab !== 'columns',
          'metadata-profile__attributes--paged':
            embedded && (activeTab === 'columns' || activeTab === 'versions'),
        }"
      >
        <Tabs
          v-model:active-key="activeTab"
          :class="{ 'metadata-profile__tabs': embedded }"
        >
          <!-- 基本信息 -->
          <TabPane key="base" tab="基本信息">
            <Descriptions
              v-if="embedded"
              class="metadata-profile__info"
              :column="descriptionColumns"
              bordered
              size="small"
            >
              <DescriptionsItem
                v-for="field in baseFields"
                :key="field.key"
                :label="field.label"
                :span="field.span === 2 ? 'filled' : 1"
              >
                {{
                  field.value === null ||
                  field.value === undefined ||
                  field.value === ''
                    ? '-'
                    : field.value
                }}
              </DescriptionsItem>
            </Descriptions>
            <DetailFieldForm v-else :fields="baseFields" label-width="130px" />
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
              <div
                class="flex gap-2"
                :class="{ 'metadata-profile__tag-editor': embedded }"
              >
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
              :class="{ 'metadata-profile__table': embedded }"
              :columns="columnTableColumns"
              :data-source="visibleColumns"
              :loading="loading"
              :pagination="false"
              :scroll="
                embedded ? { x: 'max-content' } : { x: 'max-content', y: 420 }
              "
              :styles="
                embedded ? { content: { overflowY: 'auto' } } : undefined
              "
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
                  <Tag v-if="(record as any).pkFlag === 1" color="gold">
主键
</Tag>
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
                    <template #icon>
<IconifyIcon icon="lucide:edit" />
</template>
                    编辑
                  </Button>
                </template>
              </template>
            </Table>
            <ListPagination
              v-if="embedded"
              :current="columnPagination.pageNo"
              :page-size="columnPagination.pageSize"
              :total="columns.length"
              @change="handleColumnPageChange"
            />
          </TabPane>

          <!-- 版本与变更 -->
          <TabPane key="versions" tab="版本与变更">
            <div :class="{ 'metadata-profile__versions': embedded }">
              <section :class="{ 'metadata-profile__version-panel': embedded }">
                <div class="mb-2 font-medium">版本快照</div>
                <Table
                  :class="{ 'metadata-profile__table': embedded }"
                  :columns="snapshotTableColumns"
                  :data-source="visibleSnapshots"
                  :pagination="false"
                  :scroll="
                    embedded
                      ? { x: 'max-content' }
                      : { x: 'max-content', y: 200 }
                  "
                  :styles="
                    embedded ? { content: { overflowY: 'auto' } } : undefined
                  "
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
                        <template #icon>
<IconifyIcon icon="lucide:eye" />
</template>
                        查看快照
                      </Button>
                    </template>
                  </template>
                </Table>
                <ListPagination
                  v-if="embedded"
                  :current="snapshotPagination.pageNo"
                  :page-size="snapshotPagination.pageSize"
                  :total="snapshots.length"
                  @change="handleSnapshotPageChange"
                />
              </section>
              <section :class="{ 'metadata-profile__version-panel': embedded }">
                <div class="mb-2 mt-4 font-medium">变更明细</div>
                <Alert
                  v-if="embedded && changesError"
                  :message="changesError"
                  class="mb-2"
                  type="error"
                  show-icon
                >
                  <template #action>
<Button size="small" @click="loadChanges">
重试
</Button>
</template>
                </Alert>
                <Table
                  :class="{ 'metadata-profile__table': embedded }"
                  :columns="changeTableColumns"
                  :data-source="changes"
                  :loading="embedded ? changesLoading : undefined"
                  :pagination="
                    embedded
                      ? false
                      : {
                          current: changePagination.pageNo,
                          pageSize: changePagination.pageSize,
                          total: changePagination.total,
                          showSizeChanger: true,
                          showTotal: (total: number) => `共 ${total} 条`,
                          onChange: handleChangePageChange,
                        }
                  "
                  :scroll="embedded ? { x: 'max-content' } : undefined"
                  :styles="
                    embedded ? { content: { overflowY: 'auto' } } : undefined
                  "
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
                          optionColor(
                            CHANGE_TYPE_OPTIONS,
                            (record as any).changeType,
                          )
                        "
                      >
                        {{
                          optionLabel(
                            CHANGE_TYPE_OPTIONS,
                            (record as any).changeType,
                          )
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
                          (record as any).versionFrom ||
                          (record as any).versionTo
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
                <ListPagination
                  v-if="embedded"
                  :current="changePagination.pageNo"
                  :page-size="changePagination.pageSize"
                  :total="changePagination.total"
                  @change="handleChangePageChange"
                />
              </section>
            </div>
          </TabPane>
        </Tabs>
      </section>
    </div>
  </component>
</template>

<style scoped>
.metadata-profile,
.metadata-profile__content {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.metadata-profile__content {
  gap: 16px;
}

.metadata-profile__summary,
.metadata-profile__attributes {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.metadata-profile__summary {
  flex-shrink: 0;
}

.metadata-profile__head,
.metadata-profile__identity {
  display: flex;
  gap: 12px;
  align-items: center;
  min-width: 0;
}

.metadata-profile__head {
  justify-content: space-between;
  margin-bottom: 20px;
}

.metadata-profile__identity {
  flex: 1;
  flex-wrap: wrap;
}

.metadata-profile__id {
  max-width: 100%;
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--primary));
  overflow-wrap: anywhere;
  background: hsl(var(--primary) / 10%);
  border-radius: calc(var(--radius) - 4px);
}

.metadata-profile__name {
  max-width: 100%;
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.metadata-profile__urn {
  min-width: 0;
  overflow-wrap: anywhere;
}

.metadata-profile__overview {
  margin-bottom: 16px;
}

.metadata-profile__loading {
  display: block;
  margin: 20px auto;
}

.metadata-profile__info :deep(.ant-descriptions-item-label) {
  width: 140px;
  color: hsl(var(--muted-foreground));
}

.metadata-profile__info :deep(.ant-descriptions-item-content) {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.metadata-profile__attributes {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 320px;
  padding-top: 8px;
}

.metadata-profile__attributes--paged {
  padding-bottom: 16px;
}

.metadata-profile__tabs {
  display: flex;
  flex: 1;
  min-height: 0;
}

.metadata-profile__tabs :deep(.ant-tabs-body-holder) {
  flex: 1;
  min-height: 0;
}

.metadata-profile__tabs :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

.metadata-profile__tabs :deep(.ant-tabs-body),
.metadata-profile__tabs :deep(.ant-tabs-content) {
  height: 100%;
}

.metadata-profile__tabs :deep(.ant-tabs-content-active) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.metadata-profile__attributes--flow,
.metadata-profile__attributes--flow .metadata-profile__tabs,
.metadata-profile__attributes--flow :deep(.ant-tabs-body-holder) {
  flex: 1 0 auto;
}

.metadata-profile__attributes--flow :deep(.ant-tabs-body),
.metadata-profile__attributes--flow :deep(.ant-tabs-content) {
  height: auto;
}

.metadata-profile__versions,
.metadata-profile__version-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.metadata-profile__versions {
  flex: none;
  gap: 16px;
}

.metadata-profile__version-panel {
  flex: none;
}

.metadata-profile__version-panel .metadata-profile__table {
  flex: none;
}

.metadata-profile__table,
.metadata-profile__table :deep(.ant-spin),
.metadata-profile__table :deep(.ant-spin-container),
.metadata-profile__table :deep(.ant-table),
.metadata-profile__table :deep(.ant-table-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.metadata-profile__table :deep(.ant-table-content) {
  flex: 1;
  min-height: 0;
  overflow-y: auto !important;
  scrollbar-color: hsl(var(--muted-foreground) / 20%) transparent;
  scrollbar-width: thin;
}

.metadata-profile__table :deep(.ant-table-empty .ant-table-content > table) {
  height: 100%;
}

.metadata-profile__table :deep(.ant-table) {
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.metadata-profile__tag-editor {
  flex-wrap: wrap;
  min-width: 0;
}

.metadata-profile__tag-editor :deep(.ant-select) {
  flex: 1 1 280px;
  min-width: 0;
  max-width: 100%;
}

:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

@media (max-width: 640px) {
  .metadata-profile__summary,
  .metadata-profile__attributes {
    padding: 12px;
  }

  .metadata-profile__head {
    align-items: flex-start;
  }

  .metadata-profile__info :deep(.ant-descriptions-item-label) {
    width: 100px;
  }
}
</style>
