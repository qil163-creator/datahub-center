<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';

import { computed, onMounted, onUnmounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  message,
  Select,
  Table,
  Tooltip,
} from 'antdv-next';

import { previewDataAsset } from '#/api/asset/dataasset';
import { DataWatermark } from '#/components/data-watermark';
import ListPagination from '#/components/list-pagination/index.vue';
import {
  indexMaskedColumns,
  renderMaskedColumnTitle,
} from '#/components/masked-column';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import {
  columnDisplayName,
  formatQueryCell,
  NO_VALUE_OPERATORS,
  PREVIEW_OPERATOR_OPTIONS,
} from '../../data';
import RowEditor from './row-editor.vue';
import RowHistory from './row-history.vue';

import '#/components/detail-fill-table.css';

defineOptions({ name: 'AssetMarketMapPreviewTab' });
const props = defineProps<{
  assetId: number | string;
  columns: DataAssetApi.MetadataColumn[];
  importedSnapshot?: ImportedAssetApi.Snapshot;
  techAvailable?: number;
}>();
interface FilterRow {
  enabled: boolean;
  column?: string;
  operator: string;
  value?: string;
}
interface PreviewRow {
  key: string;
  values: Record<string, unknown>;
  rowId?: string;
  revision?: number;
}
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
    props.assetId,
  ]),
);
const canRead = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:data-asset:preview']),
);
const canEdit = computed(
  () =>
    canRead.value &&
    !!props.importedSnapshot &&
    // 含脱敏值的结果不能作为编辑基线，避免把打码值写回。
    (preview.value.maskedColumns?.length ?? 0) === 0 &&
    hasAccessByCodes(['asset:data-asset:update']),
);
const available = computed(
  () => !!props.importedSnapshot || props.techAvailable !== 0,
);
const filters = ref<FilterRow[]>([]);
const loading = ref(false);
const loaded = ref(false);
const preview = ref<DataAssetApi.Preview>({});
const pageNo = ref(1);
const pageSize = ref(10);
const loadError = ref('');
const previewGuard = createLatestWins();
let alive = true;
const [RowEditorModal, rowEditorApi] = useVbenModal({
  connectedComponent: RowEditor,
  destroyOnClose: true,
});
const [RowHistoryModal, rowHistoryApi] = useVbenModal({
  connectedComponent: RowHistory,
  destroyOnClose: true,
});
const columnOptions = computed(() =>
  props.columns
    .filter((column) => column.columnName)
    .map((column) => ({
      label: `${column.columnName}（${columnDisplayName(column)}）`,
      value: column.columnName!,
    })),
);
const rows = computed<PreviewRow[]>(() =>
  (preview.value.rows ?? []).map((values, index) => ({
    key: preview.value.rowIds?.[index] ?? `page-${pageNo.value}-${index}`,
    rowId: preview.value.rowIds?.[index],
    revision: preview.value.rowRevisions?.[index],
    values,
  })),
);
const maskedIndex = computed(() =>
  indexMaskedColumns(preview.value.maskedColumns),
);
const tableColumns = computed(() => {
  const columns = (preview.value.columns ?? []).map((column) => {
    const meta = props.columns.find((item) => item.columnName === column.name);
    return {
      key: column.name,
      dataIndex: ['values', column.name],
      title: renderMaskedColumnTitle(
        `${column.label || column.name}${meta ? `（${columnDisplayName(meta)}）` : ''}`,
        maskedIndex.value.get(column.name),
      ),
      ellipsis: true,
      width: 180,
      typeName: column.typeName,
    };
  });
  return canEdit.value
    ? [
        ...columns,
        {
          key: '__actions',
          title: '操作',
          width: 180,
          fixed: 'right' as const,
        },
      ]
    : columns;
});
function addFilter() {
  if (filters.value.length >= 10) {
    message.warning('最多支持10个筛选条件');
    return;
  }
  filters.value.push({ enabled: true, operator: 'eq' });
}
function removeFilter(index: number) {
  filters.value.splice(index, 1);
}
function query() {
  pageNo.value = 1;
  void load();
}
function changePage(page: number, size: number) {
  pageNo.value = page;
  pageSize.value = size;
  void load();
}
function resetFilters() {
  filters.value = [];
  query();
}
function editRow(row: PreviewRow) {
  if (!canEdit.value || !row.rowId || row.revision === undefined) return;
  rowEditorApi
    .setData({
      assetId: props.assetId,
      rowId: row.rowId,
      revision: row.revision,
      values: row.values,
      columns: props.columns,
    })
    .open();
}
function showHistory(rowId?: string) {
  if (!canEdit.value) return;
  rowHistoryApi.setData({ id: props.assetId, rowId }).open();
}
function formatCell(row: PreviewRow, key: unknown) {
  if (typeof key !== 'string') return '';
  const meta = preview.value.columns?.find((column) => column.name === key);
  return formatQueryCell(row.values[key], meta?.typeName);
}
async function load() {
  if (!alive || !canRead.value || !available.value) return;
  const ticket = previewGuard.next();
  loading.value = true;
  loadError.value = '';
  try {
    const effective = filters.value
      .filter(
        (row) =>
          row.enabled &&
          row.column &&
          (NO_VALUE_OPERATORS.has(row.operator) || row.value?.trim()),
      )
      .map((row) => ({
        column: row.column!,
        operator: row.operator,
        value: NO_VALUE_OPERATORS.has(row.operator)
          ? undefined
          : row.value?.trim(),
      }));
    const data = await previewDataAsset(
      props.assetId,
      pageSize.value,
      effective,
      undefined,
      { pageNo: pageNo.value, pageSize: pageSize.value },
    );
    if (!ticket.isCurrent()) return;
    preview.value = data;
    loaded.value = true;
  } catch {
    if (ticket.isCurrent()) {
      loadError.value = '预览读取失败，请重试。';
      preview.value = {};
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function clear() {
  previewGuard.invalidate();
  preview.value = {};
  loaded.value = false;
  loading.value = false;
  pageNo.value = 1;
  filters.value = [];
  loadError.value = '';
  rowEditorApi.setState({ isOpen: false });
  rowHistoryApi.setState({ isOpen: false });
}
watch(
  owner,
  () => {
    clear();
    void load();
  },
  { flush: 'sync' },
);
watch(available, () => {
  clear();
  void load();
});
onMounted(() => {
  void load();
});
onUnmounted(() => {
  alive = false;
  clear();
});
</script>

<template>
  <div class="preview-tab">
    <RowEditorModal @success="load" />
    <RowHistoryModal />
    <Alert
      v-if="!available"
      class="mb-3"
      show-icon
      type="warning"
      message="当前资产尚无可用数据，完成数据接入后可预览。"
    />
    <Alert
      v-if="importedSnapshot && !importedSnapshot.complete"
      class="mb-3"
      type="info"
      :message="`当前已导入 ${importedSnapshot.importedRowCount} 条，源系统共 ${importedSnapshot.sourceDataCount} 条。预览和修改作用于已导入的数据。`"
    />
    <div class="preview-tab__toolbar">
      <Button
        :loading="loading"
        :disabled="!available || !canRead"
        @click="query"
      >
        <template #icon><IconifyIcon icon="lucide:search" /></template>查询
      </Button>
      <Button :disabled="!available || !canRead" @click="resetFilters">
        <template #icon><IconifyIcon icon="lucide:rotate-ccw" /></template>重置
      </Button>
      <Button v-if="canEdit" class="ml-auto" @click="showHistory()">
        修改记录
      </Button>
    </div>
    <div class="preview-tab__filters">
      <Button
        size="small"
        type="primary"
        :disabled="!available || !canRead"
        aria-label="添加筛选条件"
        @click="addFilter"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
      </Button>
      <span v-if="!filters.length" class="preview-tab__filter-hint">点击“+”以添加筛选准则</span>
      <div
        v-for="(row, index) in filters"
        :key="index"
        class="preview-tab__filter"
      >
        <Checkbox v-model:checked="row.enabled" />
        <Select
          v-model:value="row.column"
          :options="columnOptions"
          allow-clear
          option-filter-prop="label"
          placeholder="请选择字段"
          show-search
          class="preview-tab__column"
        />
        <Select
          v-model:value="row.operator"
          :options="PREVIEW_OPERATOR_OPTIONS"
          class="preview-tab__operator"
        />
        <Input
          v-model:value="row.value"
          :disabled="NO_VALUE_OPERATORS.has(row.operator)"
          placeholder="请输入值"
          @press-enter="query"
        />
        <Button danger aria-label="删除筛选条件" @click="removeFilter(index)">
          <template #icon><IconifyIcon icon="lucide:minus" /></template>
        </Button>
      </div>
    </div>
    <template v-if="loadError"></template>
    <Alert
      v-else-if="preview.truncated && !importedSnapshot"
      class="mb-2"
      message="结果已达到预览上限，请添加筛选条件。"
      show-icon
      type="warning"
    />
    <DataWatermark>
    <Table
      class="preview-tab__table detail-fill-table"
      :columns="tableColumns"
      :data-source="rows"
      :loading="loading"
      :row-key="(row) => row.key"
      :pagination="false"
      :scroll="{ x: Math.max(tableColumns.length * 180, 800) }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === '__actions'">
          <Button
            type="link"
            size="small"
            :disabled="!record.rowId || record.revision === undefined"
            @click="editRow(record)"
          >
            修改
          </Button>
          <Button
            type="link"
            size="small"
            :disabled="!record.rowId"
            @click="showHistory(record.rowId)"
          >
            修改记录
          </Button>
        </template>
        <Tooltip v-else :title="formatCell(record, column.key)">
          <span>{{ formatCell(record, column.key) }}</span>
        </Tooltip>
      </template>
      <template #emptyText>
        <div class="py-10">
          {{ loaded ? '暂无数据' : '暂无预览数据' }}
        </div>
      </template>
    </Table>
    </DataWatermark>
    <ListPagination
      :current="pageNo"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      :total="preview.total ?? rows.length"
      aria-label="资产预览分页"
      @change="changePage"
    />
  </div>
</template>

<style scoped>
.preview-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.preview-tab__toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 12px 0;
}

.preview-tab__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}

.preview-tab__filter-hint {
  width: 590px;
  padding: 2px 16px;
  color: hsl(var(--muted-foreground));
  background: #f5f5f7;
  border-radius: 4px;
}

.preview-tab__filter {
  display: flex;
  flex: 1 1 100%;
  gap: 10px;
  align-items: center;
}

.preview-tab__column {
  width: 260px;
  min-width: 140px;
}

.preview-tab__operator {
  width: 110px;
  min-width: 100px;
}

.preview-tab__filter :deep(.ant-input) {
  max-width: 260px;
}

@media (width <= 760px) {
  .preview-tab__filter {
    flex-wrap: wrap;
  }

  .preview-tab__filter-hint {
    width: auto;
  }
}
</style>

<style scoped src="../../../../table-font.css"></style>
