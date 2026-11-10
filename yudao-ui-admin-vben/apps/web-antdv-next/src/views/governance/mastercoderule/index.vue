<script setup lang="ts">
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

import { onMounted, reactive, ref } from 'vue';

import { Page, useVbenDrawer } from '@vben/common-ui';

import { message, Modal } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getMasterModelSimpleList } from '#/api/governance/masterdata';
import {
  deleteMasterCodeRule,
  deleteMasterCodeRuleList,
  getMasterCodeRulePage,
  updateMasterCodeRuleStatus,
} from '#/api/governance/mastermanage';
import PageGuide from '#/components/page-guide/index.vue';

import { useMasterScope } from '../mastercommon/use-master-scope';
import { useGridColumns, useGridFormSchema } from './data';
import RuleDetail from './modules/detail.vue';
import RuleForm from './modules/form.vue';

defineOptions({ name: 'GovernanceMasterCodeRule' });
const query = reactive<{
  modelId?: GovernanceMasterManageApi.Id;
  name?: string;
}>({});
const models = ref<{ label: string; value: number | string }[]>([]);
const selected = ref<GovernanceMasterManageApi.Id[]>([]);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const failed = ref(false);
const modelsFailed = ref(false);
const scope = useMasterScope(() => {
  selected.value = [];
  models.value = [];
  query.modelId = undefined;
  pagination.pageNo = 1;
  gridApi.setGridOptions({ data: [] });
  void gridApi.formApi.setFieldValue('modelId', undefined);
  void refresh();
});
const [FormDrawer, formApi] = useVbenDrawer({
  connectedComponent: RuleForm,
  destroyOnClose: true,
});
const [DetailDrawer, detailDrawerApi] = useVbenDrawer<{
  id?: GovernanceMasterManageApi.Id;
}>({
  connectedComponent: RuleDetail,
  destroyOnClose: true,
});
const [Grid, gridApi] = useVbenVxeGrid<GovernanceMasterManageApi.CodeRule>({
  formOptions: {
    schema: useGridFormSchema(() => models.value),
    handleSubmit: async (values) => {
      query.modelId = values.modelId;
      query.name = values.name;
      await search();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  gridOptions: {
    showOverflow: false,
    columns: useGridColumns(),
    height: 'auto',
    rowConfig: { keyField: 'id', isHover: true },
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: refresh },
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
    checkboxAll: selectionChange,
    checkboxChange: selectionChange,
  },
});
function selectionChange() {
  selected.value = gridApi.grid
    .getCheckboxRecords()
    .map((row: GovernanceMasterManageApi.CodeRule) => row.id!);
}
async function loadModels() {
  const current = scope.begin('models');
  modelsFailed.value = false;
  try {
    const list = await getMasterModelSimpleList();
    if (current())
      models.value = list.map((row) => ({
        label: row.tableCn || row.tableName || String(row.id),
        value: row.id!,
      }));
  } catch {
    if (current()) modelsFailed.value = true;
  }
}
async function load() {
  const current = scope.begin();
  failed.value = false;
  gridApi.setGridOptions({ loading: true });
  try {
    const result = await getMasterCodeRulePage({
      ...query,
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
    });
    if (!current()) return;
    selected.value = [];
    pagination.total = result.total;
    gridApi.setGridOptions({
      data: result.list,
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
async function refresh() {
  await Promise.all([loadModels(), load()]);
}
async function search() {
  pagination.pageNo = 1;
  await load();
}
function reset() {
  query.name = undefined;
  query.modelId = undefined;
  void search();
}
function edit(row?: GovernanceMasterManageApi.CodeRule) {
  formApi.setData({ id: row?.id }).open();
}
function detail(row: GovernanceMasterManageApi.CodeRule) {
  detailDrawerApi.setData({ id: row.id }).open();
}
function remove(ids: GovernanceMasterManageApi.Id[]) {
  if (ids.length === 0) return;
  const authorized = scope.begin('confirmation');
  Modal.confirm({
    title: '删除编码规则',
    content: `确认删除选中的 ${ids.length} 条编码规则？`,
    okButtonProps: { danger: true },
    async onOk() {
      if (!authorized()) return;
      const current = scope.begin('write');
      await (ids.length === 1
        ? deleteMasterCodeRule(ids[0]!)
        : deleteMasterCodeRuleList(ids));
      if (!current()) return;
      selected.value = [];
      gridApi.grid.clearCheckboxRow();
      message.success('编码规则已删除');
      await load();
    },
  });
}
function toggle(row: GovernanceMasterManageApi.CodeRule) {
  const enabled = row.status !== 0;
  const authorized = scope.begin('confirmation');
  Modal.confirm({
    title: enabled ? '启用编码规则' : '停用编码规则',
    content: enabled
      ? '启用后将自动停用该模型下其他编码规则，确认启用？'
      : '确认停用该编码规则？',
    async onOk() {
      if (!authorized()) return;
      const current = scope.begin('write');
      await updateMasterCodeRuleStatus(row.id!, enabled ? 0 : 1);
      if (!current()) return;
      message.success(enabled ? '编码规则已启用' : '编码规则已停用');
      await load();
    },
  });
}
onMounted(refresh);
</script>

<template>
  <Page auto-content-height>
    <FormDrawer @success="load" />
    <DetailDrawer />
    <div class="flex h-full flex-col gap-4">
      <PageGuide
        guide-id="governance.master-code-rule.auto-code"
        type="info"
        show-icon
        message="编码规则定义了主数据编码的自动生成方式（如 STU-20260413-001）。为模型配置并启用编码规则后，新增数据时系统将自动生成编码，无需手动填写。每个模型仅可启用一条编码规则。"
      />
      <Grid class="min-h-0 flex-1" table-title="编码规则列表">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: '新增',
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['governance:master-code-rule:create'],
                onClick: () => edit(),
              },
              {
                label: '批量删除',
                type: 'primary',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:master-code-rule:delete'],
                disabled: selected.length === 0,
                onClick: () => remove(selected),
              },
            ]"
          />
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: '编辑',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['governance:master-code-rule:update'],
                onClick: () => edit(row),
              },
              {
                label: '详情',
                type: 'link',
                icon: ACTION_ICON.VIEW,
                auth: ['governance:master-code-rule:query'],
                onClick: () => detail(row),
              },
              {
                label: row.status === 0 ? '停用' : '启用',
                icon:
                  row.status === 0
                    ? 'lucide:circle-off'
                    : 'lucide:circle-check',
                type: 'link',
                auth: ['governance:master-code-rule:update'],
                onClick: () => toggle(row),
              },
              {
                label: '删除',
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:master-code-rule:delete'],
                onClick: () => remove([row.id!]),
              },
            ]"
          />
        </template>
      </Grid>
    </div>
  </Page>
</template>
