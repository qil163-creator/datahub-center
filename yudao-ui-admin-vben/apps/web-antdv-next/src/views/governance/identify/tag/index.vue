<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { SafetyIdentifyApi } from '#/api/governance/identify';

import { onMounted, ref } from 'vue';

import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { isEmpty } from '@vben/utils';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getDatabaseSimpleList } from '#/api/dataforge/database';
import {
  confirmFieldTags,
  deleteFieldTag,
  getFieldTagPage,
  rejectFieldTags,
} from '#/api/governance/identify';
import { $t } from '#/locales';

import {
  optionColor,
  optionLabel,
  SOURCE_OPTIONS,
  TAG_STATUS_OPTIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Detail from './modules/detail.vue';
import Runs from './modules/runs.vue';
import Scan from './modules/scan.vue';
import TagForm from './modules/tag-form.vue';

defineOptions({ name: 'SafetyFieldTag' });

const [ScanModal, scanModalApi] = useVbenModal({
  connectedComponent: Scan,
  destroyOnClose: true,
});

const [RunsModal, runsModalApi] = useVbenModal({
  connectedComponent: Runs,
  destroyOnClose: true,
});

const [TagFormModal, tagFormModalApi] = useVbenModal({
  connectedComponent: TagForm,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);

function databaseName(id?: number) {
  if (id === undefined || id === null) {
    return '';
  }
  return databases.value.find((item) => item.id === id)?.name ?? String(id);
}

function handleRefresh() {
  gridApi.query();
}

function handleScan() {
  scanModalApi.setData(null).open();
}

function handleRuns() {
  runsModalApi.setData(null).open();
}

function handleManualTag() {
  tagFormModalApi.setData(null).open();
}

function handleDetail(row: SafetyIdentifyApi.FieldTag) {
  detailModalApi.setData(row).open();
}

async function handleConfirm(row: SafetyIdentifyApi.FieldTag) {
  await confirmFieldTags([row.id!]);
  message.success('已确认打标，并回写元数据分级与敏感标记');
  handleRefresh();
}

async function handleReject(row: SafetyIdentifyApi.FieldTag) {
  await rejectFieldTags([row.id!]);
  message.success('已驳回');
  handleRefresh();
}

async function handleDelete(row: SafetyIdentifyApi.FieldTag) {
  await deleteFieldTag(row.id!);
  message.success($t('ui.actionMessage.deleteSuccess', [row.columnName]));
  handleRefresh();
}

const checkedIds = ref<number[]>([]);
function handleRowCheckboxChange({
  records,
}: {
  records: SafetyIdentifyApi.FieldTag[];
}) {
  checkedIds.value = records.map((item) => item.id!);
}

async function handleConfirmBatch() {
  await confirm(
    `确认选中的 ${checkedIds.value.length} 条打标吗？确认后将回写元数据。`,
  );
  await confirmFieldTags(checkedIds.value);
  checkedIds.value = [];
  message.success('批量确认成功');
  handleRefresh();
}

async function handleRejectBatch() {
  await confirm(`驳回选中的 ${checkedIds.value.length} 条打标吗？`);
  await rejectFieldTags(checkedIds.value);
  checkedIds.value = [];
  message.success('批量驳回成功');
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getFieldTagPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
  } as VxeTableGridOptions<SafetyIdentifyApi.FieldTag>,
  gridEvents: {
    checkboxAll: handleRowCheckboxChange,
    checkboxChange: handleRowCheckboxChange,
  },
});

onMounted(async () => {
  databases.value = (await getDatabaseSimpleList()) ?? [];
});
</script>

<template>
  <Page auto-content-height>
    <ScanModal @success="handleRefresh" />
    <RunsModal />
    <TagFormModal @success="handleRefresh" />
    <DetailModal />
    <Grid table-title="敏感字段打标">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '执行扫描',
              icon: 'lucide:scan-search',
              type: 'primary',
              auth: ['governance:safety-identify:scan'],
              onClick: handleScan,
            },
            {
              label: '扫描记录',
              icon: 'lucide:history',
              auth: ['governance:safety-identify:query'],
              onClick: handleRuns,
            },
            {
              label: '人工打标',
              icon: ACTION_ICON.ADD,
              auth: ['governance:safety-identify:confirm'],
              onClick: handleManualTag,
            },
            {
              label: '批量确认',
              icon: 'lucide:check-check',
              type: 'primary',
              auth: ['governance:safety-identify:confirm'],
              disabled: isEmpty(checkedIds),
              onClick: handleConfirmBatch,
            },
            {
              label: '批量驳回',
              icon: 'lucide:x',
              danger: true,
              auth: ['governance:safety-identify:confirm'],
              disabled: isEmpty(checkedIds),
              onClick: handleRejectBatch,
            },
          ]"
        />
      </template>
      <template #column="{ row }">
        <span class="font-mono text-xs">
          {{ databaseName(row.databaseId) }}.{{ row.tableName }}.{{
            row.columnName
          }}
        </span>
      </template>
      <template #level="{ row }">
        <Tag v-if="row.levelName" :color="row.levelColor || 'default'">
          {{ row.levelName }}
        </Tag>
        <span v-else>-</span>
      </template>
      <template #source="{ row }">
        <Tag :color="optionColor(SOURCE_OPTIONS, row.source)">
          {{ optionLabel(SOURCE_OPTIONS, row.source) }}
        </Tag>
      </template>
      <template #tagStatus="{ row }">
        <Tag :color="optionColor(TAG_STATUS_OPTIONS, row.tagStatus)">
          {{ optionLabel(TAG_STATUS_OPTIONS, row.tagStatus) }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:safety-identify:query'],
              onClick: handleDetail.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '确认',
              type: 'link',
              icon: 'lucide:check',
              ifShow: row.tagStatus === 'PENDING',
              auth: ['governance:safety-identify:confirm'],
              popConfirm: {
                title: `确认「${row.columnName}」为敏感字段吗？确认后将回写元数据。`,
                confirm: handleConfirm.bind(null, row),
              },
            },
            {
              label: '驳回',
              type: 'link',
              danger: true,
              icon: ACTION_ICON.CLOSE,
              ifShow: row.tagStatus === 'PENDING',
              auth: ['governance:safety-identify:confirm'],
              popConfirm: {
                title: `驳回「${row.columnName}」的打标吗？`,
                confirm: handleReject.bind(null, row),
              },
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:safety-identify:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.columnName]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
