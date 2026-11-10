<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SupplyAppApi } from '#/api/supply/app';

import { useRouter } from 'vue-router';

import { confirm, Page, useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteSupplyApp,
  getSupplyAppPage,
  resetSupplyAppSecret,
} from '#/api/supply/app';
import { $t } from '#/locales';
import { parseResourceId } from '#/utils/resource-id';
import TextPreview from '#/views/governance/common/text-preview.vue';

import { useGridColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';

defineOptions({ name: 'SupplyApp' });

const router = useRouter();
const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [SecretPreview, secretPreviewApi] = useVbenModal({
  connectedComponent: TextPreview,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData(null).open();
}

function handleEdit(row: SupplyAppApi.App) {
  formModalApi.setData(row).open();
}

function handleDetail(row: SupplyAppApi.App) {
  const id = parseResourceId(row.id);
  if (!id) {
    message.warning('应用编号无效，请刷新列表后重试');
    return;
  }
  void router.push({ name: 'SupplyAppDetailPage', params: { id } });
}

function onFormSuccess(created?: SupplyAppApi.App) {
  handleRefresh();
  if (created?.appKey) {
    secretPreviewApi
      .setData({
        title: '应用已创建',
        text: `AppKey：${created.appKey}\nAppSecret：${created.secret ?? ''}`,
      })
      .open();
  }
}

async function handleResetSecret(row: SupplyAppApi.App) {
  try {
    await confirm(
      `确认要重置"${row.name}"的应用秘钥吗？重置后旧秘钥立即失效。`,
    );
  } catch {
    return;
  }
  const secret = await resetSupplyAppSecret(row.id!);
  secretPreviewApi
    .setData({
      title: '秘钥已重置',
      text: `新秘钥：${secret}`,
    })
    .open();
  handleRefresh();
}

async function handleDelete(row: SupplyAppApi.App) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.name || row.id]),
    duration: 0,
  });
  try {
    await deleteSupplyApp(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.name || row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    showHeaderOverflow: 'tooltip',
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getSupplyAppPage({
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
  } as VxeTableGridOptions<SupplyAppApi.App>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="onFormSuccess" />
    <SecretPreview />
    <Grid class="supply-app-grid" table-title="应用列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['supply:app:create'],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['supply:app:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['supply:app:query'],
              onClick: handleDetail.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '重置秘钥',
              type: 'link',
              icon: 'lucide:key-round',
              auth: ['supply:app:update'],
              onClick: handleResetSecret.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['supply:app:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [
                  row.name || row.id,
                ]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>

<style scoped>
.supply-app-grid :deep(.vxe-body--column) {
  font-size: 12px;
}
</style>
