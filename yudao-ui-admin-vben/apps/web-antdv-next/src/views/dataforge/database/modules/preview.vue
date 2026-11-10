<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { computed, nextTick, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Modal,
  Table,
} from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  getDatabaseTableList,
  getDatabaseTableMeta,
  previewDatabaseTable,
} from '#/api/dataforge/database';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { createLatestWins } from '#/utils/resource-id';

import {
  displayText,
  formatPreviewCell,
  resolvePreviewError,
  tableTypeLabel,
  yesNo,
} from '../preview';

defineOptions({ name: 'FoundationDatabasePreview' });

const SAMPLE_LIMIT = 20;

type NestedKind = 'attrs' | 'columns' | 'sample';

const database = ref<FoundationDatabaseApi.Database>();
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const listLoading = ref(false);
const listError = ref('');
const selectedTableName = ref('');
const nestedKind = ref<NestedKind>();
const nestedTable = ref<FoundationDatabaseApi.DatabaseTable>();
const nestedLoading = ref(false);
const nestedError = ref('');
const tableMeta = ref<FoundationDatabaseApi.DatabaseTableMeta>();
const sample = ref<FoundationDatabaseApi.DatabasePreview>();
const listGuard = createLatestWins();
const nestedGuard = createLatestWins();

const getTitle = computed(() =>
  database.value?.name ? `表数据预览 - ${database.value.name}` : '表数据预览',
);

const nestedTitle = computed(() => {
  const name = nestedTable.value?.tableName || '';
  if (nestedKind.value === 'attrs') {
    return name ? `查看属性 - ${name}` : '查看属性';
  }
  if (nestedKind.value === 'columns') {
    return name ? `字段结构 - ${name}` : '字段结构';
  }
  return name ? `数据样例 - ${name}` : '数据样例';
});

const nestedOpen = computed(() => nestedKind.value !== undefined);

const nestedWidth = computed(() => (nestedKind.value === 'attrs' ? 960 : 1280));

function selectTable(table: FoundationDatabaseApi.DatabaseTable) {
  selectedTableName.value = table.tableName;
}

const tableGridColumns: VxeTableGridOptions<FoundationDatabaseApi.DatabaseTable>['columns'] =
  [
    {
      field: 'tableName',
      title: '表名',
      minWidth: 200,
      align: 'left',
      slots: { default: 'tableName' },
    },
    {
      field: 'viewTable',
      title: '类型',
      width: 130,
      align: 'left',
      formatter: ({ row }) => tableTypeLabel(row.viewTable),
    },
    {
      field: 'remarks',
      title: '说明',
      minWidth: 160,
      align: 'left',
      cellRender: {
        name: 'CellTextEllipsis',
        props: { line: 2, center: true },
      },
    },
    {
      field: 'attrs',
      title: '属性信息',
      width: 110,
      align: 'center',
      slots: { default: 'attrs' },
    },
    {
      field: 'columnStruct',
      title: '字段信息',
      width: 110,
      align: 'center',
      slots: { default: 'columnStruct' },
    },
    {
      field: 'sample',
      title: '数据预览',
      width: 110,
      align: 'center',
      slots: { default: 'sample' },
    },
  ];

const [Grid, gridApi] = useVbenVxeGrid({
  gridClass: 'p-0',
  gridOptions: {
    columns: tableGridColumns,
    height: 'auto',
    autoResize: true,
    keepSource: true,
    proxyConfig: {
      enabled: false,
    },
    toolbarConfig: {
      enabled: false,
    },
    pagerConfig: {
      enabled: true,
      pageSize: 10,
    },
    rowConfig: {
      keyField: 'tableName',
      isCurrent: true,
      isHover: true,
    },
  } as VxeTableGridOptions<FoundationDatabaseApi.DatabaseTable>,
  gridEvents: {
    cellClick: ({ row }: { row: FoundationDatabaseApi.DatabaseTable }) => {
      selectTable(row);
    },
  },
});

async function syncTableGrid() {
  await nextTick();
  gridApi.setLoading(listLoading.value);
  const grid = gridApi.grid;
  if (!grid?.reloadData) {
    return;
  }
  await grid.reloadData(tables.value);
  await nextTick();
  grid.recalculate?.();
  const current =
    tables.value.find((item) => item.tableName === selectedTableName.value) ??
    tables.value[0];
  if (current) {
    grid.setCurrentRow(current);
  } else {
    grid.clearCurrentRow?.();
  }
}

watch([tables, listLoading], () => {
  void syncTableGrid();
});

const columnColumns = [
  { title: '字段名', dataIndex: 'fieldName', key: 'fieldName', ellipsis: true },
  {
    title: '类型',
    dataIndex: 'fieldTypeName',
    key: 'fieldTypeName',
    width: 140,
  },
  { title: '说明', dataIndex: 'remarks', key: 'remarks' },
  { title: '主键', key: 'pk', width: 70 },
  { title: '可空', key: 'nullable', width: 70 },
  { title: '长度', dataIndex: 'length', key: 'length', width: 80 },
  {
    title: '默认值',
    dataIndex: 'defaultValue',
    key: 'defaultValue',
    ellipsis: true,
  },
];

