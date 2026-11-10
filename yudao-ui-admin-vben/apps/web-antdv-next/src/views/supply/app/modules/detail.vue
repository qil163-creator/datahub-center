<script lang="ts" setup>
import type { SupplyAppApi } from '#/api/supply/app';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  DatePicker,
  Descriptions,
  DescriptionsItem,
  Image,
  message,
  RadioGroup,
  Select,
  Switch,
  Table,
  TabPane,
  Tabs,
  Tag,
  TextArea,
  Tooltip,
} from 'antdv-next';

import {
  createAppApi,
  getAppApi,
  getAppApiList,
  getSupplyApp,
  revokeAdminGrant,
  setGrantControl,
  updateAppApi,
} from '#/api/supply/app';
import { getDataApiSimpleList } from '#/api/supply/dataapi';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import ListPagination from '#/components/list-pagination/index.vue';
import { getRangePickerDefaultProps } from '#/utils';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  formatSupplyDate,
  formatSupplyDateTime,
  formatSupplyDateTimeRange,
  toSupplyDateTimeMillis,
} from '../../utils/format';
import { getUserNickname } from '../data';

import '#/components/detail-fill-table.css';

defineOptions({ name: 'SupplyAppDetail' });

const props = defineProps<{
  appId?: SupplyAppApi.Id;
  embedded?: boolean;
}>();
const emit = defineEmits<{ back: [] }>();
const detail = ref<SupplyAppApi.App>({});
const detailLoading = ref(false);
const activeTab = ref('auth');
const authList = ref<SupplyAppApi.AppApi[]>([]);
const authPage = reactive({ current: 1, pageSize: 10 });
const authLoading = ref(false);
const detailError = ref('');
const authError = ref('');
const mutationBusy = ref(false);
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
// 读取只需 query；写接口是 grant OR update，不误套历史核查的 AND 权限。
const canQuery = computed(() => hasAccessByCodes(['supply:app:query']));
const canManage = computed(() =>
  hasAccessByCodes(['supply:app:grant', 'supply:app:update']),
);
const opened = ref(false);
const selectedApp = ref<string>();
const pageGuard = createLatestWins();
const listGuard = createLatestWins();
const mutationGuard = createLatestWins();
let pageTicket: ReturnType<typeof pageGuard.next> | undefined;

function changeAuthPage(current: number, pageSize: number) {
  Object.assign(authPage, { current, pageSize });
}

function sourceAppId() {
  return parseResourceId(
    props.embedded ? props.appId : drawerApi.getData()?.id,
  );
}

/** 固定本次页面身份；同步 watcher 使身份往返切换（ABA）也不能恢复旧请求。 */
function context() {
  const appId = selectedApp.value;
  const ticket = pageTicket;
  if (!opened.value || !appId || !ticket || !canQuery.value) return;
  return {
    appId,
    isCurrent: () =>
      opened.value &&
      canQuery.value &&
      ticket.isCurrent() &&
      selectedApp.value === appId &&
      sourceAppId() === appId,
  };
}

function readyContext() {
  const current = context();
  return current?.isCurrent() &&
    parseResourceId(detail.value.id) === current.appId
    ? current
    : undefined;
}

function resetPage() {
  pageGuard.invalidate();
  pageTicket = undefined;
  listGuard.invalidate();
  mutationGuard.invalidate();
  selectedApp.value = undefined;
  detail.value = {};
  detailLoading.value = false;
  authList.value = [];
  changeAuthPage(1, 10);
  authLoading.value = false;
  detailError.value = '';
  authError.value = '';
  mutationBusy.value = false;
  activeTab.value = 'auth';
  drawerApi.unlock();
  resetAuthForm();
  // close() 内部 await 会延后一拍。这里同步关闭，防止父关闭后子弹窗仍可提交。
  authFormModalApi.setState({ isOpen: false });
}

const headerFields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'name', label: '应用名称', value: row.name },
    { key: 'appType', label: '应用类型', value: row.appType },
    {
      key: 'publicFlag',
      label: '是否公开',
      value: row.publicFlag === 1 ? '公开' : '非公开',
    },
    { key: 'appKey', label: 'AppKey', value: row.appKey, span: 2 },
    {
      key: 'secretHint',
      label: 'AppSecret',
      value: '仅创建或重置时显示一次',
      span: 2,
    },
    {
      key: 'description',
      label: '描述',
      value: row.description,
      span: 2,
      type: 'textarea',
    },
  ];
});

