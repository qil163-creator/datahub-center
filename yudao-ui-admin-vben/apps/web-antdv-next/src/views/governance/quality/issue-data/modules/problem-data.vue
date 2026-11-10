<script setup lang="ts">
import type { QualityIssueDataApi } from '#/api/governance/quality/issue';
import type { QualityReportApi } from '#/api/governance/quality/report';

import { computed, h, onDeactivated, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Table,
  TextArea,
  Tooltip,
} from 'antdv-next';

import { TableAction } from '#/adapter/vxe-table';
import { actOnIssueRows, getIssueRows } from '#/api/governance/quality/issue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import { clearReactiveObject } from '../../../common/form-layout';
import RepairForm from '../../repair-order/modules/form.vue';

const access = useAccessStore();
const emptyImage = '/images/quality-reference/empty.png';
const warningLevels: Record<string, string> = {
  LOW: '告警',
  MID: '一般',
  CRITICAL: '严重',
};
const { hasAccessByCodes } = useAccess();
const visible = ref(false);
const rule = ref<QualityReportApi.Rule>();
const loading = ref(false);
const failed = ref(false);
const rows = ref<QualityIssueDataApi.SourceRow[]>([]);
const displayRows = computed(() =>
  rows.value.map((row, index) => ({
    ...row,
    displayKey: row.processable ? row.rowKey : `${row.rowKey}:${index}`,
  })),
);
const fields = ref<string[]>([]);
const selectedKeys = ref<string[]>([]);
const filters = reactive<Record<string, string>>({});
const page = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const truncated = ref(false);
const processing = ref(false);
const remarkRow = ref<QualityIssueDataApi.SourceRow>();
const remark = ref('');
const repairRef = ref<InstanceType<typeof RepairForm>>();
const canUpdate = computed(() =>
  hasAccessByCodes(['governance:quality-issue-data:update']),
);
const request = useObjectRequest(() => `${visible.value}/${rule.value?.id}`);
const mutations = useObjectRequest(() => `${visible.value}/${rule.value?.id}`);

function close() {
  repairRef.value?.close();
  visible.value = false;
  request.begin();
  mutations.begin();
  rule.value = undefined;
  rows.value = [];
  fields.value = [];
  selectedKeys.value = [];
  remarkRow.value = undefined;
  remark.value = '';
  loading.value = false;
  processing.value = false;
  truncated.value = false;
  failed.value = false;
}

async function open(value: QualityReportApi.Rule) {
  close();
  clearReactiveObject(filters);
  page.pageNo = 1;
  page.total = 0;
  rule.value = value;
  visible.value = true;
  await load();
}

