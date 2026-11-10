<script lang="ts" setup>
import type { MarketCheckApi } from '#/api/asset/market/check';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Button, Input, message, TextArea } from 'antdv-next';

import { NativeVxeButton, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { useVbenForm } from '#/adapter/form';
import {
  checkMarketCheck,
  getMarketCheckPage,
} from '#/api/asset/market/check';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  canReviewMarketCheck,
  CHECK_STATUS_OPTIONS,
  marketCheckPeriod,
  marketCheckReviewError,
  marketCheckRowKey,
  marketCheckStatusColor,
  marketCheckStatusLabel,
  marketCheckTime,
} from './data';

defineOptions({ name: 'MarketCheck' });
type Row = MarketCheckApi.Ticket & { rowKey: string };
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const canCheck = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!user.userInfo?.userId &&
    hasAccessByCodes(['asset:market:check']),
);
const owner = computed(() =>
  JSON.stringify([
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const searchVisible = ref(true);
const martName = ref('');
const status = ref<string>();
const [FilterForm, filterFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 110 },
  layout: 'horizontal',
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  showCollapseButton: true,
  submitOnEnter: true,
  resetButtonOptions: { show: false },
  submitButtonOptions: { show: false },
  schema: [
    {
      fieldName: 'martName',
      label: '集市资产名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入集市资产名称' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: CHECK_STATUS_OPTIONS.map((item) => ({ ...item })),
        placeholder: '请选择状态',
      },
    },
  ],
  handleValuesChange(values) {
    martName.value = values.martName ?? '';
    status.value = values.status;
  },
  handleSubmit: () => load(true),
  handleReset: resetSearch,
});
const listError = ref(false);
const pageNo = ref(1);
const pageSize = ref(10);
const sortBy = ref<'applyTime' | 'displayId'>('applyTime');
const sortOrder = ref<'asc' | 'desc'>('desc');
const review = ref<MarketCheckApi.Ticket>();
const reviewStatus = ref<'3' | '4'>('3');
const approvalReason = ref('');
const reviewError = ref('');
const reviewSubmitting = ref(false);
let active = true;
let mounted = false;
let reviewSubmission = 0;
const requestGuard = useObjectRequest(
  () => owner.value,
  () => {
    gridApi.setGridOptions({
      data: [],
      loading: false,
      pagerConfig: { total: 0 },
    });
  },
);
const reviewGuard = useObjectRequest(
  () => owner.value,
  () => {
    reviewSubmission += 1;
    review.value = undefined;
    approvalReason.value = '';
    reviewError.value = '';
    reviewSubmitting.value = false;
    void reviewModalApi.close();
  },
);
const [Grid, gridApi] = useVbenVxeGrid<Row>({
  gridOptions: {
    columns: [
      { field: 'displayId', title: '编号', width: 74, sortable: true },
      {
        field: 'martName',
        title: '集市资产名称',
        minWidth: 190,
        align: 'left',
        slots: { default: 'asset' },
      },
      {
        field: 'applicantName',
        title: '申请人',
        minWidth: 190,
        showOverflow: false,
        className: 'check-applicant-cell',
      },
      {
        field: 'applyType',
        title: '申请周期',
        minWidth: 210,
        slots: { default: 'period' },
      },
      {
        field: 'applyTime',
        title: '申请时间',
        width: 164,
        sortable: true,
        formatter: ({ cellValue }: { cellValue?: MarketCheckApi.Time }) =>
          marketCheckTime(cellValue),
      },
      { field: 'applyReason', title: '申请理由', minWidth: 190, align: 'left' },
      {
        field: 'approvalReason',
        title: '审批意见',
        minWidth: 190,
        align: 'left',
      },
      {
        field: 'status',
        title: '状态',
        width: 110,
        slots: { default: 'status' },
      },
      {
        field: 'actions',
        title: '操作',
        width: 212,
        fixed: 'right',
        showOverflow: false,
        className: 'check-actions-cell',
        slots: { default: 'actions' },
      },
    ],
    data: [],
    height: 'auto',
    showOverflow: false,
    showHeaderOverflow: true,
    rowConfig: { keyField: 'rowKey', isHover: true },
    sortConfig: { remote: true, multiple: false },
    pagerConfig: {
      currentPage: 1,
      pageSize: 10,
      pageSizes: [10, 20, 50, 100],
      total: 0,
    },
    proxyConfig: { enabled: false, autoLoad: false },
    toolbarConfig: {
      custom: true,
      refresh: true,
      refreshOptions: { queryMethod: () => load() },
      zoom: false,
      search: false,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize: size,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      pageNo.value = currentPage;
      pageSize.value = size;
      void load();
    },
    sortChange: ({
      field,
      order,
    }: {
      field: string;
      order: 'asc' | 'desc' | null;
    }) => {
      sortBy.value = field === 'applyTime' ? 'applyTime' : 'displayId';
      sortOrder.value = order ?? 'desc';
      void load(true);
    },
  },
});
const [ReviewModal, reviewModalApi] = useVbenModal({
  class: 'market-check-review w-[800px] max-w-[calc(100vw-32px)]',
  headerClass: 'check-review-header',
  contentClass: 'check-review-content',
  footerClass: 'check-review-footer',
  fullscreenButton: false,
  draggable: true,
  onConfirm: submitReview,
  onOpenChange(open) {
    if (!open) {
      reviewSubmission += 1;
      reviewSubmitting.value = false;
      reviewModalApi.unlock();
      reviewGuard.begin();
      review.value = undefined;
      approvalReason.value = '';
      reviewError.value = '';
    }
  },
});
async function load(reset = false) {
  if (!active || !canCheck.value) return;
  if (reset) pageNo.value = 1;
  const current = requestGuard.begin();
  listError.value = false;
  gridApi.setGridOptions({
    loading: true,
    pagerConfig: { currentPage: pageNo.value, pageSize: pageSize.value },
  });
  try {
    const result = await getMarketCheckPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      martName: martName.value.trim() || undefined,
      status: status.value,
      sortBy: sortBy.value,
      sortOrder: sortOrder.value,
    });
    if (current() && active)
      gridApi.setGridOptions({
        data: (result.list ?? []).map((item) => ({
          ...item,
          rowKey: marketCheckRowKey(item),
        })),
        pagerConfig: { total: result.total ?? 0 },
      });
  } catch {
    if (current() && active) {
      listError.value = true;
      gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
    }
  } finally {
    if (current() && active) gridApi.setGridOptions({ loading: false });
  }
}
function resetSearch() {
  if (filterFormApi.isMounted) void filterFormApi.reset();
  martName.value = '';
  status.value = undefined;
  void load(true);
}
function viewDetail(row: MarketCheckApi.Ticket) {
  if (row.canView)
    void router.push({
      name: 'MarketCheckDetail',
      params: { id: String(row.id) },
      query: { origin: row.origin },
    });
}
function openReview(row: MarketCheckApi.Ticket, decision: '3' | '4') {
  if (!canCheck.value || !canReviewMarketCheck(row, decision)) return;
  reviewGuard.begin();
  reviewSubmission += 1;
  reviewModalApi.unlock();
  review.value = { ...row };
  reviewStatus.value = decision;
  approvalReason.value = '';
  reviewError.value = '';
  reviewSubmitting.value = false;
  reviewModalApi
    .setState({
      title:
        decision === '3' ? '数据集市资产申请 -同意' : '数据集市资产审批- 驳回',
    })
    .open();
}
async function submitReview() {
  const ticket = review.value;
  const reason = approvalReason.value.trim();
  if (
    !ticket ||
    reviewSubmitting.value ||
    !canCheck.value ||
    !canReviewMarketCheck(ticket, reviewStatus.value)
  )
    return;
  reviewError.value = marketCheckReviewError(
    approvalReason.value,
    reviewStatus.value,
  );
  if (reviewError.value) return;
  const current = reviewGuard.begin();
  const submission = ++reviewSubmission;
  reviewSubmitting.value = true;
  reviewModalApi.lock();
  reviewError.value = '';
  try {
    await checkMarketCheck({
      id: ticket.id,
      origin: ticket.origin,
      status: reviewStatus.value,
      approvalReason: reason,
      expectedRevision: ticket.revision,
    });
    if (!current() || !active) return;
    message.success('审批意见已保存');
    await reviewModalApi.close();
    void load();
  } finally {
    if (submission === reviewSubmission) {
      reviewSubmitting.value = false;
      reviewModalApi.unlock();
    }
  }
}
onMounted(() => {
  mounted = true;
  void load();
});
onActivated(() => {
  if (!active && mounted) {
    active = true;
    void load();
  }
});
function deactivate() {
  active = false;
  requestGuard.begin();
  reviewGuard.begin();
  void reviewModalApi.close();
}
onDeactivated(deactivate);
onBeforeUnmount(deactivate);