const infoFields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'creator', label: '创建人', value: getUserNickname(row.creator) },
    {
      key: 'createTime',
      label: '创建时间',
      value: formatSupplyDateTime(row.createTime),
    },
    { key: 'updater', label: '更新人', value: getUserNickname(row.updater) },
    {
      key: 'updateTime',
      label: '更新时间',
      value: formatSupplyDateTime(row.updateTime),
    },
    {
      key: 'remark',
      label: '备注',
      value: row.remark,
      span: 2,
      type: 'textarea',
    },
  ];
});

const authColumns = [
  {
    title: 'API编号',
    dataIndex: 'apiId',
    key: 'apiId',
    width: 100,
    sorter: (left: SupplyAppApi.AppApi, right: SupplyAppApi.AppApi) => {
      const a = BigInt(String(left.apiId));
      const b = BigInt(String(right.apiId));
      if (a === b) return 0;
      return a > b ? 1 : -1;
    },
  },
  { title: 'API名称', dataIndex: 'apiName', key: 'apiName', minWidth: 200 },
  {
    title: '描述',
    key: 'description',
    width: 180,
    ellipsis: true,
  },
  {
    title: '路径',
    dataIndex: 'apiPath',
    key: 'apiPath',
    minWidth: 240,
    ellipsis: true,
  },
  { title: '请求方式', key: 'apiMethod', width: 90 },
  { title: '有效期', key: 'validity', width: 210 },
  { title: '授权状态', key: 'authStatus', width: 130 },
  { title: '创建人', key: 'creator', width: 100 },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
    sorter: (left: SupplyAppApi.AppApi, right: SupplyAppApi.AppApi) =>
      formatSupplyDateTime(left.createTime).localeCompare(
        formatSupplyDateTime(right.createTime),
      ),
    customRender: ({ text }: { text: Date | null | number | string }) =>
      formatSupplyDateTime(text) || '-',
  },
  { title: '操作', key: 'actions', width: 200, fixed: 'right' as const },
];

// 保留独立的 Drawer 注入通道，页面模式与内部授权弹窗共用详情逻辑。
const [Drawer, drawerApi] = useVbenDrawer<{ id?: SupplyAppApi.Id }>({
  class: 'w-[1200px] max-w-full',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange: (isOpen) => initializeDetail(isOpen),
});

async function initializeDetail(isOpen: boolean) {
  resetPage();
  opened.value = isOpen;
  if (!isOpen) return;
  const appId = sourceAppId();
  if (!canQuery.value || !appId) {
    detailError.value = '缺少查询权限或应用身份无效，请返回列表后重新打开。';
    return;
  }
  selectedApp.value = appId;
  pageTicket = pageGuard.next();
  const current = context()!;
  detailLoading.value = true;
  if (!props.embedded) drawerApi.lock();
  try {
    const result = await getSupplyApp(appId);
    if (!current.isCurrent()) return;
    if (parseResourceId(result?.id) !== appId) throw new Error('APP_IDENTITY');
    detail.value = result;
    await loadAuthList();
  } catch {
    if (current.isCurrent()) {
      detail.value = {};
      authList.value = [];
      detailError.value =
        '应用详情读取失败或身份不一致，请返回列表后重新打开。';
    }
  } finally {
    if (current.isCurrent()) {
      detailLoading.value = false;
      if (!props.embedded) drawerApi.unlock();
    }
  }
}

function handleBack() {
  opened.value = false;
  resetPage();
  emit('back');
}

async function loadAuthList() {
  const current = readyContext();
  if (!current) return;
  const ticket = listGuard.next();
  authPage.current = 1;
  authList.value = [];
  authError.value = '';
  authLoading.value = true;
  try {
    const result = await getAppApiList(current.appId);
    if (!current.isCurrent() || !ticket.isCurrent()) return;
    const ids = new Set<string>();
    if (!Array.isArray(result)) throw new Error('GRANT_LIST');
    for (const item of result) {
      const id = parseResourceId(item?.id);
      if (!id || ids.has(id) || !belongsToApp(item, current.appId))
        throw new Error('GRANT_IDENTITY');
      ids.add(id);
    }
    authList.value = result;
  } catch {
    if (current.isCurrent() && ticket.isCurrent()) {
      authList.value = [];
      authError.value =
        '授权列表读取失败或身份不一致，当前状态未确认，请刷新。';
    }
  } finally {
    if (current.isCurrent() && ticket.isCurrent()) authLoading.value = false;
  }
}

const apiOptions = ref<{ label: string; value: SupplyDataApiApi.Id }[]>([]);
const authForm = ref<{
  apiId?: SupplyDataApiApi.Id;
  dateRange?: [string, string];
  description?: string;
  foreverFlag: number;
}>({ foreverFlag: 1 });
const editingGrant = ref<SupplyAppApi.AppApi>();
const formOpened = ref(false);
const optionsLoading = ref(false);
const optionsError = ref('');
const submitError = ref('');
const formGuard = createLatestWins();
const optionsGuard = createLatestWins();