async function load(reset = false) {
  if (!visible.value || !rule.value) return;
  if (reset) page.pageNo = 1;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  selectedKeys.value = [];
  try {
    const data = await getIssueRows({
      auditLogId: rule.value.id,
      pageNo: page.pageNo,
      pageSize: page.pageSize,
      filters: Object.fromEntries(
        Object.entries(filters).filter(([, value]) => value !== ''),
      ),
    });
    if (!current()) return;
    fields.value = data.columns;
    rows.value = data.list;
    page.total = data.total;
    truncated.value = data.truncated;
  } catch {
    if (current()) {
      rows.value = [];
      page.total = 0;
      failed.value = true;
    }
  } finally {
    if (current()) loading.value = false;
  }
}
function reset() {
  clearReactiveObject(filters);
  void load(true);
}
function selectable(row: QualityIssueDataApi.SourceRow) {
  return row.processable && row.state === 'UNPROCESSED';
}
async function mutate(
  action: 'IGNORE' | 'REMARK',
  selected: QualityIssueDataApi.SourceRow[],
) {
  if (
    !canUpdate.value ||
    !rule.value ||
    processing.value ||
    selected.length === 0
  )
    return;
  const current = mutations.begin();
  processing.value = true;
  try {
    await actOnIssueRows({
      auditLogId: rule.value.id,
      action,
      rows: selected.map((row) => ({
        rowKey: row.rowKey,
        revision: row.revision,
      })),
      ...(action === 'REMARK' ? { remark: remark.value } : {}),
    });
    if (!current()) return;
    remarkRow.value = undefined;
    message.success(action === 'IGNORE' ? '已忽略问题' : '备注已保存');
    await load();
  } finally {
    if (current()) processing.value = false;
  }
}
function ignore(selected: QualityIssueDataApi.SourceRow[]) {
  if (selected.length === 0) return;
  const current = mutations.begin();
  Modal.confirm({
    title: `确认忽略选中的 ${selected.length} 条问题数据？`,
    content: '忽略后仍保留问题记录和处置状态。',
    okText: '确定',
    cancelText: '取消',
    onOk: () => current() && mutate('IGNORE', selected),
  });
}
function openRemark(row: QualityIssueDataApi.SourceRow) {
  remarkRow.value = row;
  remark.value = row.remark ?? '';
}
function repair() {
  if (!rule.value) return;
  repairRef.value?.open({
    orderTitle: `修复 - ${rule.value.ruleName}`,
    objectId: rule.value.checkObjectId,
    tableName: rule.value.tableName,
    checkField: rule.value.checkField,
    targetField: rule.value.checkField,
  });
}
const selection = computed(() => ({
  selectedRowKeys: selectedKeys.value,
  onChange: (keys: (number | string)[]) => {
    selectedKeys.value = keys.map(String);
  },
  getCheckboxProps: (row: QualityIssueDataApi.SourceRow) => ({
    disabled: !canUpdate.value || !selectable(row) || processing.value,
  }),
}));
const columns = computed(() => [
  { title: '问题数据', key: 'values', width: 300 },
  { title: '评测字段', key: 'fields', width: 300 },
  { title: '严重程度', key: 'severity', width: 100 },
  { title: '是否已修复', key: 'state', width: 130 },
  { title: '错误示例', key: 'errorExample', width: 240 },
  { title: '修复建议', key: 'repairAdvice', width: 240 },
  { title: '检查时间', key: 'checkTime', width: 180 },
  {
    title: '备注',
    dataIndex: 'remark',
    key: 'remark',
    width: 300,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '操作', key: 'actions', width: 240, fixed: 'right' as const },
]);
watch(() => [access.accessToken, access.refreshToken], close);
onDeactivated(close);
defineExpose({ open, close });
</script>

