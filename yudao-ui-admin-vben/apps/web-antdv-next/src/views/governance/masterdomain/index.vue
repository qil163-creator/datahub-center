<script setup lang="ts">
import type { GovernanceMasterDomainApi } from '#/api/governance/masterdomain';

import { onMounted, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { Alert, message, Modal } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteMasterDomain,
  getMasterDomainPage,
  updateMasterDomainStatus,
} from '#/api/governance/masterdomain';
import PageGuide from '#/components/page-guide/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

import { useMasterScope } from '../mastercommon/use-master-scope';
import { useGridColumns, useGridFormSchema } from './data';
import DomainDetail from './modules/detail.vue';
import DomainForm from './modules/form.vue';

defineOptions({ name: 'GovernanceMasterDomain' });
const query = reactive<{
  code?: string;
  name?: string;
  status?: number;
}>({});
const pagination = reactive({ pageNo: 1, pageSize: 20, total: 0 });
const rows = ref<GovernanceMasterDomainApi.Domain[]>([]);
const failed = ref(false);
const { hasAccessByCodes } = useAccess();
const scope = useMasterScope(() => {
  rows.value = [];
  gridApi.setGridOptions({ data: [] });
  void search();
});
const [DetailDrawer, detailDrawerApi] = useVbenDrawer<{
  id?: GovernanceMasterDomainApi.Id;
}>({
  connectedComponent: DomainDetail,
  destroyOnClose: true,
});
const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: DomainForm,
  destroyOnClose: true,
});
const [Grid, gridApi] = useVbenVxeGrid<GovernanceMasterDomainApi.Domain>({
  formOptions: {
    schema: useGridFormSchema(),
    handleSubmit: async (values) => {
      query.name = values.name;
      query.code = values.code;
      query.status = values.status;
      await search();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  gridOptions: {
    showOverflow: false,
    columns: useGridColumns(handleStatusChange, () =>
      hasAccessByCodes(['governance:master-domain:update']),
    ),
    height: 'auto',
    rowConfig: { keyField: 'id', isHover: true },
    pagerConfig: { currentPage: 1, pageSize: 20, total: 0 },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: load },
      search: true,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      pagination.pageNo = currentPage;
      pagination.pageSize = pageSize;
      void load();
    },
  },
});
async function load() {
  const current = scope.begin();
  failed.value = false;
  gridApi.setGridOptions({ loading: true });
  try {
    const result = await getMasterDomainPage({
      ...query,
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
    });
    if (!current()) return;
    rows.value = result.list;
    pagination.total = result.total;
    gridApi.setGridOptions({
      data: rows.value,
      pagerConfig: {
        currentPage: pagination.pageNo,
        pageSize: pagination.pageSize,
        total: result.total,
      },
    });
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) {
      gridApi.setGridOptions({ loading: false });
    }
  }
}
async function search() {
  pagination.pageNo = 1;
  await load();
}
function reset() {
  query.name = undefined;
  query.code = undefined;
  query.status = undefined;
  void search();
}
function edit(row?: GovernanceMasterDomainApi.Domain) {
  formModalApi.setData(row ?? {}).open();
}
function detail(row: GovernanceMasterDomainApi.Domain) {
  detailDrawerApi.setData({ id: row.id }).open();
}
function remove(row: GovernanceMasterDomainApi.Domain) {
  const authorized = scope.begin('confirmation');
  Modal.confirm({
    title: '删除主数据域',
    content: `确认删除「${row.name}」？已关联模型的域无法删除。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    async onOk() {
      if (!authorized()) return;
      const current = scope.begin('write');
      await deleteMasterDomain(row.id!);
      if (!current()) return;
      message.success('主数据域已删除');
      await load();
    },
  });
}
async function handleStatusChange(
  status: number,
  row: GovernanceMasterDomainApi.Domain,
) {
  if (!hasAccessByCodes(['governance:master-domain:update'])) return false;
  const current = scope.begin(`status:${String(row.id)}`);
  const action = status === CommonStatusEnum.ENABLE ? '启用' : '停用';
  try {
    await confirm(`确认要${action}「${row.name}」吗？`);
  } catch {
    return false;
  }
  if (!current()) return false;
  await updateMasterDomainStatus(row.id!, status);
  if (!current()) return false;
  message.success(`${action}成功`);
  return true;
}
onMounted(load);
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="load" />
    <DetailDrawer />
    <div class="flex h-full flex-col gap-4">
      <PageGuide
        guide-id="governance.master-domain.workflow"
        type="info"
        show-icon
      >
        <template #message>
          主数据域是组织主数据的分类容器（如「人员」「组织」「物资」等）。
          <strong>使用步骤：</strong>
          ① 在此创建域 → ② 到「数据模型」创建模型并定义属性 → ③
          发布模型后到「数据维护」录入数据。
        </template>
      </PageGuide>
      <Alert
        v-if="failed"
        type="error"
        show-icon
        message="主数据域加载失败，请重新查询。"
      />
      <Grid class="min-h-0 flex-1" table-title="主数据域列表">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: '新增',
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['governance:master-domain:create'],
                onClick: () => edit(),
              },
            ]"
          />
        </template>
        <template #description="{ row }">
          <TextEllipsisCell :content="row.description" :line="2" />
        </template>
        <template #remark="{ row }">
          <TextEllipsisCell :content="row.remark" :line="2" />
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: '修改',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['governance:master-domain:update'],
                onClick: () => edit(row),
              },
              {
                label: '删除',
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:master-domain:delete'],
                onClick: () => remove(row),
              },
              {
                label: '详情',
                type: 'link',
                icon: ACTION_ICON.VIEW,
                auth: ['governance:master-domain:query'],
                onClick: () => detail(row),
              },
            ]"
          />
        </template>
      </Grid>
    </div>
  </Page>
</template>