const sampleColumns = computed(() =>
  (sample.value?.columns ?? []).map((column) => ({
    title: column.label || column.name,
    dataIndex: column.name,
    key: column.name,
    ellipsis: true,
    width: 160,
  })),
);

const [ModalComponent, modalApi] = useVbenModal({
  showConfirmButton: false,
  showCancelButton: false,
  contentClass: 'p-0',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      listGuard.invalidate();
      nestedGuard.invalidate();
      database.value = undefined;
      tables.value = [];
      listError.value = '';
      selectedTableName.value = '';
      closeNested();
      return;
    }
    const row = modalApi.getData() as
      | FoundationDatabaseApi.Database
      | undefined;
    database.value = row;
    if (!row?.id) {
      return;
    }
    const ticket = listGuard.next();
    listLoading.value = true;
    listError.value = '';
    tables.value = [];
    selectedTableName.value = '';
    try {
      const list =
        (await getDatabaseTableList(row.id, { silentError: true })) ?? [];
      if (!ticket.isCurrent()) {
        return;
      }
      tables.value = list;
      selectedTableName.value = list[0]?.tableName ?? '';
    } catch (error) {
      if (!ticket.isCurrent()) {
        return;
      }
      listError.value = resolvePreviewError(error);
    } finally {
      if (ticket.isCurrent()) {
        listLoading.value = false;
      }
    }
  },
});

function closeNested() {
  nestedGuard.invalidate();
  nestedKind.value = undefined;
  nestedTable.value = undefined;
  nestedLoading.value = false;
  nestedError.value = '';
  tableMeta.value = undefined;
  sample.value = undefined;
}

function handleNestedOpenChange(open: boolean) {
  if (!open) {
    closeNested();
  }
}

async function openNested(
  kind: NestedKind,
  table: FoundationDatabaseApi.DatabaseTable,
) {
  const databaseId = database.value?.id;
  if (!databaseId) {
    return;
  }
  selectedTableName.value = table.tableName;
  nestedKind.value = kind;
  nestedTable.value = table;
  tableMeta.value = undefined;
  sample.value = undefined;
  nestedError.value = '';
  const ticket = nestedGuard.next();
  nestedLoading.value = true;
  try {
    if (kind === 'sample') {
      const data = await previewDatabaseTable(
        {
          databaseId,
          tableName: table.tableName,
          limit: SAMPLE_LIMIT,
        },
        { silentError: true },
      );
      if (!ticket.isCurrent()) {
        return;
      }
      sample.value = data;
      return;
    }
    const meta = await getDatabaseTableMeta(databaseId, table.tableName, {
      silentError: true,
    });
    if (!ticket.isCurrent()) {
      return;
    }
    tableMeta.value = meta;
  } catch (error) {
    if (!ticket.isCurrent()) {
      return;
    }
    nestedError.value = resolvePreviewError(error);
  } finally {
    if (ticket.isCurrent()) {
      nestedLoading.value = false;
    }
  }
}

function primaryKeyText() {
  const keys = tableMeta.value?.primaryKeys?.filter(Boolean) ?? [];
  return keys.length > 0 ? keys.join(', ') : '-';
}
</script>

