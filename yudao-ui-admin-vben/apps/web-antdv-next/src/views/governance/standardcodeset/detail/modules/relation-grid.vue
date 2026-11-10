<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';

import { watch } from 'vue';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getStandardElementPage } from '#/api/governance/standardelement';

import { useRelationGridColumns } from '../data';

const props = defineProps<{
  codeSetId?: number | string;
}>();

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: useRelationGridColumns(),
    height: '100%',
    pagerConfig: {
      enabled: true,
    },
    proxyConfig: {
      ajax: {
        query: async ({ page }) => {
          if (!props.codeSetId) {
            return { list: [], total: 0 };
          }
          return await getStandardElementPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            codeSetId: props.codeSetId,
          });
        },
      },
    },
    rowConfig: {
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: false,
    },
  } as VxeTableGridOptions,
});

watch(
  () => props.codeSetId,
  () => {
    if (props.codeSetId) {
      gridApi.query();
    }
  },
);
</script>

<template>
  <div class="h-full min-h-0">
    <Grid table-title="关联标准数据元" />
  </div>
</template>
