<script setup lang="ts">
import type { VbenFormSchema } from '#/adapter/form';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DatabaseMonitorApi } from '#/api/dataforge/database/monitor';

import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Descriptions,
  DescriptionsItem,
  message,
  Switch,
  Tag,
} from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  createDatabaseRule,
  deleteDatabaseRule,
  getDatabaseRule,
  getDatabaseRulePage,
  updateDatabaseRule,
  updateDatabaseRuleStatus,
} from '#/api/dataforge/database/monitor';
import { createLatestWins } from '#/utils/resource-id';

import {
  LEVEL_COLORS,
  LEVEL_OPTIONS,
  levelLabel,
  METRIC_OPTIONS,
  metricLabel,
  OPERATOR_OPTIONS,
  triggerCondition,
} from './monitor-enums';

const props = defineProps<{ databaseId: FoundationDatabaseApi.Id }>();
const emit = defineEmits<{ change: [] }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canUpdate = computed(() =>
  hasAccessByCodes(['dataforge:database:update']),
);
const page = reactive({ pageNo: 1, pageSize: 20 });
const failed = ref(false);
const busy = ref(false);
const listGuard = createLatestWins();
const formGuard = createLatestWins();
const detailGuard = createLatestWins();
const mutationGuard = createLatestWins();
const editingId = ref<FoundationDatabaseApi.Id>();
const selected = ref<DatabaseMonitorApi.Rule>();
const detailLoading = ref(false);
const detailFailed = ref(false);
const formFailed = ref(false);

const schema: VbenFormSchema[] = [
  {
    fieldName: 'name',
    label: '规则名称',
    component: 'Input',
    rules: 'required',
    componentProps: { maxlength: 100, placeholder: '请输入规则名称' },
  },
  {
    fieldName: 'description',
    label: '描述',
    component: 'TextArea',
    componentProps: {
      maxlength: 500,
      rows: 3,
      placeholder: '说明监控目的或处置建议',
    },
  },
  {
    fieldName: 'metric',
    label: '告警指标',
    component: 'Select',
    rules: 'required',
    componentProps: { options: METRIC_OPTIONS },
  },
  {
    fieldName: 'operator',
    label: '触发条件',
    component: 'Select',
    rules: 'required',
    componentProps: { options: OPERATOR_OPTIONS },
    dependencies: {
      triggerFields: ['metric'],
      show: (values) => values.metric === 'RESPONSE_TIME',
    },
  },
  {
    fieldName: 'threshold',
    label: '阈值（ms）',
    component: 'Input',
    componentProps: { placeholder: '请输入大于 0 的响应时间，单位 ms' },
    dependencies: {
      triggerFields: ['metric'],
      show: (values) => values.metric === 'RESPONSE_TIME',
    },
  },
  {
    fieldName: 'level',
    label: '告警级别',
    component: 'Select',
    rules: 'required',
    componentProps: { options: LEVEL_OPTIONS },
  },
  {
    fieldName: 'status',
    label: '状态',
    component: 'RadioGroup',
    rules: 'required',
    componentProps: {
      options: [
        { label: '启用', value: 0 },
        { label: '停用', value: 1 },
      ],
    },
  },
  {
    fieldName: 'owner',
    label: '负责人',
    component: 'Input',
    componentProps: { maxlength: 100, placeholder: '请输入负责人说明' },
  },
];

const [Form, formApi] = useVbenForm({
  layout: 'horizontal',
  commonConfig: { labelWidth: 110, componentProps: { class: 'w-full' } },
  wrapperClass: 'grid-cols-1',
  schema,
  showDefaultActions: false,
});

const [FormModal, formModalApi] = useVbenModal({
  async onConfirm() {
    if (formFailed.value || busy.value) return;
    const identity = JSON.stringify([
      props.databaseId,
      access.accessToken,
      access.refreshToken,
    ]);
    const { valid } = await formApi.validate();
    if (!valid) return;
    const values = await formApi.getValues<DatabaseMonitorApi.RuleSave>();
    if (
      identity !==
      JSON.stringify([
        props.databaseId,
        access.accessToken,
        access.refreshToken,
      ])
    )
      return;
    const threshold = String(values.threshold ?? '').trim();
    if (
      values.metric === 'RESPONSE_TIME' &&
      (!/^\d+(?:\.\d+)?$/.test(threshold) || !/[1-9]/.test(threshold))
    ) {
      message.warning('响应时间阈值必须为大于 0 的十进制数');
      return;
    }
    const data: DatabaseMonitorApi.RuleSave = {
      databaseId: props.databaseId,
      id: editingId.value,
      name: values.name.trim(),
      description: values.description?.trim(),
      metric: values.metric,
      operator: values.metric === 'CONNECTION_STATUS' ? 'EQ' : values.operator,
      threshold: values.metric === 'CONNECTION_STATUS' ? '0' : threshold,
      level: values.level,
      status: values.status,
      owner: values.owner?.trim() ?? '',
    };
    formModalApi.lock();
    try {
      await mutate(
        async () => {
          await (data.id ? updateDatabaseRule(data) : createDatabaseRule(data));
        },
        () => formModalApi.close(),
      );
    } finally {
      formModalApi.unlock();
    }
  },
  onOpenChange(open) {
    if (!open) {
      formGuard.invalidate();
      editingId.value = undefined;
      formFailed.value = false;
      formApi.reset();
    }
  },
});

