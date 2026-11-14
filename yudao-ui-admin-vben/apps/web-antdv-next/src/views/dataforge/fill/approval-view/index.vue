<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import type { SnapshotDiff } from './diff';

import { computed, onMounted, ref, watch } from 'vue';

import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Descriptions,
  DescriptionsItem,
  Spin,
  Table,
  Tag,
} from 'antdv-next';

import { getSubmissionPreview } from '#/api/dataforge/fill';
import { getSimpleUserList } from '#/api/system/user';
import ListPagination from '#/components/list-pagination/index.vue';

import { APPROVAL_STATUS_TEXT } from '../shared';
import { formatRefDisplay } from '../workbench/ref-display';
import { changedCellCount, diffSnapshots } from './diff';

/**
 * 提交快照只读预览（审批人视图）
 *
 * 挂载方式：BPM 流程定义配置 formType=业务表单、
 * formCustomViewPath=dataforge/fill/approval-view/index；
 * BPM 审批详情页动态加载本组件并传入 businessKey（= 提交快照编号）
 */
defineOptions({ name: 'DataforgeFillApprovalView' });

const props = defineProps<{ id: number | string }>();

const loading = ref(true);
const preview = ref<DataforgeFillApi.SubmissionPreview>();
const previous = ref<DataforgeFillApi.SubmissionPreview>();
const submitterName = ref('');
const pageNo = ref(1);
const pageSize = ref(100);

interface PreviewField {
  fieldCode: string;
  name: string;
  /** 人员、部门、附件、图片：单元格保存编号，显示为“名称（编号）” */
  refKind?: string;
  required?: boolean;
}

const fields = computed<PreviewField[]>(() => {
  if (!preview.value?.fieldSchema) return [];
  try {
    const parsed: any[] = JSON.parse(preview.value.fieldSchema) ?? [];
    return parsed
      .filter((field) => !field.hidden)
      .map((field) => ({
        fieldCode: field.fieldCode,
        name: field.name ?? field.fieldCode,
        refKind: field.refKind ?? undefined,
        required: field.required,
      }));
  } catch {
    return [];
  }
});

/** 下级汇总的提交：增加来源组织列 */
const hasSource = computed(() => (preview.value?.rows ?? []).some((row) => !!row.sourceOrgName));

const columns = computed(() => [
  { key: '__no', title: '#', width: 60 },
  ...(hasSource.value ? [{ key: '__source', title: '来源单位', width: 140 }] : []),
  ...fields.value.map((field) => ({
    dataIndex: ['data', field.fieldCode],
    key: field.fieldCode,
    title: field.required ? `${field.name} *` : field.name,
  })),
]);

/** 编号类字段：fieldCode → 编号 → 名称（只含快照引用的编号） */
const refLabels = computed(() => {
  const result = new Map<string, Map<string, string>>();
  for (const field of fields.value) {
    if (!field.refKind) continue;
    const options = preview.value?.refLabels?.[field.fieldCode] ?? [];
    result.set(
      field.fieldCode,
      new Map(options.map((option) => [String(option.value), option.label])),
    );
  }
  return result;
});

/** 单元格显示文本：编号类字段显示“名称（编号）”，其余原样显示 */
function cellText(record: { data: Record<string, unknown> }, fieldCode: string) {
  const value = record.data?.[fieldCode];
  const labels = refLabels.value.get(fieldCode);
  if (labels) return formatRefDisplay(value, labels);
  return value === undefined || value === null ? '' : String(value);
}

const dataSource = computed(() =>
  [...(preview.value?.rows ?? [])]
    .toSorted((a, b) => Number(a.rowOrder) - Number(b.rowOrder))
    .map((row, index) => ({ ...row, __no: index + 1 })),
);
const visibleRows = computed(() =>
  dataSource.value.length > 100
    ? dataSource.value.slice(
        (pageNo.value - 1) * pageSize.value,
        pageNo.value * pageSize.value,
      )
    : dataSource.value,
);
function changePage(page: number, size: number) {
  pageNo.value = page;
  pageSize.value = size;
}

// 与上一次提交对比（整改后重新提交时审批人只需关注变化）
const diff = computed<SnapshotDiff | undefined>(() =>
  previous.value && preview.value
    ? diffSnapshots(
        previous.value.rows,
        preview.value.rows,
        fields.value.map((field) => field.fieldCode),
      )
    : undefined,
);
const changedCount = computed(() =>
  diff.value ? changedCellCount(diff.value) : 0,
);
const warnings = computed(() => preview.value?.warnings ?? []);
function rowClass(record: { rowId: string }) {
  return diff.value?.addedRowIds.has(record.rowId) ? 'qi-fill-row-added' : '';
}
function cellChanged(record: { rowId: string }, fieldCode: string) {
  return diff.value?.changedCells.get(record.rowId)?.has(fieldCode) === true;
}

// 路由参数变化时必须丢弃旧响应，避免审批人看到上一条提交的数据
let loadSequence = 0;
async function load() {
  const current = ++loadSequence;
  loading.value = true;
  preview.value = undefined;
  previous.value = undefined;
  submitterName.value = '';
  try {
    const result = await getSubmissionPreview(props.id);
    if (current !== loadSequence) return;
    preview.value = result;
    void resolveSubmitter(current, result.submitterUserId);
    if (
      result.previousSubmissionId !== undefined &&
      result.previousSubmissionId !== null
    ) {
      try {
        const old = await getSubmissionPreview(result.previousSubmissionId);
        if (current === loadSequence) previous.value = old;
      } catch {
        // 上一次提交不可读时只是缺少对比，不影响审批
      }
    }
  } catch {
    // 请求失败时保留空快照，由统一请求层提示。
  } finally {
    if (current === loadSequence) loading.value = false;
  }
}

