<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Modal as ConfirmModal,
  DatePicker,
  Form,
  Input,
  InputNumber,
  message,
  TabPane,
  Tabs,
  TextArea,
  Tooltip,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getMasterDataPage } from '#/api/governance/masterdata';
import { submitMasterData } from '#/api/governance/mastermanage';

import { clearReactiveObject } from '../../common/form-layout';
import { masterSubmitCommand } from '../../mastercommon/master-submit';
import { useMasterScope } from '../../mastercommon/use-master-scope';
import { fieldTypePlaceholder } from '../../masterdata/data';
import DataFilter from './data-filter.vue';

const props = defineProps<{ model: GovernanceMasterDataApi.Model }>();
const router = useRouter();
const columns = computed(() => props.model.columns ?? []);
const canWrite = computed(() => props.model.maintenanceSupported === true);
const maintenanceHint = computed(
  () =>
    props.model.maintenanceUnsupportedReason ||
    (props.model.maintenanceSupported === false
      ? '当前数据源暂不支持主数据维护，请使用 PostgreSQL 数据源。'
      : '尚未获取维护能力，请刷新模型信息后重试。'),
);
const appliedConditions = ref<GovernanceMasterDataApi.DataCondition[]>([]);
const dataLoading = ref(false);
const edit = reactive<GovernanceMasterDataApi.Row>({});
const datetimeValues = reactive<Record<string, string | undefined>>({});
const failed = ref(false);
const creating = ref(true);
const readonly = ref(false);
const filterVisible = ref(false);
const writeFailed = ref(false);
const receipt = ref<GovernanceMasterManageApi.SubmitReceipt>();
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const scope = useMasterScope(() => {
  gridApi.setGridOptions({ data: [] });
  void editApi.close();
});
let keyField = '__masterRowKey';
while (columns.value.some((column) => column.name === keyField))
  keyField += '_';
const tableColumns: VxeTableGridOptions<GovernanceMasterDataApi.Row>['columns'] =
  [
    { type: 'seq', title: '编号', width: 60 },
    ...columns.value.map((row) => ({
      title: row.commentStr || row.name,
      field: row.name,
      minWidth: row.fieldType === 9 ? 180 : 150,
      formatter: ({ cellValue }: { cellValue: unknown }) =>
        cell(row.name!, cellValue),
    })),
    {
      title: '操作',
      width: 225,
      fixed: 'right',
      slots: { default: 'actions' },
      showOverflow: false,
    },
  ];
