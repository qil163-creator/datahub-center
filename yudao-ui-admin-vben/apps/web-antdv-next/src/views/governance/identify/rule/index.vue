<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyIdentifyApi } from '#/api/governance/identify';

import { Page, useVbenModal } from '@vben/common-ui';

import { message, Tag, Tooltip } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteIdentifyRule,
  getIdentifyRulePage,
} from '#/api/governance/identify';
import { $t } from '#/locales';

import {
  MATCH_TYPE_OPTIONS,
  optionColor,
  optionLabel,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

defineOptions({ name: 'SafetyIdentifyRule' });

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData(null).open();
}

function handleEdit(row: SafetyIdentifyApi.IdentifyRule) {
  formModalApi.setData(row).open();
}

function handleDetail(row: SafetyIdentifyApi.IdentifyRule) {
  detailModalApi.setData(row).open();
}

async function handleDelete(row: SafetyIdentifyApi.IdentifyRule) {
  await deleteIdentifyRule(row.id!);
  message.success($t('ui.actionMessage.deleteSuccess', [row.ruleName]));
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
          return await getIdentifyRulePage({
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
  } as VxeTableGridOptions<SafetyIdentifyApi.IdentifyRule>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <Grid table-title="敏感识别规则">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:safety-identify:create'],
              onClick: handleCreate,
            },
          ]"
        />
      </template>
      <template #matchType="{ row }">
        <Tag :color="optionColor(MATCH_TYPE_OPTIONS, row.matchType)">
          {{ optionLabel(MATCH_TYPE_OPTIONS, row.matchType) }}
        </Tag>
      </template>
      <template #pattern="{ row }">
        <Tooltip :title="row.pattern">
          <span class="font-mono text-xs">{{ row.pattern }}</span>
        </Tooltip>
      </template>
      <template #threshold="{ row }">
        <span v-if="row.matchType === 'DATA_REGEX'">
          命中率 ≥ {{ row.minHitRatio ?? 80 }}% / 采样
          {{ row.sampleRows ?? 50 }} 行
        </span>
        <span v-else>-</span>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:safety-identify:update'],
              onClick: handleEdit.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:safety-identify:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:safety-identify:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.ruleName]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
