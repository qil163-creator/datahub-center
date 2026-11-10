<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { SafetyStaticTaskApi } from '#/api/governance/statictask';

import { onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { isEmpty } from '@vben/utils';

import { message, Switch, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getDatabaseSimpleList } from '#/api/dataforge/database';
import {
  deleteSafetyStaticTask,
  deleteSafetyStaticTaskList,
  executeSafetyStaticTask,
  getSafetyStaticTaskPage,
  updateSafetyStaticTaskStatus,
} from '#/api/governance/statictask';
import { $t } from '#/locales';

import {
  optionColor,
  optionLabel,
  useGridColumns,
  useGridFormSchema,
  WRITE_MODE_OPTIONS,
} from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';
import Log from './modules/log.vue';

defineOptions({ name: 'SafetyStaticTask' });

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [LogModal, logModalApi] = useVbenModal({
  connectedComponent: Log,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const { hasAccessByCodes } = useAccess();

function databaseName(id?: SafetyStaticTaskApi.Id) {
  if (id === undefined || id === null) {
    return '';
  }
  return (
    databases.value.find((item) => String(item.id) === String(id))?.name ??
    String(id)
  );
}

function formatTable(databaseId?: SafetyStaticTaskApi.Id, tableName?: string) {
  const name = databaseName(databaseId);
  if (!name && !tableName) {
    return '-';
  }
  return name ? `${name}.${tableName ?? ''}` : (tableName ?? '-');
}

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData(null).open();
}

function handleEdit(row: SafetyStaticTaskApi.Task) {
  formModalApi.setData(row).open();
}

function handleLog(row: SafetyStaticTaskApi.Task) {
  logModalApi.setData(row).open();
}

function handleDetail(row: SafetyStaticTaskApi.Task) {
  detailModalApi.setData(row).open();
}

async function handleExecute(row: SafetyStaticTaskApi.Task) {
  await executeSafetyStaticTask(row.id!);
  message.success('已提交执行，请在执行记录中查看进度');
}

async function handleStatusChange(
  row: SafetyStaticTaskApi.Task,
  checked: boolean,
) {
  const status = checked ? 0 : 1;
  try {
    await updateSafetyStaticTaskStatus(row.id!, status);
    message.success(checked ? '已上线' : '已下线');
  } finally {
    handleRefresh();
  }
}

async function handleDelete(row: SafetyStaticTaskApi.Task) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.taskName]),
    duration: 0,
  });
  try {
    await deleteSafetyStaticTask(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.taskName]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleDeleteBatch() {
  await confirm($t('ui.actionMessage.deleteBatchConfirm'));
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deletingBatch'),
    duration: 0,
  });
  try {
    await deleteSafetyStaticTaskList(checkedIds.value);
    checkedIds.value = [];
    message.success($t('ui.actionMessage.deleteSuccess'));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

const checkedIds = ref<SafetyStaticTaskApi.Id[]>([]);
function handleRowCheckboxChange({
  records,
}: {
  records: SafetyStaticTaskApi.Task[];
}) {
  checkedIds.value = records.map((item) => item.id!);
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
          return await getSafetyStaticTaskPage({
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
  } as VxeTableGridOptions<SafetyStaticTaskApi.Task>,
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
    <FormModal @success="handleRefresh" />
    <LogModal />
    <DetailModal />
    <Grid table-title="静态脱敏任务">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:safety-static-task:create'],
              onClick: handleCreate,
            },
            {
              label: $t('ui.actionTitle.deleteBatch'),
              type: 'primary',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:safety-static-task:delete'],
              disabled: isEmpty(checkedIds),
              onClick: handleDeleteBatch,
            },
          ]"
        />
      </template>
      <template #source="{ row }">
        <span class="font-mono text-xs">
          {{ formatTable(row.sourceDatabaseId, row.sourceTableName) }}
        </span>
      </template>
      <template #target="{ row }">
        <span class="font-mono text-xs">
          {{ formatTable(row.targetDatabaseId, row.targetTableName) }}
        </span>
      </template>
      <template #writeMode="{ row }">
        <Tag :color="optionColor(WRITE_MODE_OPTIONS, row.writeMode)">
          {{ optionLabel(WRITE_MODE_OPTIONS, row.writeMode) }}
        </Tag>
      </template>
      <template #schedule="{ row }">
        <Tag v-if="row.cronExpression" color="blue">
          定时 {{ row.cronExpression }}
        </Tag>
        <Tag v-else>手动</Tag>
      </template>
      <template #status="{ row }">
        <Switch
          :checked="row.status === 0"
          :disabled="
            !hasAccessByCodes(['governance:safety-static-task:update'])
          "
          checked-children="上线"
          un-checked-children="下线"
          @change="(checked: boolean) => handleStatusChange(row, checked)"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:safety-static-task:update'],
              onClick: handleEdit.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:safety-static-task:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: '执行一次',
              type: 'link',
              icon: 'lucide:play',
              auth: ['governance:safety-static-task:execute'],
              popConfirm: {
                title: `确认立即执行「${row.taskName}」吗？将按规则把源表数据脱敏写入目标表。`,
                confirm: handleExecute.bind(null, row),
              },
            },
            {
              label: '执行记录',
              type: 'link',
              icon: 'lucide:history',
              auth: ['governance:safety-static-task:query'],
              onClick: handleLog.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:safety-static-task:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.taskName]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