function syncFilterValues() {
  if (filterFormApi.isMounted)
    void filterFormApi.setValues({
      martName: martName.value,
      status: status.value,
    });
}
</script>

<template>
  <Page auto-content-height content-class="market-check-page">
    <FilterForm
      @vue:mounted="syncFilterValues"
      v-show="searchVisible"
      class="rounded-lg bg-background p-4"
    >
      <template #reset-before>
        <Button @click="resetSearch">
          重置
        </Button>
        <Button type="primary" @click="filterFormApi.validateAndSubmit()">
          <template #icon><IconifyIcon icon="lucide:search" /></template>查询
        </Button>
      </template>
    </FilterForm>
    <section class="check-grid-shell">
<Grid :style="{ '--vxe-ui-font-size-small': '14px' }" class="check-grid" table-title="审批工单列表">
        <template #toolbar-tools>
          <NativeVxeButton
            aria-label="显示或隐藏搜索条件"
            :aria-pressed="searchVisible"
            title="搜索"
            icon="vxe-icon-search"
            circle
            :status="searchVisible ? 'primary' : undefined"
            @click="searchVisible = !searchVisible"
          />
        </template>
        <template #asset="{ row }">
          <button
            type="button"
            class="check-link"
            :disabled="!row.canView"
            @click="viewDetail(row)"
          >
            {{ row.martName || '-' }}
          </button>
        </template>
        <template #period="{ row }">
          <span
            class="check-period"
            :class="{ 'check-period--range': row.applyType !== 1 }"
            >{{ marketCheckPeriod(row) }}</span>
        </template>
        <template #status="{ row }">
          <span
            class="check-status"
            :class="`check-status--${row.statusTone || marketCheckStatusColor(row.status)}`"
            >{{ marketCheckStatusLabel(row) }}</span>
        </template>
        <template #actions="{ row }">
          <div class="check-row-actions">
            <Button
              type="link"
              size="small"
              :disabled="!canCheck || !canReviewMarketCheck(row, '4')"
              @click="openReview(row, '4')"
            >
              <template #icon><IconifyIcon icon="lucide:x" /></template>驳回