function resetAuthForm() {
  formGuard.invalidate();
  optionsGuard.invalidate();
  formOpened.value = false;
  apiOptions.value = [];
  authForm.value = { foreverFlag: 1 };
  editingGrant.value = undefined;
  optionsLoading.value = false;
  optionsError.value = '';
  submitError.value = '';
  authFormModalApi.unlock();
}

const [AuthFormModal, authFormModalApi] = useVbenModal({
  title: '添加API服务授权',
  class: 'w-[640px]',
  async onConfirm() {
    await handleAuthSubmit();
  },
  onOpenChange(isOpen: boolean) {
    const row = editingGrant.value;
    resetAuthForm();
    formOpened.value = isOpen;
    if (!isOpen) return;
    if (row) {
      editingGrant.value = row;
      void loadEditableGrant(row);
    } else {
      void loadApiOptions();
    }
  },
});

function openAuthForm() {
  if (!readyContext() || !canManage.value || mutationBusy.value) return;
  editingGrant.value = undefined;
  authFormModalApi.open();
}

function canEditGrant(row: SupplyAppApi.AppApi) {
  return (
    row.protocolVersion === 0 &&
    row.sourceType === 'ADMIN' &&
    !isClosedAdminGrant(row)
  );
}

function openEditAuthForm(row: SupplyAppApi.AppApi) {
  const current = readyContext();
  if (
    !current ||
    !canManage.value ||
    mutationBusy.value ||
    !canEditGrant(row) ||
    !currentRow(row, current.appId)
  )
    return;
  editingGrant.value = { ...row };
  authFormModalApi.open();
}

async function loadEditableGrant(row: SupplyAppApi.AppApi) {
  const current = readyContext();
  const id = parseResourceId(row.id);
  if (!current || !id || !formOpened.value || !canManage.value) return;
  const ticket = optionsGuard.next();
  const isCurrent = () =>
    current.isCurrent() && ticket.isCurrent() && formOpened.value;
  optionsLoading.value = true;
  optionsError.value = '';
  authForm.value = { foreverFlag: 1 };
  apiOptions.value = [];
  try {
    const result = await getAppApi(id);
    if (!isCurrent()) return;
    if (
      !sameGrant(result, row) ||
      !canEditGrant(result) ||
      parseRevision(result.aggregateRevision) === undefined
    )
      throw new Error('GRANT_IDENTITY');
    const start = formatSupplyDate(result.startDate);
    const end = formatSupplyDate(result.endDate);
    editingGrant.value = { ...result };
    authForm.value = {
      apiId: parseResourceId(result.apiId)!,
      foreverFlag: result.foreverFlag ?? 1,
      dateRange:
        result.foreverFlag === 0 && start && end ? [start, end] : undefined,
      description: result.description,
    };
    apiOptions.value = [
      {
        label: result.apiName ?? String(result.apiId),
        value: parseResourceId(result.apiId)!,
      },
    ];
    authList.value = authList.value.map((item) =>
      sameGrant(item, row) ? result : item,
    );
  } catch {
    if (isCurrent())
      optionsError.value = '授权详情读取失败或已不可修改，请重新加载后再试。';
  } finally {
    if (isCurrent()) optionsLoading.value = false;
  }
}

function reloadAuthOptions() {
  if (mutationBusy.value) return;
  if (editingGrant.value) void loadEditableGrant(editingGrant.value);
  else void loadApiOptions();
}

async function loadApiOptions() {
  const current = readyContext();
  if (!current || !formOpened.value || !canManage.value || mutationBusy.value)
    return;
  const ticket = optionsGuard.next();
  apiOptions.value = [];
  authForm.value.apiId = undefined;
  optionsError.value = '';
  optionsLoading.value = true;
  try {
    const list = await getDataApiSimpleList(1, 'grant');
    if (!current.isCurrent() || !ticket.isCurrent()) return;
    if (!Array.isArray(list)) throw new Error('API_OPTIONS');
    const ids = new Set<string>();
    apiOptions.value = list.map((item) => {
      const id = parseResourceId(item?.id);
      if (!id || ids.has(id)) throw new Error('API_IDENTITY');
      ids.add(id);
      return {
        label: `${item.name ?? id}（${item.version ?? '-'}）`,
        value: id,
      };
    });
  } catch {
    if (current.isCurrent() && ticket.isCurrent()) {
      apiOptions.value = [];
      optionsError.value = 'API 选项读取失败或身份无效，请重新加载后选择。';
    }
  } finally {
    if (current.isCurrent() && ticket.isCurrent()) optionsLoading.value = false;
  }
}