const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: tableColumns,
    showOverflow: false,
    rowConfig: { keyField, isHover: true },
    proxyConfig: { enabled: false },
    pagerConfig: {
      currentPage: 1,
      pageSize: 10,
      total: 0,
    },
    toolbarConfig: {
      search: false,
      custom: true,
      refresh: true,
      refreshOptions: { queryMethod: load },
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize,
    }: {
      currentPage: number;
      pageSize: number;
    }) => pageChange(currentPage, pageSize),
  },
});
function dataRow(value: unknown) {
  const record = value as GovernanceMasterDataApi.Row;
  return Object.fromEntries(
    columns.value
      .filter((column) => column.name)
      .map((column) => [column.name!, record[column.name!]]),
  );
}
function rowKey(value: unknown) {
  const row = dataRow(value);
  return JSON.stringify(
    columns.value
      .filter((column) => column.pk === 1)
      .map((column) => [column.name, row[column.name!]]),
  );
}
function cell(name: string, value: unknown) {
  if (value === null || value === undefined || value === '') return '-';
  return columns.value.find((column) => column.name === name)?.fieldType === 9
    ? formatDateTime(value as number | string) || String(value)
    : String(value);
}
async function load() {
  const current = scope.begin();
  dataLoading.value = true;
  gridApi.setGridOptions({ loading: true });
  failed.value = false;
  try {
    const result = await getMasterDataPage({
      modelId: props.model.id!,
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      conditions: appliedConditions.value.map((condition) => ({
        ...condition,
      })),
    });
    if (!current()) return;
    pagination.total = result.total ?? 0;
    gridApi.setGridOptions({
      data: (result.list ?? []).map((row) => ({
        ...row,
        [keyField]: rowKey(row),
      })),
      pagerConfig: {
        currentPage: pagination.pageNo,
        pageSize: pagination.pageSize,
        total: pagination.total,
      },
    });
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) {
      dataLoading.value = false;
      gridApi.setGridOptions({ loading: false });
    }
  }
}
function search(conditions: GovernanceMasterDataApi.DataCondition[]) {
  appliedConditions.value = conditions.map((condition) => ({ ...condition }));
  pagination.pageNo = 1;
  void load();
}
async function editRow(row?: GovernanceMasterDataApi.Row, viewOnly = false) {
  if (!viewOnly && !canWrite.value) return;
  const current = scope.begin('row');
  creating.value = row === undefined;
  readonly.value = viewOnly;
  writeFailed.value = false;
  receipt.value = undefined;
  clearReactiveObject(edit);
  clearReactiveObject(datetimeValues);
  let latest = row;
  if (row) {
    const keys = columns.value.filter(
      (column) => column.pk === 1 && column.name,
    );
    if (keys.length > 0) {
      let result: GovernanceMasterDataApi.DataPageResult;
      try {
        result = await getMasterDataPage({
          modelId: props.model.id!,
          pageNo: 1,
          pageSize: 2,
          conditions: keys.map((column) => ({
            name: column.name!,
            operator: 'EQ',
            value: String(row[column.name!]),
          })),
        });
      } catch {
        if (current()) message.error('当前行加载失败，请重试');
        return;
      }
      if (!current()) return;
      if (result.list?.length !== 1) {
        message.warning('数据已变化，请刷新列表后重试');
        return;
      }
      latest = result.list[0];
    }
  }
  if (!current()) return;
  Object.assign(edit, latest ?? {});
  for (const column of columns.value) {
    if (
      column.name &&
      column.fieldType === 9 &&
      edit[column.name] !== undefined
    )
      datetimeValues[column.name] = formatDateTime(
        edit[column.name] as number | string,
      );
  }
  editApi.open();
}
function updateDatetime(name: string, value: unknown) {
  if (typeof value === 'string' || value === null || value === undefined)
    edit[name] = value ?? undefined;
}
async function write(
  operation: GovernanceMasterManageApi.Operation,
  values: GovernanceMasterDataApi.Row[],
) {
  const current = scope.begin('write');
  const command = masterSubmitCommand(scope.owner.value, {
    modelId: props.model.id!,
    operation,
    rows: values,
  });
  const result = await submitMasterData(command.data);
  if (!['APPLYING', 'APPROVED', 'UNKNOWN'].includes(result.status))
    command.complete();
  if (!current()) return false;
  receipt.value = result;
  if (result.status === 'APPLIED') message.success('数据变更已生效');
  else if (result.status === 'PENDING' || result.status === 'WAIT_START')
    message.success('已提交审批，待审批通过后生效');
  else if (result.status === 'UNKNOWN')
    message.warning('数据变更结果待核查，请查看审批记录');
  else if (result.status === 'REJECTED')
    message.error(result.message || '数据变更未生效，请查看处理结果');
  else message.info('数据变更已受理，请查看审批记录');
  await load();
  return result.status !== 'REJECTED';
}
function remove(row: GovernanceMasterDataApi.Row) {
  if (!canWrite.value) return;
  const authorized = scope.begin('confirmation');
  ConfirmModal.confirm({
    title: '删除数据',
    content: props.model.approvalEnabled
      ? '确认提交该数据的删除申请？审批通过后生效。'
      : '确认删除该行数据？',
    okButtonProps: { danger: true },
    async onOk() {
      if (!authorized()) return;
      await write('DELETE', [row]);
    },
  });
}
const [EditModal, editApi] = useVbenModal({
  async onConfirm() {
    if (readonly.value) return;
    for (const column of columns.value) {
      const value = edit[column.name!];
      if (
        column.nullable === 0 &&
        !(creating.value && column.name === props.model.enabledCodeField) &&
        (value === undefined || value === null || value === '')
      ) {
        message.warning(`请填写${column.commentStr || column.name}`);
        return;
      }
    }
    const payload = Object.fromEntries(
      columns.value
        .filter((column) => column.name)
        .map((column) => [column.name!, edit[column.name!]]),
    );
    editApi.lock();
    writeFailed.value = false;
    try {
      if (await write(creating.value ? 'CREATE' : 'UPDATE', [payload]))
        await editApi.close();
    } catch {
      writeFailed.value = true;
    } finally {
      editApi.unlock();
    }
  },
});
watch(
  () => props.model.id,
  () => {
    scope.invalidate();
    gridApi.setGridOptions({ data: [] });
    pagination.pageNo = 1;
    receipt.value = undefined;
    appliedConditions.value = [];
    void editApi.close();
    void load();
  },
  { immediate: true },
);
function pageChange(page: number, size: number) {
  pagination.pageNo = page;
  pagination.pageSize = size;
  void load();
}
</script>

