<script lang="ts" setup>
import type { FoundationSystemSourceApi } from '#/api/dataforge/systemsource';
import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemUserApi } from '#/api/system/user';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { formatDateTime } from '@vben/utils';

import { getSystemSource } from '#/api/dataforge/systemsource';
import { getDeptList } from '#/api/system/dept';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

defineOptions({ name: 'FoundationSystemSourceDetail' });

const detail = ref<FoundationSystemSourceApi.SystemSource>();
const detailRequest = useObjectRequest(
  () => '',
  () => {
    detail.value = undefined;
    modalApi.setState({ loading: false });
    void modalApi.close();
  },
);

const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '主键', value: detail.value?.id },
  { key: 'name', label: '系统名称', value: detail.value?.name },
  {
    key: 'type',
    label: '系统类型',
    value: dictLabel(DICT_TYPE.SYSTEM_SOURCE_TYPE, detail.value?.type),
  },
  { key: 'sortOrder', label: '排序', value: detail.value?.sortOrder },
  { key: 'icon', label: '系统图标', value: detail.value?.icon, span: 2 },
  { key: 'url', label: '系统地址', value: detail.value?.url, span: 2 },
  { key: 'deptName', label: '所属部门', value: detail.value?.deptName },
  { key: 'ownerUserName', label: '负责人', value: detail.value?.ownerUserName },
  { key: 'ownerMobile', label: '联系方式', value: detail.value?.ownerMobile },
  {
    key: 'status',
    label: '状态',
    value: dictLabel(DICT_TYPE.COMMON_STATUS, detail.value?.status, 'number'),
  },
  {
    key: 'description',
    label: '说明',
    value: detail.value?.description,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'remark',
    label: '备注',
    value: detail.value?.remark,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'createTime',
    label: '创建时间',
    value: formatDateTime(detail.value?.createTime),
  },
  {
    key: 'updateTime',
    label: '更新时间',
    value: formatDateTime(detail.value?.updateTime),
  },
]);

function dictLabel(
  type: string,
  value: unknown,
  valueType: 'number' | 'string' = 'string',
) {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  const options =
    valueType === 'number'
      ? getDictOptions(type, 'number')
      : getDictOptions(type, 'string');
  const found = options.find((item) => String(item.value) === String(value));
  return found?.label ?? String(value);
}

function getDeptName(
  deptId: number | string | undefined,
  deptList: SystemDeptApi.Dept[],
) {
  if (deptId === null || deptId === undefined || deptId === '') {
    return '-';
  }
  return (
    deptList.find((item) => String(item.id) === String(deptId))?.name || '-'
  );
}

function getOwnerInfo(
  ownerUserId: number | string | undefined,
  userList: SystemUserApi.User[],
) {
  if (ownerUserId === null || ownerUserId === undefined || ownerUserId === '') {
    return { ownerUserName: '-', ownerMobile: '-' };
  }
  const user = userList.find((item) => String(item.id) === String(ownerUserId));
  return {
    ownerUserName: user?.nickname || '-',
    ownerMobile: user?.mobile || '-',
  };
}

const [Modal, modalApi] =
  useVbenModal<FoundationSystemSourceApi.SystemSource>({
    centered: true,
    showConfirmButton: false,
    cancelText: '关闭',
    async onOpenChange(isOpen: boolean) {
      const current = detailRequest.begin();
      detail.value = undefined;
      modalApi.setState({ loading: false });
      if (!isOpen) {
        return;
      }
      const data = modalApi.getData();
      if (!data?.id) {
        return;
      }
      modalApi.setState({ loading: true });
      try {
        const [source, deptList, userList] = await Promise.all([
          getSystemSource(data.id),
          getDeptList(),
          getSimpleUserList(),
        ]);
        if (!current()) return;
        const { ownerUserName, ownerMobile } = getOwnerInfo(
          source.ownerUserId,
          userList,
        );
        detail.value = {
          ...source,
          deptName: getDeptName(source.deptId, deptList),
          ownerUserName,
          ownerMobile,
        };
      } finally {
        if (current()) modalApi.setState({ loading: false });
      }
    },
  });
</script>

<template>
  <Modal
    :title="`来源系统详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4">
      <template v-if="detail?.icon" #icon>
        <img
          :src="detail.icon"
          alt=""
          class="h-16 w-16 rounded object-contain"
        />
      </template>
    </DetailFieldForm>
  </Modal>
</template>