async function handleAuthSubmit() {
  const current = readyContext();
  if (
    !current ||
    !formOpened.value ||
    !canManage.value ||
    mutationBusy.value ||
    optionsLoading.value ||
    optionsError.value ||
    submitError.value
  )
    return;
  const apiId = parseResourceId(authForm.value.apiId);
  if (!apiId || !apiOptions.value.some((option) => option.value === apiId)) {
    message.warning('请选择 API 服务');
    return;
  }
  if (
    authForm.value.foreverFlag === 0 &&
    (!authForm.value.dateRange || authForm.value.dateRange.length !== 2)
  ) {
    message.warning('请选择有效期');
    return;
  }
  const editing = editingGrant.value;
  const expectedRevision = parseRevision(editing?.aggregateRevision);
  if (
    editing &&
    (!canEditGrant(editing) ||
      !currentRow(editing, current.appId) ||
      expectedRevision === undefined)
  )
    return;
  const startDate =
    formatSupplyDate(authForm.value.dateRange?.[0]) || undefined;
  const endDate = formatSupplyDate(authForm.value.dateRange?.[1]) || undefined;
  if (
    authForm.value.foreverFlag === 0 &&
    (!startDate || !endDate || startDate > endDate)
  ) {
    message.warning('请选择有效的起止日期');
    return;
  }
  const ticket = mutationGuard.next();
  const formTicket = formGuard.next();
  const isCurrent = () =>
    current.isCurrent() &&
    ticket.isCurrent() &&
    formTicket.isCurrent() &&
    formOpened.value &&
    canManage.value;
  mutationBusy.value = true;
  submitError.value = '';
  authFormModalApi.lock();
  try {
    await (editing
      ? updateAppApi({
          id: parseResourceId(editing.id)!,
          expectedRevision: expectedRevision!,
          foreverFlag: authForm.value.foreverFlag,
          startDate: authForm.value.foreverFlag === 0 ? startDate : undefined,
          endDate: authForm.value.foreverFlag === 0 ? endDate : undefined,
          description: authForm.value.description,
        })
      : createAppApi({
          appId: current.appId,
          apiId,
          foreverFlag: authForm.value.foreverFlag,
          startDate: authForm.value.foreverFlag === 0 ? startDate : undefined,
          endDate: authForm.value.foreverFlag === 0 ? endDate : undefined,
          validFrom:
            authForm.value.foreverFlag === 0
              ? toSupplyDateTimeMillis(authForm.value.dateRange?.[0])
              : undefined,
          validTo:
            authForm.value.foreverFlag === 0
              ? toSupplyDateTimeMillis(authForm.value.dateRange?.[1])
              : undefined,
          description: authForm.value.description,
        }));
    if (!isCurrent()) return;
    message.success(editing ? '授权修改成功' : '授权成功');
    await authFormModalApi.close();
    if (current.isCurrent() && ticket.isCurrent()) await loadAuthList();
  } catch {
    if (isCurrent()) {
      submitError.value = editing
        ? '授权修改结果未确认，请关闭弹窗后重新加载授权列表核查。'
        : '授权提交结果未确认，本次提交已锁定。请先刷新核查；如决定重试，关闭后重新打开授权弹窗。本页不会自动重发，不保证后端幂等。';
      clearUnconfirmedGrant();
    }
  } finally {
    if (ticket.isCurrent()) mutationBusy.value = false;
    if (isCurrent()) authFormModalApi.unlock();
  }
}

const rangePickerProps = computed(() =>
  editingGrant.value
    ? {
        ...getRangePickerDefaultProps(),
        format: 'YYYY-MM-DD',
        valueFormat: 'YYYY-MM-DD',
        showTime: false as const,
        placeholder: ['开始日期', '结束日期'] as [string, string],
      }
    : getRangePickerDefaultProps(),
);

watch(
  () => authForm.value.foreverFlag,
  (foreverFlag) => {
    if (foreverFlag === 1) authForm.value.dateRange = undefined;
  },
  { flush: 'sync' },
);

function controlActionText(disabled: boolean): string {
  return disabled
    ? '整体禁用（保留来源记录，可再启用）'
    : '重新启用（按来源账本恢复）';
}

/**
 * 整体启用/禁用或永久关闭独立授权。expectedRevision 用最近一次读取到的聚合版本，
 * 旧页面提交会被后端以版本冲突拒绝；成功后按权威状态刷新，不乐观改本地标签。
 */
