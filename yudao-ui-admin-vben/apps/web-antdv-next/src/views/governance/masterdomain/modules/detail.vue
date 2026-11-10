<script setup lang="ts">
import type { GovernanceMasterDomainApi } from '#/api/governance/masterdomain';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { formatDateTime } from '@vben/utils';

import { getMasterDomain } from '#/api/governance/masterdomain';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { useMasterScope } from '../../mastercommon/use-master-scope';

defineOptions({ name: 'GovernanceMasterDomainDetail' });

const detail = ref<GovernanceMasterDomainApi.Domain>();
const failed = ref(false);
const scope = useMasterScope(() => {
  detail.value = undefined;
  void modalApi.close();
});
const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '编号', value: detail.value?.id },
  { key: 'name', label: '主数据域名称', value: detail.value?.name },
  { key: 'code', label: '英文缩写', value: detail.value?.code },
  {
    key: 'status',
    label: '状态',
    value: formatStatus(detail.value?.status),
  },
  {
    key: 'leader',
    label: '负责人',
    value: detail.value?.leaderUserName || detail.value?.leaderUserId,
  },
  {
    key: 'leaderPhone',
    label: '负责人电话',
    value: detail.value?.leaderPhone,
  },
  {
    key: 'description',
    label: '描述',
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
    key: 'creator',
    label: '创建人',
    value: detail.value?.creatorName || detail.value?.creator,
  },
  {
    key: 'createTime',
    label: '创建时间',
    value: formatDateTime(detail.value?.createTime),
  },
  {
    key: 'updater',
    label: '更新人',
    value: detail.value?.updaterName || detail.value?.updater,
  },
  {
    key: 'updateTime',
    label: '更新时间',
    value: formatDateTime(detail.value?.updateTime),
  },
]);
const [Modal, modalApi] = useVbenModal<{
  id?: GovernanceMasterDomainApi.Id;
}>({
  class: 'top-[6vh] w-[800px]',
  bordered: false,
  closeOnClickModal: false,
  fullscreenButton: false,
  headerClass: 'px-6 pt-6 pb-2',
  contentClass: 'px-6 py-0',
  footerClass: 'px-6 py-5',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(open) {
    scope.invalidate();
    detail.value = undefined;
    failed.value = false;
    modalApi.setState({ loading: false });
    if (!open) return;
    const data = modalApi.getData();
    if (data?.id === undefined) return;
    const current = scope.begin('detail');
    modalApi.setState({ loading: true });
    try {
      const result = await getMasterDomain(data.id);
      if (current()) detail.value = result;
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) modalApi.setState({ loading: false });
    }
  },
});
function formatStatus(status?: number) {
  if (status === CommonStatusEnum.ENABLE) return '启用';
  if (status === CommonStatusEnum.DISABLE) return '停用';
  return '-';
}
</script>

<template>
  <Modal :title="`主数据域详情 - ${detail?.name ?? ''}`">
    <template v-if="failed"></template>
    <DetailFieldForm v-else :fields="fields" label-width="110px" />
  </Modal>
</template>