const [DetailModal, detailApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(open) {
    if (!open) {
      detailGuard.invalidate();
      selected.value = undefined;
      detailLoading.value = false;
    }
  },
});

const [Grid, gridApi] = useVbenVxeGrid<DatabaseMonitorApi.Rule>({
  gridOptions: {
    columns: [
      { field: 'id', title: '编号', width: 100 },
      { field: 'name', title: '规则名称', minWidth: 180 },
      {
        field: 'description',
        title: '描述',
        minWidth: 240,
        showOverflow: 'tooltip',
      },
      {
        field: 'metric',
        title: '告警指标',
        minWidth: 130,
        slots: { default: 'metric' },
      },
      {
        field: 'threshold',
        title: '触发条件',
        minWidth: 145,
        formatter: ({ row }: { row: DatabaseMonitorApi.Rule }) =>
          triggerCondition(row),
      },
      {
        field: 'level',
        title: '告警级别',
        width: 110,
        slots: { default: 'level' },
      },
      {
        field: 'status',
        title: '状态',
        width: 90,
        slots: { default: 'status' },
      },
      {
        field: 'owner',
        title: '负责人',
        minWidth: 130,
        formatter: ({ cellValue }: { cellValue: unknown }) =>
          String(cellValue || '—'),
      },
      { field: 'creator', title: '创建人', minWidth: 115 },
      {
        title: '操作',
        width: 205,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    height: 'auto',
    minHeight: 320,
    rowConfig: { keyField: 'id', isHover: true },
    proxyConfig: { enabled: false },
    pagerConfig: { currentPage: 1, pageSize: 20, total: 0 },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: load },
      custom: true,
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
      page.pageNo = currentPage;
      page.pageSize = pageSize;
      void load();
    },
  },
});

async function load() {
  const current = listGuard.next();
  failed.value = false;
  gridApi.setLoading(true);
  try {
    const result = await getDatabaseRulePage({
      ...page,
      databaseId: props.databaseId,
    });
    if (current.isCurrent())
      gridApi.setGridOptions({
        data: result.list,
        pagerConfig: {
          currentPage: page.pageNo,
          pageSize: page.pageSize,
          total: result.total,
        },
      });
  } catch {
    if (current.isCurrent()) {
      failed.value = true;
      gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
    }
  } finally {
    if (current.isCurrent()) gridApi.setLoading(false);
  }
}

async function openForm(row?: DatabaseMonitorApi.Rule) {
  const current = formGuard.next();
  editingId.value = row?.id;
  formFailed.value = false;
  formModalApi.open();
  formModalApi.lock();
  try {
    await formApi.reset();
    const values = row
      ? await getDatabaseRule(props.databaseId, row.id)
      : {
          name: '',
          description: '',
          metric: 'CONNECTION_STATUS',
          operator: 'EQ',
          threshold: '0',
          level: 'GENERAL',
          status: 0,
          owner: '',
        };
    if (current.isCurrent()) await formApi.setValues(values);
  } catch {
    if (current.isCurrent()) formFailed.value = true;
  } finally {
    if (current.isCurrent()) formModalApi.unlock();
  }
}

async function showDetail(row: DatabaseMonitorApi.Rule) {
  const current = detailGuard.next();
  selected.value = undefined;
  detailFailed.value = false;
  detailLoading.value = true;
  detailApi.open();
  try {
    const result = await getDatabaseRule(props.databaseId, row.id);
    if (current.isCurrent()) selected.value = result;
  } catch {
    if (current.isCurrent()) detailFailed.value = true;
  } finally {
    if (current.isCurrent()) detailLoading.value = false;
  }
}