async function handleControl(
  row: SupplyAppApi.AppApi,
  disabled: 'REVOKE' | boolean,
) {
  const current = readyContext();
  if (
    !current ||
    !canManage.value ||
    mutationBusy.value ||
    isClosedAdminGrant(row) ||
    (disabled === 'REVOKE' && !isIndependentAdminGrant(row)) ||
    !currentRow(row, current.appId)
  )
    return;
  const revision = parseRevision(row.aggregateRevision);
  if (revision === undefined) {
    message.warning('缺少聚合版本，请刷新后重试');
    return;
  }
  const identity = { ...row };
  const ticket = mutationGuard.next();
  const isCurrent = () =>
    current.isCurrent() && ticket.isCurrent() && canManage.value;
  mutationBusy.value = true;
  try {
    try {
      await confirm(
        disabled === 'REVOKE'
          ? `确认永久关闭"${row.apiName ?? row.apiId}"的管理员授权吗？关闭后不能重新启用，恢复访问须新增授权并重新校验权限。`
          : `确认对"${row.apiName ?? row.apiId}"执行${controlActionText(disabled)}吗？`,
      );
    } catch {
      return;
    }
    // 确认框不是身份/版本快照：确认后再次验证页面与本行版本，不借其他行的版本。
    if (
      !isCurrent() ||
      !currentRow(identity, current.appId) ||
      parseRevision(row.aggregateRevision) !== revision
    )
      return;
    const request = {
      expectedRevision: revision,
      id: parseResourceId(identity.id)!,
    };
    await (disabled === 'REVOKE'
      ? revokeAdminGrant(request)
      : setGrantControl({ ...request, disabled }));
    if (!isCurrent()) return;
    const controlSuccess = disabled === true ? '已整体禁用' : '已重新启用';
    message.success(
      disabled === 'REVOKE' ? '已永久关闭，恢复访问须重新授权' : controlSuccess,
    );
    await loadAuthList();
  } catch {
    if (isCurrent()) clearUnconfirmedGrant();
  } finally {
    if (ticket.isCurrent()) mutationBusy.value = false;
  }
}

function clearUnconfirmedGrant() {
  listGuard.invalidate();
  authList.value = [];
  authLoading.value = false;
  authError.value =
    '操作结果未确认，旧授权状态已清除；请刷新核查，不会自动重发。';
}

function parseRevision(value: unknown) {
  return value === 0 || value === '0' ? '0' : parseResourceId(value);
}

function isIndependentAdminGrant(row: SupplyAppApi.AppApi) {
  return (
    row.protocolVersion === 0 &&
    ['ADMIN', 'ADMIN_ETL_BATCH'].includes(row.sourceType ?? '')
  );
}

function isClosedAdminGrant(row: SupplyAppApi.AppApi) {
  return (
    isIndependentAdminGrant(row) &&
    (row.grantStatus === 3 || row.authStatus === 3)
  );
}

function belongsToApp(row: SupplyAppApi.AppApi, appId: string) {
  return (
    !!parseResourceId(row?.id) &&
    parseResourceId(row?.appId) === appId &&
    !!parseResourceId(row?.apiId)
  );
}

function sameGrant(left: SupplyAppApi.AppApi, right: SupplyAppApi.AppApi) {
  return (
    !!parseResourceId(right.id) &&
    ['id', 'appId', 'apiId'].every((key) => {
      const field = key as 'apiId' | 'appId' | 'id';
      const value = parseResourceId(left?.[field]);
      return !!value && value === parseResourceId(right[field]);
    })
  );
}

function currentRow(row: SupplyAppApi.AppApi, appId: string) {
  return (
    belongsToApp(row, appId) &&
    authList.value.some(
      (item) =>
        sameGrant(item, row) &&
        parseRevision(item.aggregateRevision) ===
          parseRevision(row.aggregateRevision) &&
        item.aggregateDisabled === row.aggregateDisabled &&
        item.sourceType === row.sourceType &&
        item.protocolVersion === row.protocolVersion &&
        item.grantStatus === row.grantStatus &&
        item.authStatus === row.authStatus,
    )
  );
}