<template>
  <ModalComponent :title="getTitle" class="w-[min(1440px,92vw)]">
    <div class="preview-modal">
      <div class="preview-modal__body">
        <aside class="preview-modal__catalog">
          <div class="preview-modal__panel-title">对象目录</div>
          <button class="preview-modal__catalog-item is-active" type="button">
            <IconifyIcon class="size-4 shrink-0" icon="lucide:table" />
            <span>全部表</span>
          </button>
        </aside>
        <section class="preview-modal__list">
          <div class="preview-modal__list-head">
            <div class="preview-modal__panel-title">表清单</div>
            <span v-if="!listError" class="preview-modal__count">{{ tables.length }} 个</span>
          </div>
          <Grid v-if="!listError" class="preview-modal__grid bg-transparent" :table-data="tables">
            <template #tableName="{ row }">
              <span class="preview-modal__table-name">
                <IconifyIcon class="size-4 shrink-0" icon="lucide:table" />
                <span class="truncate">{{ row.tableName }}</span>
              </span>
            </template>
            <template #attrs="{ row }">
              <Button
                size="small"
                type="link"
                @click.stop="openNested('attrs', row)"
              >
                <template #icon><IconifyIcon icon="lucide:eye" /></template>
                查看属性
              </Button>
            </template>
            <template #columnStruct="{ row }">
              <Button
                size="small"
                type="link"
                @click.stop="openNested('columns', row)"
              >
                <template #icon>
                  <IconifyIcon icon="lucide:columns-3" />
                </template>
                字段结构
              </Button>
            </template>
            <template #sample="{ row }">
              <Button
                size="small"
                type="link"
                v-access:code="'dataforge:database:preview'"
                @click.stop="openNested('sample', row)"
              >
                <template #icon><IconifyIcon icon="lucide:table-2" /></template>
                数据样例
              </Button>
            </template>
          </Grid>
        </section>
      </div>
    </div>
  </ModalComponent>

  <Modal
    :footer="null"
    :open="nestedOpen"
    :title="nestedTitle"
    :width="nestedWidth"
    :z-index="4000"
    destroy-on-hidden
    @update:open="handleNestedOpenChange"
  >
    <template v-if="!nestedError">
    <div v-if="nestedKind === 'attrs'" class="preview-nested">
      <Descriptions :column="2" bordered size="small">
        <DescriptionsItem label="表名">
          {{ displayText(tableMeta?.tableName || nestedTable?.tableName) }}
        </DescriptionsItem>
        <DescriptionsItem label="类型">
          {{
            displayText(
              tableMeta?.tableType || tableTypeLabel(nestedTable?.viewTable),
            )
          }}
        </DescriptionsItem>
        <DescriptionsItem label="Schema">
          {{ displayText(tableMeta?.schemaName) }}
        </DescriptionsItem>
        <DescriptionsItem label="说明">
          {{ displayText(tableMeta?.remarks || nestedTable?.remarks) }}
        </DescriptionsItem>
        <DescriptionsItem :span="2" label="主键">
          {{ nestedLoading ? '加载中...' : primaryKeyText() }}
        </DescriptionsItem>
        <DescriptionsItem :span="2" label="建表语句">
          <pre class="preview-nested__sql">{{
            nestedLoading ? '加载中...' : displayText(tableMeta?.createSql)
          }}</pre>
        </DescriptionsItem>
      </Descriptions>
    </div>
    <Table
      v-else-if="nestedKind === 'columns'"
      :columns="columnColumns"
      :data-source="tableMeta?.columns ?? []"
      :loading="nestedLoading"
      :pagination="false"
      :scroll="{ y: 560 }"
      row-key="fieldName"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'remarks'">
          <TextEllipsisCell
            :content="displayText(record.remarks)"
            :line="1"
            center
          />
        </template>
        <template v-else-if="column.key === 'pk'">
          {{ yesNo(record.pk) }}
        </template>
        <template v-else-if="column.key === 'nullable'">
          {{ yesNo(record.nullable) }}
        </template>
        <template v-else-if="column.key === 'length'">
          {{ displayText(record.length) }}
        </template>
        <template v-else-if="column.key === 'defaultValue'">
          {{ displayText(record.defaultValue) }}
        </template>
      </template>
    </Table>
    <div v-else class="preview-nested">
      <Alert
        v-if="sample?.truncated"
        class="mb-3"
        message="仅返回样例数据，已按上限截断"
        show-icon
        type="info"
      />
      <Table
        :columns="sampleColumns"
        :data-source="sample?.rows ?? []"
        :loading="nestedLoading"
        :pagination="false"
        :row-key="
          (_record: Record<string, unknown>, index?: number) =>
            String(index ?? 0)
        "
        :scroll="{ x: 'max-content', y: 560 }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          {{ formatPreviewCell(record[column.dataIndex as string]) }}
        </template>
      </Table>
    </div>
    </template>
  </Modal>
</template>

<style scoped>
.preview-modal {
  display: flex;
  flex-direction: column;
  height: min(780px, 78vh);
  padding: 16px 20px 12px;
}

.preview-modal__body {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.preview-modal__catalog {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  width: 220px;
  padding: 12px;
  background: hsl(var(--background));
  border-right: 1px solid hsl(var(--border));
}

.preview-modal__panel-title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  line-height: 22px;
}

.preview-modal__catalog-item {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  font-size: 14px;
  color: hsl(var(--foreground));
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 6px;
}

.preview-modal__catalog-item.is-active {
  font-weight: 500;
  background: hsl(var(--primary) / 8%);
}

.preview-modal__list {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 12px 16px 8px;
}

.preview-modal__grid {
  flex: 1;
  min-height: 0;
}

.preview-modal__list-head {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
}

.preview-modal__count {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.preview-modal__table-name {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  max-width: 100%;
}

.preview-nested {
  min-height: 120px;
}

.preview-nested__sql {
  max-height: 320px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-all;
  white-space: pre-wrap;
}

:deep(.vxe-body--row.row--current > .vxe-body--column) {
  background: hsl(var(--primary) / 8%);
}

:deep(.ant-table-wrapper) {
  flex: 1;
}

:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

:deep(.ant-btn-link) {
  padding-inline: 0;
}

:deep(.vxe-pager) {
  padding-inline: 0;
}
</style>