</Button><Button
              type="link"
              size="small"
              :disabled="!canCheck || !canReviewMarketCheck(row, '3')"
              @click="openReview(row, '3')"
            >
              <template #icon><IconifyIcon icon="lucide:check" /></template>同意
</Button><Button
              type="link"
              size="small"
              :disabled="!row.canView"
              @click="viewDetail(row)"
            >
              <template #icon><IconifyIcon icon="lucide:eye" /></template>详情
            </Button>
          </div>
        </template>
      </Grid>
    </section>
    <ReviewModal>
      <div class="check-review-form">
        <div class="check-review-form__row">
          <label>集市资产名称</label><Input :value="review?.martName || '-'" disabled />
        </div>
        <div class="check-review-form__row">
          <label>申请周期</label><span v-if="review" class="check-period">{{
            marketCheckPeriod(review)
          }}</span>
        </div>
        <div class="check-review-form__row check-review-form__row--required">
          <label for="check-review-reason">{{
            reviewStatus === '4' ? '驳回理由' : '审批意见'
          }}</label>
          <div class="check-review-form__control">
            <TextArea
              id="check-review-reason"
              v-model:value="approvalReason"
              :rows="4"
              :maxlength="256"
              :status="reviewError ? 'error' : undefined"
              :placeholder="
                reviewStatus === '4'
                  ? review?.origin === 'QDATA'
                    ? '请填写驳回理由，工单将保留驳回原因'
                    : '请填写驳回理由, 驳回后申请人不会获得该资产权限，工单将保留驳回原因'
                  : review?.origin === 'QDATA'
                    ? '请填写审批意见，通过后仍需单独办理资产使用授权'
                    : '请填写审批意见,通过后申请人将获得该资产使用权限，资产会出现在“我的资产”中'
              "
              :aria-invalid="!!reviewError"
              @input="reviewError = ''"
            />
            <p class="check-review-form__count">
              {{ approvalReason.length }} / 256个字符
            </p>
            <p v-if="reviewError" class="check-review-form__error">
              {{ reviewError }}
            </p>
          </div>
        </div>
      </div>
    </ReviewModal>
  </Page>