async function mutate(
  operation: () => Promise<unknown>,
  afterSuccess?: () => unknown,
) {
  if (busy.value) return;
  const current = mutationGuard.next();
  busy.value = true;
  try {
    await operation();
    if (!current.isCurrent()) return;
    await afterSuccess?.();
    message.success('操作成功');
    emit('change');
    await load();
  } finally {
    if (current.isCurrent()) busy.value = false;
  }
}

function setStatus(
  row: DatabaseMonitorApi.Rule,
  checked: boolean | number | string,
) {
  return mutate(() =>
    updateDatabaseRuleStatus({
      databaseId: props.databaseId,
      id: row.id,
      status: checked ? 0 : 1,
    }),
  );
}

function remove(row: DatabaseMonitorApi.Rule) {
  return mutate(() => deleteDatabaseRule(props.databaseId, row.id));
}

function invalidate() {
  listGuard.invalidate();
  formGuard.invalidate();
  detailGuard.invalidate();
  mutationGuard.invalidate();
}

watch(
  () => [props.databaseId, access.accessToken, access.refreshToken],
  () => {
    invalidate();
    busy.value = false;
    page.pageNo = 1;
    formModalApi.close();
    detailApi.close();
    gridApi.setGridOptions({
      data: [],
      pagerConfig: { currentPage: 1, total: 0 },
    });
    void load();
  },
  { flush: 'sync' },
);
onMounted(load);
onBeforeUnmount(invalidate);
</script>

<template>
  <div class="database-rules-panel">
    <Alert
      v-if="failed"
      type="error"
      show-icon
      message="告警规则加载失败，请点击表格右上角刷新重试。"
      class="mb-3"
    />
    <Grid table-title="告警规则列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '新增',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['dataforge:database:update'],
              disabled: busy,
              onClick: () => openForm(),
            },
          ]"
        />
      </template>
      <template #metric="{ row }">
        <Tag color="blue">{{ metricLabel(row.metric) }}</Tag>
      </template>
      <template #level="{ row }">
        <Tag :color="LEVEL_COLORS[row.level as DatabaseMonitorApi.Level]">
          {{ levelLabel(row.level) }}
        </Tag>
      </template>
      <template #status="{ row }">
        <Switch
          :checked="row.status === 0"
          :disabled="!canUpdate || busy"
          :aria-label="`${row.name}启用状态`"
          @change="(value) => setStatus(row, value)"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['dataforge:database:query'],
              onClick: () => showDetail(row),
            },
            {
              label: '修改',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['dataforge:database:update'],
              disabled: busy,
              onClick: () => openForm(row),
            },
            {
              label: '删除',
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:database:update'],
              disabled: busy,
              popConfirm: {
                title: `确认删除规则“${row.name}”吗？`,
                confirm: () => remove(row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <FormModal
      :title="editingId ? '修改告警规则' : '新增告警规则'"
      class="w-[620px]"
    >
      <Alert
        v-if="formFailed"
        type="error"
        show-icon
        message="规则加载失败，请关闭后重试。"
        class="mb-4"
      />
      <Alert
        type="info"
        show-icon
        message="连接状态规则在连接断开时触发；响应时间阈值单位为毫秒。"
        class="mb-4"
      />
      <Form />
    </FormModal>
    <DetailModal title="告警规则详情" class="w-[720px]">
      <div v-if="detailLoading" class="py-10 text-center text-muted-foreground">
        正在加载详情…
      </div>
      <Alert
        v-else-if="detailFailed"
        type="error"
        show-icon
        message="规则加载失败，请关闭后重试。"
      />
      <Descriptions v-else-if="selected" bordered :column="2" size="small">
        <DescriptionsItem label="规则名称" :span="2">
          {{ selected.name }}
        </DescriptionsItem>
        <DescriptionsItem label="描述" :span="2">
          {{ selected.description || '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="告警指标">
          {{ metricLabel(selected.metric) }}
        </DescriptionsItem>
        <DescriptionsItem label="触发条件">
          {{ triggerCondition(selected) }}
        </DescriptionsItem>
        <DescriptionsItem label="告警级别">
          {{ levelLabel(selected.level) }}
        </DescriptionsItem>
        <DescriptionsItem label="状态">
          {{ selected.status === 0 ? '启用' : '停用' }}
        </DescriptionsItem>
        <DescriptionsItem label="负责人">
          {{ selected.owner || '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="创建人">
          {{ selected.creator || '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="创建时间">
          {{ formatDateTime(selected.createTime) }}
        </DescriptionsItem>
        <DescriptionsItem label="更新时间">
          {{ formatDateTime(selected.updateTime) }}
        </DescriptionsItem>
      </Descriptions>
    </DetailModal>
  </div>
</template>