async function resolveSubmitter(current: number, userId?: number | string) {
  if (userId === undefined || userId === null) return;
  try {
    const users = (await getSimpleUserList()) as any[];
    const found = users.find((user) => String(user.id) === String(userId));
    if (current === loadSequence) submitterName.value = found?.nickname ?? '';
  } catch {
    // 查不到姓名时仍显示编号
  }
}

onMounted(load);
watch(() => props.id, () => {
  pageNo.value = 1;
  pageSize.value = 100;
  void load();
});
</script>

<template>
  <Spin :spinning="loading">
    <div class="flex flex-col gap-3 p-1">
      <Descriptions v-if="preview" :column="3" bordered size="small">
        <DescriptionsItem label="采集任务">{{
          preview.taskName
        }}</DescriptionsItem>
        <DescriptionsItem label="报送组织">{{
          preview.orgName
        }}</DescriptionsItem>
        <DescriptionsItem label="数据所属期">{{
          preview.periodKey
        }}</DescriptionsItem>
        <DescriptionsItem label="提交次数"
          >第 {{ preview.attemptNo }} 次</DescriptionsItem
        >
        <DescriptionsItem label="提交人">{{
          submitterName || preview.submitterUserId
        }}</DescriptionsItem>
        <DescriptionsItem label="提交时间">
          {{ formatDateTime(preview.submitTime) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="数据行数">
          {{ preview.rowCount }}
          <Tag v-if="preview.submissionType === 2" class="ml-1" color="warning"
            >零上报</Tag
          >
        </DescriptionsItem>
        <DescriptionsItem label="审批状态">
          <Tag :color="APPROVAL_STATUS_TEXT[preview.approvalStatus]?.color">
            {{ APPROVAL_STATUS_TEXT[preview.approvalStatus]?.text }}
          </Tag>
        </DescriptionsItem>
        <DescriptionsItem label="快照哈希">
          <span class="font-mono text-xs"
            >{{ preview.snapshotHash?.slice(0, 16) }}…</span
          >
        </DescriptionsItem>
        <DescriptionsItem
          v-if="preview.emptyReason"
          :span="3"
          label="零上报说明"
        >
          {{ preview.emptyReason }}
        </DescriptionsItem>
      </Descriptions>

      <Alert
        v-if="diff"
        type="info"
        show-icon
        :message="`较上一次提交：新增 ${diff.addedRowIds.size} 行，删除 ${diff.removedRows.length} 行，修改 ${changedCount} 个单元格`"
        description="新增行以绿色底色标出，被修改的单元格以黄色底色标出。"
      />
      <Alert
        v-if="warnings.length > 0"
        type="warning"
        show-icon
        :message="`提交时存在 ${warnings.length} 条警告（填报人已确认可提交）`"
      >
        <template #description>
          <ul class="m-0 max-h-40 list-disc overflow-auto pl-4">
            <li v-for="(warning, index) in warnings.slice(0, 50)" :key="index">
              {{ warning.message }}
            </li>
            <li v-if="warnings.length > 50">
              其余 {{ warnings.length - 50 }} 条省略
            </li>
          </ul>
        </template>
      </Alert>

      <div
        v-if="preview?.submissionType === 2"
        class="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:bg-amber-950"
      >
        本次为零上报：该组织申报本期无数据。审批通过即视为按期完成报送
        {{ preview.emptyReason ? `（说明：${preview.emptyReason}）` : '' }}。
      </div>

      <Table
        :columns="columns"
        :data-source="visibleRows"
        :pagination="false"
        :scroll="{ x: 'max-content', y: 480 }"
        bordered
        row-key="rowId"
        size="small"
        :row-class-name="(record: any) => rowClass(record)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === '__no'">{{ record.__no }}</template>
          <template v-else-if="column.key === '__source'">
            {{ record.sourceOrgName ?? '本单位' }}
          </template>
          <div
            v-else-if="cellChanged(record, String(column.key))"
            class="qi-fill-cell-changed"
          >
            {{ cellText(record, String(column.key)) }}
          </div>
          <template v-else-if="refLabels.has(String(column.key))">
            {{ cellText(record, String(column.key)) }}
          </template>
        </template>
      </Table>
      <ListPagination
        v-if="dataSource.length > 100"
        :current="pageNo"
        :page-size="pageSize"
        :page-size-options="[10, 20, 50, 100]"
        :total="dataSource.length"
        aria-label="提交快照分页"
        @change="changePage"
      />

      <p
        v-if="diff && diff.removedRows.length > 0"
        class="text-muted-foreground text-xs"
      >
        上一次提交中的 {{ diff.removedRows.length }} 行在本次提交中已删除。
      </p>

      <p class="text-muted-foreground text-xs">
        以上为不可变提交快照内容，与审批结论一一对应；审批人不能修改表格内容。
      </p>
    </div>
  </Spin>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

:deep(.qi-fill-row-added > td) {
  background: #f0f9eb !important;
}

.qi-fill-cell-changed {
  padding: 4px 8px;
  margin: -4px -8px;
  background: #fdf6ec;
}
</style>