</template>

<style scoped>
:deep(.market-check-page) {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  min-height: 0;
  padding: 14px;
  overflow: hidden;
  font-size: 14px;
  color: hsl(var(--foreground));
  background: hsl(var(--muted));
}

.check-grid-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 0 14px 14px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.check-list-error {
  margin-bottom: 12px;
}

.check-grid {
  flex: 1;
  min-height: 0;
}

.check-grid :deep(.vxe-body--column .vxe-cell) {
  overflow-wrap: anywhere;
  white-space: normal;
}

.check-grid :deep(.check-actions-cell .vxe-cell) {
  white-space: nowrap;
}

.check-period {
  display: inline-block;
  max-width: 100%;
  padding: 1px 8px;
  font-size: 14px;
  line-height: 22px;
  color: hsl(var(--primary));
  overflow-wrap: anywhere;
  white-space: normal;
  background: hsl(var(--primary) / 10%);
  border-radius: 4px;
}

.check-period--range {
  padding-right: 0;
  padding-left: 0;
  background: transparent;
}

.check-status {
  display: inline-block;
  padding: 0 8px;
  line-height: 22px;
  border-radius: 4px;
}

.check-status--success {
  color: #6fae50;
  background: #edf6e9;
}

.check-status--error {
  color: #e87777;
  background: #fff0f0;
}

.check-status--warning {
  color: #d1a448;
  background: #fff8e8;
}

.check-status--default {
  padding: 0;
  color: #888;
}

.check-link {
  max-width: 100%;
  padding: 0;
  color: hsl(var(--primary));
  overflow-wrap: anywhere;
  white-space: normal;
  cursor: pointer;
  background: none;
  border: 0;
}

.check-row-actions {
  display: flex;
  gap: 2px;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
}

.check-review-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 0;
  font-size: 14px;
  color: hsl(var(--foreground));
}

.check-review-form__row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  min-height: 30px;
  line-height: 30px;
}

.check-review-form__row > label {
  flex: 0 0 118px;
  color: hsl(var(--foreground));
  text-align: right;
}

.check-review-form__row--required > label::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}

.check-review-form__control {
  flex: 1;
  min-width: 0;
}

.check-review-form__control :deep(textarea) {
  height: 98px;
  min-height: 98px;
  border-radius: var(--radius);
}

.check-review-form__count {
  margin: 0;
  line-height: 20px;
  color: hsl(var(--muted-foreground));
  text-align: right;
}

.check-review-form__error {
  margin: 4px 0 0;
  line-height: 18px;
  color: hsl(var(--destructive));
}

@media (width <= 767px) {
  .check-review-form {
    padding: 14px 8px;
  }

  .check-review-form__row > label {
    flex-basis: 90px;
  }
}
</style>
