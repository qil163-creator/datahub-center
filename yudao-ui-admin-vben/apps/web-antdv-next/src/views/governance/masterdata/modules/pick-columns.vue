<script lang="ts" setup>
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { nextTick, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message, Tag } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { useVbenForm } from '#/adapter/form';
import { getDatabaseTableList } from '#/api/dataforge/database';
import { getMasterTableColumns } from '#/api/governance/masterdata';

import { useMasterScope } from '../../mastercommon/use-master-scope';
import { fieldTypeLabel } from '../data';

defineOptions({ name: 'GovernanceMasterPickColumns' });

const emit = defineEmits<{
  (e: 'picked', columns: GovernanceMasterDataApi.Column[]): void;
}>();

const databaseId = ref<GovernanceMasterDataApi.Id>();
const tables = ref<{ label: string; value: string }[]>([]);
const columns = ref<GovernanceMasterDataApi.Column[]>([]);
const selectedNames = ref<string[]>([]);
const scope = useMasterScope(() => {
  void modalApi.close();
});

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 80 },
  layout: 'horizontal',
  wrapperClass: 'grid-cols-1',
  showDefaultActions: false,
  schema: [
    {
      fieldName: 'tableName',
      label: '选择表',
      component: 'Select',
      componentProps: () => ({
        options: tables.value,
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
        placeholder: '请选择数据源中的已有表',
        onChange: handleTableChange,
      }),
    },
  ],
});
const [Grid, gridApi] = useVbenVxeGrid<GovernanceMasterDataApi.Column>({
  gridOptions: {
    columns: [
      { type: 'checkbox', width: 40 },
      { field: 'name', title: '字段名', minWidth: 180, align: 'left' },
      { field: 'commentStr', title: '注释', minWidth: 200, align: 'left' },
      {
        field: 'fieldType',
        title: '类型',
        width: 120,
        formatter: ({ cellValue }: { cellValue?: number }) =>
          fieldTypeLabel(cellValue),
      },
      { field: 'fieldLength', title: '长度', width: 80 },
      { field: 'pk', title: '主键', width: 80, slots: { default: 'pk' } },
    ],
    data: [],
    height: 380,
    rowConfig: { keyField: 'name', isHover: true },
    pagerConfig: { enabled: false },
    proxyConfig: { enabled: false, autoLoad: false },
    toolbarConfig: { enabled: false },
  },
  gridEvents: {
    checkboxAll: ({
      records,
    }: {
      records: GovernanceMasterDataApi.Column[];
    }) => {
      selectedNames.value = records.map((row) => row.name!);
    },
    checkboxChange: ({
      records,
    }: {
      records: GovernanceMasterDataApi.Column[];
    }) => {
      selectedNames.value = records.map((row) => row.name!);
    },
  },
});

async function handleTableChange(tableName?: string) {
  const current = scope.begin('columns');
  columns.value = [];
  selectedNames.value = [];
  gridApi.setGridOptions({ data: [] });
  if (!tableName || !databaseId.value) {
    gridApi.setLoading(false);
    return;
  }
  gridApi.setLoading(true);
  try {
    const list = await getMasterTableColumns(databaseId.value, tableName);
    if (!current()) return;
    columns.value = list;
    selectedNames.value = columns.value.map((column) => column.name!);
    gridApi.setGridOptions({ data: list });
    await nextTick();
    if (current()) await gridApi.grid.setAllCheckboxRow(true);
  } finally {
    if (current()) gridApi.setLoading(false);
  }
}

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  confirmText: '引入所选字段',
  async onConfirm() {
    const picked = columns.value.filter((column) =>
      selectedNames.value.includes(column.name!),
    );
    if (picked.length === 0) {
      message.warning('请选择要引入的字段');
      return;
    }
    emit('picked', picked);
    await modalApi.close();
  },
  async onOpenChange(isOpen: boolean) {
    scope.invalidate();
    gridApi.setLoading(false);
    await formApi.reset();
    if (!isOpen) {
      columns.value = [];
      tables.value = [];
      selectedNames.value = [];
      gridApi.setGridOptions({ data: [] });
      return;
    }
    const data = modalApi.getData() as {
      databaseId: GovernanceMasterDataApi.Id;
    };
    databaseId.value = data?.databaseId;
    if (!databaseId.value) {
      return;
    }
    modalApi.lock();
    const current = scope.begin('tables');
    try {
      const list = await getDatabaseTableList(databaseId.value);
      if (!current()) return;
      tables.value = (list ?? []).map((item) => ({
        label: item.remarks
          ? `${item.tableName}（${item.remarks}）`
          : item.tableName,
        value: item.tableName,
      }));
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
</script>

<template>
  <ModalComponent class="w-[800px]" title="从已有表拾取字段">
    <div class="mx-4">
      <Form />
      <div class="text-muted-foreground mb-3 text-sm">
        读取已有表结构，将所选字段引入模型。
      </div>
      <Grid>
        <template #pk="{ row }">
          <Tag v-if="row.pk === 1" color="warning">主键</Tag>
          <span v-else>-</span>
        </template>
      </Grid>
    </div>
  </ModalComponent>
</template>
