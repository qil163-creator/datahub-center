<script lang="ts" setup>
import type { DetailFieldItem } from '../../modelcommon/detail-field-form.vue';

import type { ModelingBizCategoryApi } from '#/api/modeling/bizcategory';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { formatDateTime } from '@vben/utils';

import { getBizCategory } from '#/api/modeling/bizcategory';

import DetailFieldForm from '../../modelcommon/detail-field-form.vue';
import { useModelingRequest } from '../../modelcommon/use-modeling-request';
import { getUserNickname } from '../data';

defineOptions({ name: 'ModelingBizCategoryDetail' });

const detail = ref<ModelingBizCategoryApi.BizCategory>({});
const parentName = ref('');
const detailRequest = useModelingRequest(
  () => '',
  () => {
    detail.value = {};
    parentName.value = '';
    drawerApi.setState({ loading: false });
  },
);

const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '编号', value: detail.value.id },
  { key: 'parentName', label: '上级业务分类', value: parentName.value },
  { key: 'name', label: '业务分类名称', value: detail.value.name },
  { key: 'code', label: '英文缩写', value: detail.value.code },
  {
    key: 'leader',
    label: '负责人',
    value: getUserNickname(detail.value.leaderUserId),
  },
  { key: 'leaderPhone', label: '负责人电话', value: detail.value.leaderPhone },
  { key: 'status', label: '状态', value: formatStatus(detail.value.status) },
  {
    key: 'responsibilityScope',
    label: '责任范围',
    value: detail.value.responsibilityScope,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'description',
    label: '描述',
    value: detail.value.description,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'remark',
    label: '备注',
    value: detail.value.remark,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'creator',
    label: '创建人',
    value: getUserNickname(detail.value.creator),
  },
  {
    key: 'createTime',
    label: '创建时间',
    value: formatDateTime(detail.value.createTime),
  },
  {
    key: 'updater',
    label: '更新人',
    value: getUserNickname(detail.value.updater),
  },
  {
    key: 'updateTime',
    label: '更新时间',
    value: formatDateTime(detail.value.updateTime),
  },
]);

const [Drawer, drawerApi] = useVbenDrawer({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    const current = detailRequest.begin();
    detail.value = {};
    parentName.value = '';
    if (!isOpen) {
      drawerApi.setState({ loading: false });
      return;
    }
    const data = drawerApi.getData() as {
      id: number | string;
      parentName?: string;
    };
    parentName.value = data?.parentName ?? '';
    if (data?.id) {
      drawerApi.setState({ loading: true });
      try {
        const result = await getBizCategory(data.id);
        if (current()) detail.value = result;
      } finally {
        if (current()) drawerApi.setState({ loading: false });
      }
    }
  },
});

function formatStatus(status?: number) {
  if (status === CommonStatusEnum.ENABLE) {
    return '启用';
  }
  if (status === CommonStatusEnum.DISABLE) {
    return '停用';
  }
  return '-';
}
</script>

<template>
  <Drawer
    :title="`业务分类详情 - ${detail.name ?? ''}`"
    class="w-[960px] max-w-full"
  >
    <DetailFieldForm :fields="fields" class="mx-4" />
  </Drawer>
</template>