watch(
  () => [
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    access.accessCodes.join('\0'),
    canQuery.value,
    canManage.value,
  ],
  () => {
    opened.value = false;
    resetPage();
    drawerApi.setState({ isOpen: false });
    if (props.embedded) void initializeDetail(true);
  },
  { flush: 'sync' },
);
watch(
  () => [props.embedded, props.appId],
  () => {
    if (props.embedded) void initializeDetail(true);
  },
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(() => {
  opened.value = false;
  resetPage();
});

function authRecord(record: object) {
  return record as SupplyAppApi.AppApi;
}

function authStatusText(status?: number) {
  if (status === 1) {
    return '有效';
  }
  if (status === 3) {
    return '已撤销';
  }
  return '不可用';
}

function authStatusColor(status?: number) {
  if (status === 1) {
    return 'success';
  }
  if (status === 3) {
    return 'error';
  }
  return 'warning';
}

function grantValidityText(row: SupplyAppApi.AppApi) {
  if (row.foreverFlag === 1) return '永久';
  if (row.sourceType !== 'ADMIN' || (!row.startDate && !row.endDate))
    return (
      formatSupplyDateTimeRange(
        row.validFrom ?? row.startDate,
        row.validTo ?? row.endDate,
      ) || '-'
    );
  return `${formatSupplyDate(row.startDate) || '-'} ~ ${formatSupplyDate(row.endDate) || '-'}`;
}
</script>

<template>
  <component
    :is="embedded ? 'div' : Drawer"
    :title="`应用详情 - ${detail.name ?? ''}`"
    :class="{ 'app-profile': embedded }"
    :aria-busy="detailLoading"
  >
    <section
      :class="embedded ? 'app-profile__summary' : 'mx-4'"
      aria-label="应用概要"
    >
      <div v-if="embedded" class="app-profile__head">
        <div class="app-profile__identity">
          <span v-if="detail.id" class="app-profile__id">{{ detail.id }}</span>
          <h1 class="app-profile__name">{{ detail.name || '应用详情' }}</h1>
          <Tag
            v-if="detail.id"
            :color="detail.publicFlag === 1 ? 'success' : 'default'"
          >
            {{ detail.publicFlag === 1 ? '公开' : '非公开' }}
          </Tag>
        </div>
        <DetailBackButton @click="handleBack" />
      </div>
      <Alert v-if="detailError" type="error" :message="detailError" />
      <p v-if="embedded && detailLoading" role="status">正在加载应用详情…</p>
      <Descriptions
        v-if="embedded && detail.id"
        class="app-profile__info"
        :column="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3, xxl: 3 }"
        bordered
        size="small"
      >
        <DescriptionsItem label="AppKey">
          {{ detail.appKey || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="应用图标">
          <Image
            v-if="detail.icon"
            :src="detail.icon"
            :width="32"
            :height="32"
            :preview="false"
          />
          <IconifyIcon
            v-else
            class="size-8 text-primary"
            icon="lucide:app-window"
          />
        </DescriptionsItem>
        <DescriptionsItem label="应用类型">
          <Tag color="blue">{{ detail.appType || '-' }}</Tag>
        </DescriptionsItem>
        <DescriptionsItem label="描述" span="filled">
          {{ detail.description || '-' }}
        </DescriptionsItem>
      </Descriptions>
      <DetailFieldForm
        v-else-if="!embedded"
        :fields="headerFields"
        label-width="130px"
      />
    </section>
    <section
      :class="[
        embedded ? 'app-profile__attributes' : 'mx-4',
        { 'app-profile__attributes--info': embedded && activeTab === 'info' },
      ]"
      aria-label="应用授权与详细信息"
    >
      <Tabs
        v-model:active-key="activeTab"
        :class="embedded ? 'app-profile__tabs' : 'mt-4'"
      >
        <TabPane key="auth" tab="API授权">
          <div class="app-profile__toolbar">
            <Button
              v-if="canManage && canQuery"
              :disabled="mutationBusy || !detail.id"
              size="small"
              :type="embedded ? 'default' : 'primary'"
              @click="openAuthForm"
            >
              <IconifyIcon
                v-if="embedded"
                class="mr-1"
                icon="lucide:plus"
              />新增
            </Button>
          </div>
          <Alert v-if="authError" type="error" :message="authError">
            <template #action>
              <Button
                :disabled="mutationBusy || !detail.id || !canQuery"
                :loading="authLoading"
                size="small"
                @click="loadAuthList"
              >
                <template #icon>
                  <IconifyIcon icon="lucide:refresh-cw" />
                </template>
                重新加载授权列表
              </Button>
            </template>
          </Alert>
          <Table
            class="supply-font-table"
            :columns="authColumns"
            :data-source="authList"
            :loading="authLoading"
            :pagination="
              embedded
                ? {
                    current: authPage.current,
                    pageSize: authPage.pageSize,
                    placement: ['none'],
                    onChange: changeAuthPage,
                  }
                : false
            "
            :scroll="{ x: 'max-content' }"
            :styles="embedded ? { content: { overflowY: 'auto' } } : undefined"
            :class="{ 'app-profile__table detail-fill-table': embedded }"
            row-key="id"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'apiMethod'">
                <Tag color="geekblue">
                  {{ authRecord(record).apiMethod || '-' }}
                </Tag>
              </template>
              <template v-else-if="column.key === 'description'">
                {{
                  (authRecord(record).description ??
                    authRecord(record).apiDescription) ||
                  '-'
                }}
              </template>
              <template v-else-if="column.key === 'creator'">
                {{ getUserNickname(authRecord(record).creator) || '-' }}
              </template>
              <template v-else-if="column.key === 'validity'">
                {{ grantValidityText(authRecord(record)) }}
              </template>
              <template v-else-if="column.key === 'createTime'">
                {{ formatSupplyDateTime(authRecord(record).createTime) || '-' }}
              </template>
              <template v-else-if="column.key === 'authStatus'">
                <Tooltip
                  v-if="!isClosedAdminGrant(authRecord(record))"
                  title="控制此应用的 API 授权；启用后仍需满足有效期和来源授权条件。"
                >
                  <Switch
                    :checked="!authRecord(record).aggregateDisabled"
                    :disabled="!canManage || mutationBusy || !canQuery"
                    :loading="mutationBusy"
                    :aria-label="
                      authRecord(record).aggregateDisabled
                        ? '重新启用'
                        : '整体禁用'
                    "
                    size="medium"
                    @change="
                      (checked) => handleControl(authRecord(record), !checked)
                    "
                  />
                </Tooltip>
                <Tag
                  v-if="
                    authRecord(record).authStatus !== 1 ||
                    isClosedAdminGrant(authRecord(record))
                  "
                  :color="authStatusColor(authRecord(record).authStatus)"
                  class="ml-2"
                >
                  {{ authStatusText(authRecord(record).authStatus) }}
                </Tag>
                <div
                  v-if="authRecord(record).sourceType === 'ADMIN_ETL_BATCH'"
                  class="text-xs text-gray-500"
                >
                  ETL 批次授权
                </div>
              </template>
              <template v-else-if="column.key === 'actions'">
                <Button
                  v-if="canManage && canEditGrant(authRecord(record))"
                  type="link"
                  size="small"
                  :disabled="mutationBusy"
                  @click="openEditAuthForm(authRecord(record))"
                >
                  <IconifyIcon class="mr-1" icon="lucide:square-pen" />修改
                </Button>
                <Button
                  v-if="
                    canManage &&
                    isIndependentAdminGrant(authRecord(record)) &&
                    !isClosedAdminGrant(authRecord(record))
                  "
                  type="link"
                  size="small"
                  danger
                  :disabled="mutationBusy"
                  @click="handleControl(authRecord(record), 'REVOKE')"
                >
                  <IconifyIcon class="mr-1" icon="lucide:trash-2" />删除
                </Button>
                <span
                  v-if="isClosedAdminGrant(authRecord(record))"
                  class="text-xs text-gray-500"
                >
                  已关闭，恢复访问须重新授权
                </span>
              </template>
            </template>
          </Table>
          <ListPagination
            v-if="embedded"
            :current="authPage.current"
            :page-size="authPage.pageSize"
            :page-size-options="[10, 20, 50, 100]"
            :total="authList.length"
            aria-label="应用授权分页"
            @change="changeAuthPage"
          />
        </TabPane>
        <TabPane key="info" tab="详细信息">
          <Descriptions
            v-if="embedded"
            class="app-profile__info app-profile__details"
            :column="{ xs: 1, sm: 1, md: 2, lg: 2, xl: 2, xxl: 2 }"
            bordered
            size="small"
          >
            <DescriptionsItem
              v-for="field in infoFields"
              :key="field.key"
              :label="field.label"
              :span="field.key === 'remark' ? 'filled' : 1"
            >
              {{ field.value || '-' }}
            </DescriptionsItem>
          </Descriptions>
          <DetailFieldForm v-else :fields="infoFields" label-width="130px" />
        </TabPane>
      </Tabs>
    </section>
  </component>
  <AuthFormModal
    :title="editingGrant ? '修改API服务授权' : '添加API服务授权'"
    :confirm-disabled="
      !canManage ||
      !canQuery ||
      mutationBusy ||
      optionsLoading ||
      !!optionsError ||
      !!submitError ||
      !authForm.apiId
    "
  >
    <Alert v-if="optionsError" type="error" :message="optionsError" />
    <Alert v-if="submitError" type="error" :message="submitError" />
    <Button
      v-if="optionsError"
      :disabled="mutationBusy || !canManage"
      :loading="optionsLoading"
      @click="reloadAuthOptions"
    >
      <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
      <span>重新加载选项</span>
    </Button>
    <div class="governance-modal-form governance-form-item-horizontal">
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          <span>API服务</span>
        </label>
        <Select
          v-model:value="authForm.apiId"
          :options="apiOptions"
          :disabled="
            !!editingGrant || mutationBusy || optionsLoading || !!optionsError
          "
          :loading="optionsLoading"
          option-filter-prop="label"
          allow-clear
          placeholder="请输入API服务名称"
          show-search
        />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          是否永久有效
        </label>
        <RadioGroup
          :disabled="mutationBusy"
          v-model:value="authForm.foreverFlag"
          :options="[
            { label: '是', value: 1 },
            { label: '否', value: 0 },
          ]"
        />
      </div>
      <div v-if="authForm.foreverFlag === 0" class="form-item">
        <label class="form-item__label form-item__label--required">
          <span>有效期</span>
        </label>
        <DatePicker.RangePicker
          :disabled="mutationBusy"
          v-model:value="authForm.dateRange as [string, string] | undefined"
          class="w-full"
          v-bind="rangePickerProps"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">描述</label>
        <TextArea
          :disabled="mutationBusy"
          v-model:value="authForm.description"
          :maxlength="500"
          :rows="3"
          placeholder="请输入描述"
          show-count
        />
      </div>
    </div>
  </AuthFormModal>
</template>

<style lang="scss" scoped>
@import '../../../governance/common/custom-modal-form.css';

.supply-font-table :deep(.ant-table-thead > tr > th),
.supply-font-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.app-profile {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.app-profile__summary,
.app-profile__attributes {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.app-profile__summary {
  flex-shrink: 0;
}

.app-profile__attributes {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 300px;
  padding-top: 8px;
}

.app-profile__attributes:not(.app-profile__attributes--info) {
  padding-bottom: 16px;
}

.app-profile__head,
.app-profile__identity {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.app-profile__head {
  justify-content: space-between;
  margin-bottom: 20px;
}

.app-profile__identity {
  flex-wrap: wrap;
  min-width: 0;
}

.app-profile__id {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border-radius: calc(var(--radius) - 4px);
}

.app-profile__name {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.app-profile__info :deep(.ant-descriptions-item-label) {
  width: 140px;
  color: hsl(var(--muted-foreground));
}

.app-profile__info :deep(.ant-descriptions-item-content) {
  overflow-wrap: anywhere;
}

.app-profile__details :deep(.ant-descriptions-item-content) {
  white-space: pre-wrap;
}

.app-profile__tabs {
  display: flex;
  flex: 1;
  min-height: 0;
}

.app-profile__tabs :deep(.ant-tabs-body-holder) {
  flex: 1;
  min-height: 0;
}

.app-profile__tabs :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

.app-profile__tabs :deep(.ant-tabs-body),
.app-profile__tabs :deep(.ant-tabs-content) {
  height: 100%;
}

.app-profile__tabs :deep(.ant-tabs-content-active) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.app-profile__attributes--info,
.app-profile__attributes--info .app-profile__tabs,
.app-profile__attributes--info :deep(.ant-tabs-body-holder) {
  flex: 1 0 auto;
}

.app-profile__attributes--info :deep(.ant-tabs-body),
.app-profile__attributes--info :deep(.ant-tabs-content) {
  height: auto;
}

.app-profile__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.app-profile__table {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.app-profile__table :deep(.ant-spin),
.app-profile__table :deep(.ant-spin-container),
.app-profile__table :deep(.ant-table) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.app-profile__table :deep(.ant-table-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.app-profile__table :deep(.ant-table-content) {
  flex: 1;
  min-height: 0;
  scrollbar-color: hsl(var(--muted-foreground) / 20%) transparent;
  scrollbar-width: thin;
}

.app-profile__table :deep(.ant-table-content::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.app-profile__table :deep(.ant-table-content::-webkit-scrollbar-track) {
  background: transparent;
}

.app-profile__table :deep(.ant-table-content::-webkit-scrollbar-thumb) {
  background: hsl(var(--muted-foreground) / 20%);
  border-radius: 999px;
}

.app-profile__table :deep(.ant-table) {
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

@media (max-width: 640px) {
  .app-profile__summary,
  .app-profile__attributes {
    padding: 12px;
  }

  .app-profile__attributes:not(.app-profile__attributes--info) {
    padding-bottom: 12px;
  }

  .app-profile__head {
    align-items: flex-start;
  }

  .app-profile__info :deep(.ant-descriptions-item-label) {
    width: 100px;
  }
}
</style>
