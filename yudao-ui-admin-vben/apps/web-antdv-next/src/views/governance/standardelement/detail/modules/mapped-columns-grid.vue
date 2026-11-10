<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { h, watch } from 'vue';

import { confirm } from '@vben/common-ui';

import { message, Modal } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  getStandardElementEnumCheckSqls,
  getStandardElementMappedColumns,
  revokeStandardElementMapping,
} from '#/api/governance/standardelement';
import { TableAction } from '#/components/table-action';

const props = defineProps<{
  elementId?: number;
}>();

const MAPPING_STATUS_LABELS: Record<string, string> = {
  CONFIRMED: '已确认',
  PROPOSED: '待确认',
  REVOKED: '已撤销',
  STALE: '已失效',
};

/** 查看码值内容稽核 SQL（数据元需绑定码表） */
async function handleEnumCheckSql() {
  if (!props.elementId) {
    return;
  }
  const list = await getStandardElementEnumCheckSqls(props.elementId);
  if (list.length === 0) {
    message.info('该数据元未绑定码表或没有可生成的落标字段');
    return;
  }
  Modal.info({
    content: h(
      'div',
      { style: 'max-height:60vh;overflow:auto' },
      list.map((item) =>
        h('div', { style: 'margin-bottom:12px' }, [
          h('b', `${item.tableName}.${item.columnName}`),
          h(
            'pre',
            {
              style:
                'margin:4px 0;padding:8px;background:#f5f5f5;font-size:12px;white-space:pre-wrap',
            },
            item.checkSql,
          ),
        ]),
      ),
    ),
    title: '码值内容稽核 SQL（可在质量模块或数据源执行）',
    width: 720,
  });
}

/** 撤销落标（同时解除元数据字段绑定） */
async function handleRevoke(row: GovernanceStandardElementApi.MappedColumn) {
  await confirm(
    `确认撤销字段【${row.tableName ?? ''}.${row.columnName}】的落标吗？将同时解除该字段与本标准的绑定。`,
  );
  await revokeStandardElementMapping(row.mappingId!);
  message.success('已撤销落标');
  gridApi.query();
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    align: 'left',
    border: false,
    headerAlign: 'left',
    headerCellConfig: { height: 34 },
    columns: [
      { field: 'columnId', title: '字段编号', minWidth: 90 },
      { field: 'tableName', title: '所属表', minWidth: 160 },
      { field: 'tableBusinessName', title: '表业务名称', minWidth: 140 },
      { field: 'layer', title: '数仓分层', minWidth: 90 },
      { field: 'columnName', title: '字段名', minWidth: 140 },
      { field: 'businessName', title: '字段业务名称', minWidth: 140 },
      { field: 'dataType', title: '数据类型', minWidth: 100 },
      { field: 'dataLength', title: '数据长度', minWidth: 90 },
      {
        field: 'nullableFlag',
        title: '是否可空',
        minWidth: 90,
        formatter: ({ cellValue }) => {
          if (cellValue === 1) return '可空';
          if (cellValue === 0) return '不可空';
          return '-';
        },
      },
      {
        field: 'complianceResult',
        title: '贯标结果',
        minWidth: 90,
        align: 'center',
        formatter: ({ cellValue }) => {
          switch (cellValue) {
            case 'FAIL': {
              return '不符合';
            }
            case 'PASS': {
              return '符合';
            }
            case 'SKIP': {
              return '未定义约束';
            }
            default: {
              return '-';
            }
          }
        },
      },
      {
        field: 'complianceIssues',
        title: '不符合项说明',
        minWidth: 220,
        formatter: ({ cellValue }) =>
          Array.isArray(cellValue) && cellValue.length > 0
            ? cellValue.join('；')
            : '-',
      },
      {
        field: 'mappingStatus',
        title: '落标状态',
        minWidth: 90,
        align: 'center',
        formatter: ({ cellValue }) =>
          MAPPING_STATUS_LABELS[cellValue as string] ?? cellValue ?? '-',
      },
      {
        title: '操作',
        width: 140,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    height: '100%',
    round: false,
    pagerConfig: {
      enabled: false,
    },
    proxyConfig: {
      ajax: {
        query: async () => {
          if (!props.elementId) {
            return { list: [], total: 0 };
          }
          const list = await getStandardElementMappedColumns(props.elementId);
          return { list, total: list.length };
        },
      },
    },
    rowConfig: {
      keyField: 'columnId',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: false,
      zoom: false,
    },
  } as VxeTableGridOptions<GovernanceStandardElementApi.MappedColumn>,
});

watch(
  () => props.elementId,
  () => {
    if (props.elementId) {
      gridApi.query();
    }
  },
);
</script>

<template>
  <div class="standard-detail-vxe h-full min-h-0">
    <Grid>
      <template #toolbar-actions>
        <TableAction
          :actions="[
            {
              label: '码值稽核SQL',
              icon: 'lucide:code',
              type: 'primary',
              size: 'small',
              auth: ['governance:standard-element:query'],
              onClick: handleEnumCheckSql,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '撤销落标',
              icon: 'lucide:unlink',
              type: 'link',
              danger: true,
              auth: ['governance:standard-element:update'],
              ifShow: row.mappingStatus === 'CONFIRMED' && !!row.mappingId,
              onClick: handleRevoke.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>
  </div>
</template>

<style scoped>
.standard-detail-vxe :deep(.vxe-grid) {
  padding: 0;
}

.standard-detail-vxe :deep(.vxe-toolbar) {
  height: 30px;
  min-height: 30px;
  margin-bottom: 8px;
}

.standard-detail-vxe :deep(.vxe-toolbar .vxe-button.is--circle) {
  width: 26px;
  min-width: 26px;
  height: 26px;
}

.standard-detail-vxe :deep(.vxe-table--border-line) {
  border: 0;
  border-bottom: 1px solid hsl(var(--border));
}

.standard-detail-vxe :deep(.vxe-cell--col-resizable::before) {
  background-color: transparent;
}

.standard-detail-vxe :deep(.vxe-cell--col-resizable:hover::before) {
  background-color: hsl(var(--border));
}

.standard-detail-vxe :deep(.vxe-header--column .vxe-cell),
.standard-detail-vxe :deep(.vxe-body--column .vxe-cell) {
  padding-inline: 10px;
}

.standard-detail-vxe :deep(.vxe-table--scroll-x-handle),
.standard-detail-vxe :deep(.vxe-table--scroll-y-handle) {
  scrollbar-color: #d4d7dc transparent;
  scrollbar-width: thin;
}
</style>
