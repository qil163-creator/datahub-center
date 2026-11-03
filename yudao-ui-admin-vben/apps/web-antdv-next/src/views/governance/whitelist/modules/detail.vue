<script lang="ts" setup>
import type { SafetyWhitelistApi } from '#/api/governance/whitelist';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictLabel } from '@vben/hooks';
import { formatDateTime } from '@vben/utils';

import {
  getWhitelist,
  getWhitelistSubjectListByWhitelistId,
} from '#/api/governance/whitelist';
import { getSimpleDeptList } from '#/api/system/dept';
import { getSimpleRoleList } from '#/api/system/role';
import { useObjectRequest } from '#/hooks/use-object-request';
import {
  auditDetailFields,
  formatEnableStatus,
} from '#/views/governance/common/detail-helpers';
import { getUserNickname } from '#/views/governance/common/user';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  APPROVAL_STATUS_OPTIONS,
  approvalOptionLabel,
  formatWhitelistObjectName,
  loadObjectNameCaches,
} from '../data';

defineOptions({ name: 'SafetyWhitelistDetail' });

const detail = ref<SafetyWhitelistApi.Whitelist>();
const subjectNames = ref('-');
const detailRequest = useObjectRequest(
  () => '',
  () => {
    detail.value = undefined;
    subjectNames.value = '-';
    drawerApi.setState({ loading: false });
  },
);

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'name', label: '白名单名称', value: row?.name },
    {
      key: 'objectName',
      label: '数据分类',
      value: row ? formatWhitelistObjectName(row) : '-',
    },
    {
      key: 'subjectType',
      label: '生效分类',
      value: getDictLabel(DICT_TYPE.SUBJECT_TYPE, row?.subjectType) || '-',
    },
    { key: 'subjects', label: '生效主体', value: subjectNames.value },
    {
      key: 'effectiveTime',
      label: '生效开始',
      value: formatDateTime(row?.effectiveTime),
    },
    {
      key: 'expireTime',
      label: '生效结束',
      value: formatDateTime(row?.expireTime),
    },
    {
      key: 'status',
      label: '状态',
      value: formatEnableStatus(row?.status),
    },
    {
      key: 'approvalStatus',
      label: '审批状态',
      value: approvalOptionLabel(APPROVAL_STATUS_OPTIONS, row?.approvalStatus),
    },
    {
      key: 'applyReason',
      label: '申请理由',
      value: row?.applyReason,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'description',
      label: '描述',
      value: row?.description,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'remark',
      label: '备注',
      value: row?.remark,
      span: 2,
      type: 'textarea',
    },
    ...auditDetailFields(row),
  ];
});

async function resolveSubjectNames(
  row: SafetyWhitelistApi.Whitelist,
  subjects: SafetyWhitelistApi.WhitelistSubject[],
) {
  const ids = subjects
    .map((item) => item.subjectId)
    .filter((id) => id !== null && id !== undefined);
  if (ids.length === 0) {
    return '-';
  }
  if (row.subjectType === 'USER') {
    return ids.map((id) => getUserNickname(id)).join('、');
  }
  if (row.subjectType === 'ROLE') {
    const roles = await getSimpleRoleList();
    return ids
      .map(
        (id) =>
          roles.find((role) => String(role.id) === String(id))?.name ||
          String(id),
      )
      .join('、');
  }
  if (row.subjectType === 'DEPT') {
    const depts = await getSimpleDeptList();
    return ids
      .map(
        (id) =>
          depts.find((dept) => String(dept.id) === String(id))?.name ||
          String(id),
      )
      .join('、');
  }
  return ids.map(String).join('、');
}

const [Drawer, drawerApi] = useVbenDrawer({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    const current = detailRequest.begin();
    detail.value = undefined;
    subjectNames.value = '-';
    if (!isOpen) {
      drawerApi.setState({ loading: false });
      return;
    }
    const data = drawerApi.getData() as { id?: number };
    if (!data?.id) {
      return;
    }
    drawerApi.setState({ loading: true });
    try {
      await loadObjectNameCaches();
      if (!current()) return;
      const [row, subjects] = await Promise.all([
        getWhitelist(data.id),
        getWhitelistSubjectListByWhitelistId(data.id),
      ]);
      if (!current()) return;
      detail.value = row;
      const names = await resolveSubjectNames(row, subjects);
      if (current()) {
        subjectNames.value = names;
      }
    } finally {
      if (current()) drawerApi.setState({ loading: false });
    }
  },
});
</script>

<template>
  <Drawer
    :title="`脱敏白名单详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px] max-w-full"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Drawer>
</template>
