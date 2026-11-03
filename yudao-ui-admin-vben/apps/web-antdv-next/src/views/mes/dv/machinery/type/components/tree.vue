<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { MesDvMachineryTypeApi } from '#/api/mes/dv/machinery/type';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { CommonStatusEnum } from '@vben/constants';
import { handleTree, traverseTreeValues } from '@vben/utils';

import { Card, Input } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getMachineryTypeList } from '#/api/mes/dv/machinery/type';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

const emit = defineEmits<{
  nodeClick: [row?: MesDvMachineryTypeApi.MachineryType];
}>();
const selectedId = ref<number>(); // 选中设备类型编号
const searchKeyword = ref(''); // 设备类型搜索关键字
const loading = ref(false);
const rows = ref<MesDvMachineryTypeApi.MachineryType[]>([]);
const expandedIds = ref<number[]>([]);
const branches = computed(() =>
  traverseTreeValues(rows.value, (row) => row).filter(
    (row) => row.children?.length,
  ),
);
const allExpanded = computed(
  () =>
    branches.value.length > 0 &&
    branches.value.every((row) => expandedIds.value.includes(row.id!)),
);
let requestId = 0;
let active = true;

async function toggleExpand() {
  const expand = !allExpanded.value;
  expandedIds.value = expand ? branches.value.map((row) => row.id!) : [];
  await gridApi.grid.setAllTreeExpand(expand);
}

async function refreshTree() {
  const request = ++requestId;
  loading.value = true;
  gridApi.setLoading(true);
  try {
    const data = await getMachineryTypeList({
      name: searchKeyword.value || undefined,
      status: CommonStatusEnum.ENABLE,
    });
    if (!active || request !== requestId) return;
    rows.value = handleTree(data);
    await gridApi.grid.reloadData(rows.value);
    if (!active || request !== requestId) return;
    await gridApi.grid.setTreeExpand(
      branches.value.filter((row) => expandedIds.value.includes(row.id!)),
      true,
    );
    if (!active || request !== requestId) return;
    const selected = traverseTreeValues(rows.value, (row) => row).find(
      (row) => row.id === selectedId.value,
    );
    if (selected) await gridApi.grid.setCurrentRow(selected);
  } finally {
    if (active && request === requestId) {
      loading.value = false;
      gridApi.setLoading(false);
    }
  }
}

onMounted(refreshTree);

onBeforeUnmount(() => {
  active = false;
  ++requestId;
});

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    autoResize: true,
    border: false,
    columns: [{ field: 'name', title: '类型名称', treeNode: true }],
    height: 'auto',
    pagerConfig: {
      enabled: false,
    },
    rowConfig: {
      isCurrent: true,
      isHover: true,
      keyField: 'id',
    },
    showHeader: false,
    toolbarConfig: {
      enabled: false,
    },
    treeConfig: { parentField: 'parentId', rowField: 'id', transform: true },
  } as VxeTableGridOptions<MesDvMachineryTypeApi.MachineryType>,
  gridEvents: {
    toggleTreeExpand: ({
      row,
      expanded,
    }: {
      expanded: boolean;
      row: MesDvMachineryTypeApi.MachineryType;
    }) => {
      expandedIds.value = expanded
        ? [...new Set([...expandedIds.value, row.id!])]
        : expandedIds.value.filter((id) => id !== row.id);
    },
    cellClick: ({ row }: { row: MesDvMachineryTypeApi.MachineryType }) => {
      if (selectedId.value === row.id) {
        selectedId.value = undefined;
        gridApi.grid.clearCurrentRow();
        emit('nodeClick', undefined);
        return;
      }
      selectedId.value = row.id;
      emit('nodeClick', row);
    },
  },
});

/** 重置选中状态 */
function reset() {
  selectedId.value = undefined;
  searchKeyword.value = '';
  gridApi.grid.clearCurrentRow();
  emit('nodeClick', undefined);
  void refreshTree();
}

/** 搜索设备类型 */
function handleSearch() {
  void refreshTree();
}

defineExpose({ reset });
</script>

<template>
  <Card class="h-full" size="small" title="设备类型">
    <div class="mb-3 flex items-center gap-2">
      <Input
        v-model:value="searchKeyword"
        allow-clear
        class="min-w-0 flex-1"
        placeholder="搜索设备类型"
        @change="handleSearch"
      />
      <TreeToolbar
        :expanded="allExpanded"
        :loading="loading"
        @toggle-expand="toggleExpand"
        @refresh="refreshTree"
      />
    </div>
    <Grid class="h-full" />
  </Card>
</template>