<template>
  <section class="flex h-full min-h-0 flex-col">
    <Alert
      v-if="!canWrite"
      type="warning"
      show-icon
      class="mb-4"
      :message="maintenanceHint"
    />
    <Alert
      v-if="model.approvalEnabled"
      type="info"
      show-icon
      class="mb-4"
      message="此模型已开启审批，新增、编辑和删除将在审批通过后生效。"
    />
    <Alert
      v-if="failed"
      type="error"
      show-icon
      class="mb-4"
      message="数据加载失败，请重新查询。"
    />
    <Alert
      v-if="receipt?.status === 'REJECTED'"
      type="error"
      show-icon
      class="mb-4"
      :message="receipt.message || '数据变更未生效，请查看审批记录。'"
    />
    <Alert
      v-if="receipt?.status === 'UNKNOWN'"
      type="warning"
      show-icon
      class="mb-4"
      message="数据变更结果待核查，请勿重复发起变更。"
    >
      <template #action>
        <Button
          v-access:code="'governance:master-approval:query'"
          size="small"
          @click="router.push({ name: 'GovernanceMasterApproval' })"
        >
          <template #icon><IconifyIcon icon="lucide:history" /></template>
          查看审批记录
        </Button>
      </template>
    </Alert>
    <Tabs active-key="data"><TabPane key="data" tab="数据列表" /></Tabs>
    <DataFilter
      v-show="filterVisible"
      :key="scope.owner.value"
      :columns="columns"
      :applied-conditions="appliedConditions"
      :allowed-operators="model.queryFilterOperators"
      :loading="dataLoading"
      class="mb-3 shrink-0"
      @apply="search"
    />
    <Grid class="metadata-grid qdata-maintain-data-grid" table-title="数据列表">
      <template #toolbar-tools>
        <div class="metadata-toolbar">
          <div class="metadata-toolbar">
            <TableAction
              :actions="[
                {
                  label: '新增',
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: ['governance:master-data:create'],
                  disabled: !canWrite,
                  onClick: () => editRow(),
                },
              ]"
            />
            <Tooltip title="参考功能尚未开放">
              <span><Button disabled><IconifyIcon icon="lucide:upload" />导入</Button></span>
            </Tooltip>
            <Tooltip title="参考功能尚未开放">
              <span><Button disabled><IconifyIcon icon="lucide:search-check" />查重</Button></span>
            </Tooltip>
          </div>

          <div class="metadata-tools">
            <Button
              aria-label="数据筛选"
              :title="filterVisible ? '收起筛选' : '展开筛选'"
              @click="filterVisible = !filterVisible"
            >
              <IconifyIcon icon="lucide:search" />
            </Button>
          </div>
        </div>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '修改',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:master-data:update'],
              ifShow: canWrite,
              onClick: () => editRow(dataRow(row)),
            },
            {
              label: '删除',
              type: 'link',
              icon: ACTION_ICON.DELETE,
              danger: true,
              auth: ['governance:master-data:delete'],
              ifShow: canWrite,
              onClick: () => remove(dataRow(row)),
            },
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:master-data:query'],
              onClick: () => editRow(dataRow(row), true),
            },
          ]"
        />
      </template>
    </Grid>
    <EditModal
      :title="
        readonly
          ? '主数据详情'
          : `${creating ? '新增' : '修改'}${model.tableCn || model.tableName}数据`
      "
      class="metadata-modal qdata-master-row-modal"
      header-class="metadata-modal__header"
      content-class="metadata-modal__body"
      footer-class="metadata-modal__footer"
      :show-confirm-button="!readonly"
      :cancel-text="readonly ? '关闭' : '取消'"
    >
      <Alert
        v-if="receipt?.status === 'REJECTED'"
        type="error"
        show-icon
        class="mb-4"
        :message="receipt.message || '数据变更未生效，请修正后重新提交。'"
      />
      <Alert
        v-if="writeFailed"
        type="warning"
        show-icon
        class="mb-4"
        message="提交未完成，重试相同内容会沿用原请求号。可到审批记录查看处理结果。"
      />
      <Form class="qdata-master-row-form">
        <div
          v-for="column in columns"
          :key="column.name"
          class="metadata-form-item"
          :class="{
            'metadata-form-item--required':
              !readonly &&
              column.nullable === 0 &&
              !(creating && column.name === model.enabledCodeField),
          }"
        >
          <label>{{ column.commentStr || column.name }}</label>
          <Input
            v-if="readonly"
            :value="cell(column.name!, edit[column.name!])"
            readonly
          />
          <DatePicker
            v-else-if="column.fieldType === 9 && (creating || column.pk !== 1)"
            v-model:value="datetimeValues[column.name!]"
            @update:value="(value) => updateDatetime(column.name!, value)"
            show-time
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            :disabled="
              (!creating && column.pk === 1) ||
              column.name === model.enabledCodeField
            "
            class="w-full"
          />
          <InputNumber
            v-else-if="[1, 5, 6].includes(column.fieldType ?? 0)"
            v-model:value="edit[column.name!] as string"
            string-mode
            :disabled="
              (!creating && column.pk === 1) ||
              column.name === model.enabledCodeField
            "
            :placeholder="fieldTypePlaceholder(column.fieldType)"
          />
          <TextArea
            v-else-if="(column.fieldLength ?? 0) >= 500"
            v-model:value="edit[column.name!] as string"
            :rows="4"
            :maxlength="column.fieldLength ?? undefined"
            show-count
            :placeholder="`请输入${column.commentStr || column.name}`"
            :disabled="
              (!creating && column.pk === 1) ||
              column.name === model.enabledCodeField
            "
          />
          <Input
            v-else
            v-model:value="edit[column.name!] as string"
            :disabled="
              (!creating && column.pk === 1) ||
              column.name === model.enabledCodeField
            "
            :placeholder="
              creating && column.name === model.enabledCodeField
                ? '留空自动生成编码'
                : `请输入${column.commentStr || column.name}`
            "
          />
        </div>
      </Form>
    </EditModal>
  </section>
</template>