<template>
  <Modal
    :open="visible"
    :width="1200"
    title="问题数据处理"
    class="quality-problem-modal"
    :mask-closable="false"
    @cancel="close"
  >
    <form class="quality-problem-filter" @submit.prevent="load(true)">
      <label v-for="field in fields" :key="field"
        ><span>{{ field }}</span
        ><Input
          v-model:value="filters[field]"
          :placeholder="`请输入 ${field}`"
          allow-clear
      /></label>
      <div class="quality-problem-filter-buttons">
        <Button
          class="quality-report-query"
          html-type="submit"
          :disabled="loading"
        >
          查询 </Button
        ><Button @click="reset">重置</Button>
      </div>
    </form>
    <div class="quality-problem-bulk">
      <Button
        type="link"
        danger
        :loading="processing"
        :disabled="!canUpdate || selectedKeys.length === 0"
        @click="
          ignore(
            rows.filter(
              (row) => selectedKeys.includes(row.rowKey) && selectable(row),
            ),
          )
        "
      >
        − 批量忽略问题
      </Button>
      <Tooltip
        v-for="action in [
          '批量重新修复',
          '批量撤销已忽略',
          '批量撤销暂缓修复',
          '批量退回问题处理',
        ]"
        :key="action"
        title="该操作暂未开放"
      >
        <span
          ><Button type="link" disabled>⟳ {{ action }}</Button></span
        >
      </Tooltip>
    </div>
    <Alert
      v-if="truncated"
      type="info"
      message="已达到1000条回源上限，仅展示本次读取范围；为避免误定位，当前结果仅可查看。"
    />
    <Table
      :columns="columns"
      :data-source="displayRows"
      :loading="loading"
      :row-selection="selection"
      :row-key="(row: { displayKey: string }) => row.displayKey"
      :scroll="{ x: 2080, y: '51vh' }"
      :pagination="
        page.total > 0
          ? {
              current: page.pageNo,
              pageSize: page.pageSize,
              total: page.total,
              showSizeChanger: true,
              showTotal: (total: number) => `共 ${total} 条`,
              onChange: (current: number, size: number) => {
                page.pageNo = current;
                page.pageSize = size;
                void load();
              },
            }
          : false
      "
      class="quality-report-table quality-problem-table"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'values'">
          <div class="quality-problem-values">
            <p v-for="field in fields" :key="field">
              {{ field }}：{{ record.values[field] ?? '-' }}
            </p>
          </div>
          <span v-if="!record.processable" class="quality-problem-ambiguous"
            >无法唯一定位，暂不可处置</span
          >
        </template>
        <template v-else-if="column.key === 'fields'">
          {{ rule?.fieldLabels?.join('，') || rule?.checkField || '-' }}
        </template>
        <template v-else-if="column.key === 'severity'">
          {{
            warningLevels[rule?.warningLevel ?? ''] || rule?.warningLevel || '-'
          }}
        </template>
        <template v-else-if="column.key === 'errorExample'">
          {{ rule?.errorExample || '-' }}
        </template>
        <template v-else-if="column.key === 'repairAdvice'">
          {{ rule?.repairAdvice || '-' }}
        </template>
        <template v-else-if="column.key === 'checkTime'">
          {{ rule?.checkTime == null ? '-' : formatDateTime(rule.checkTime) }}
        </template>
        <template v-else-if="column.key === 'state'">
          <span
            :class="record.state === 'IGNORED' ? 'quality-problem-ignored' : ''"
            >{{ record.state === 'IGNORED' ? '已忽略' : '未修复' }}</span
          >
        </template>
        <template v-else-if="column.key === 'actions'">
          <TableAction
            :actions="[
              {
                label: '忽略',
                icon: 'lucide:circle-minus',
                type: 'link',
                auth: ['governance:quality-issue-data:update'],
                disabled: !selectable(record) || processing,
                ifShow: record.state !== 'IGNORED',
                onClick: () => ignore([record]),
              },
              {
                label: '添加备注',
                icon: 'lucide:message-square-plus',
                type: 'link',
                auth: ['governance:quality-issue-data:update'],
                disabled: !record.processable || processing,
                onClick: () => openRemark(record),
              },
              {
                label: '处理',
                icon: 'lucide:wrench',
                type: 'link',
                auth: ['governance:quality-repair-order:create'],
                ifShow: record.state === 'UNPROCESSED',
                disabled: !record.processable,
                onClick: repair,
              },
            ]"
          />
        </template>
      </template>
      <template #emptyText>
        <div class="quality-report-empty">
          <img :src="emptyImage" alt="" aria-hidden="true" /><span
            >暂无记录</span
          >
        </div>
      </template>
    </Table>
    <template #footer><Button @click="close">关闭</Button></template>
  </Modal>
  <Modal
    :open="!!remarkRow"
    :width="800"
    title="添加备注"
    :confirm-loading="processing"
    ok-text="保存"
    @cancel="remarkRow = undefined"
    @ok="remarkRow && mutate('REMARK', [remarkRow])"
  >
    <label class="quality-problem-remark"
      ><span>备注</span
      ><TextArea
        v-model:value="remark"
        :maxlength="500"
        :rows="4"
        show-count
        placeholder="请输入备注"
    /></label>
  </Modal>
  <RepairForm ref="repairRef" />
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
