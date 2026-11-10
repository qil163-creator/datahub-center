<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardCodeItemApi } from '#/api/governance/standardcodeitem';

import { watch } from 'vue';

import { Plus } from '@vben/icons';

import { Button } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getStandardCodeItemPage } from '#/api/governance/standardcodeitem';

import { useRelatedItemGridColumns } from '../data';

const props = defineProps<{
  codeSetName?: string;
  dictType?: string;
}>();

const emit = defineEmits<{
  relate: [];
}>();

function handleRefresh() {
  gridApi.query();
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    align: 'left',
    border: false,
    headerAlign: 'left',
    headerCellConfig: { height: 34 },
    columns: useRelatedItemGridColumns(),
    height: '100%',
    keepSource: true,
    round: false,
    pagerConfig: {
      enabled: true,
    },
    proxyConfig: {
      ajax: {
        query: async ({ page }) => {
          if (!props.dictType) {
            return { list: [], total: 0 };
          }
          return await getStandardCodeItemPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            dictType: props.dictType,
          });
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      height: 42,
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: false,
      zoom: false,
    },
  } as VxeTableGridOptions<GovernanceStandardCodeItemApi.StandardCodeItem>,
});

watch(
  () => props.dictType,
  () => {
    if (props.dictType) {
      handleRefresh();
    }
  },
);
</script>

<template>
  <div class="standard-detail-vxe h-full min-h-0">
    <Grid>
      <template #toolbar-actions>
        <Button
          v-access:code="['governance:standard-element:update']"
          size="small"
          type="primary"
          @click="emit('relate')"
        >
          <template #icon><Plus :size="12" /></template>
          关联
        </Button>
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
