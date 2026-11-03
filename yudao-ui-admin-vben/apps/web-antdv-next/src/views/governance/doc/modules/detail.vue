<script lang="ts" setup>
import type { StandardDocApi } from '#/api/governance/doc';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictLabel } from '@vben/hooks';
import { formatDate, formatDateTime } from '@vben/utils';

import { getDoc } from '#/api/governance/doc';
import {
  auditDetailFields,
  formatEnableStatus,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

defineOptions({ name: 'StandardDocDetail' });

type DocDetail = StandardDocApi.Doc & {
  creator?: string;
  replaceDocLabel?: string;
  updater?: string;
  updateTime?: number | string;
};

const detail = ref<DocDetail>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'stdCode', label: '标准编号', value: row?.stdCode },
    { key: 'nameCn', label: '标准名称', value: row?.nameCn },
    {
      key: 'stdType',
      label: '标准分类',
      value: getDictLabel(DICT_TYPE.STANDARD_STD_TYPE, row?.stdType) || '-',
    },
    { key: 'publishOrg', label: '发布机构', value: row?.publishOrg },
    {
      key: 'publishDate',
      label: '发布日期',
      value: row?.publishDate ? formatDate(row.publishDate, 'YYYY-MM-DD') : '-',
    },
    {
      key: 'implementDate',
      label: '实施日期',
      value: row?.implementDate
        ? formatDate(row.implementDate, 'YYYY-MM-DD')
        : '-',
    },
    {
      key: 'implementStatus',
      label: '实施状态',
      value:
        getDictLabel(
          DICT_TYPE.STANDARD_IMPLEMENT_STATUS,
          row?.implementStatus,
        ) || '-',
    },
    { key: 'sort', label: '显示顺序', value: row?.sort },
    { key: 'registryNo', label: '入库编号', value: row?.registryNo },
    {
      key: 'registryTime',
      label: '入库时间',
      value: formatDateTime(row?.registryTime),
    },
    {
      key: 'status',
      label: '状态',
      value: formatEnableStatus(row?.status),
    },
    {
      key: 'fileName',
      label: '原文文件名',
      value: row?.fileName,
      span: 2,
    },
    {
      key: 'replaceDocLabel',
      label: '替代标准',
      value: row?.replaceDocLabel,
      span: 2,
    },
    {
      key: 'summary',
      label: '标准摘要/适用范围说明',
      value: row?.summary,
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

async function resolveReplaceDocLabel(replaceDocId?: null | StandardDocApi.Id) {
  if (!replaceDocId) {
    return undefined;
  }
  try {
    const replaced = await getDoc(replaceDocId);
    return replaced.stdCode
      ? `${replaced.stdCode} ${replaced.nameCn}`
      : replaced.nameCn;
  } catch {
    return String(replaceDocId);
  }
}

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      detail.value = undefined;
      return;
    }
    const data = modalApi.getData() as StandardDocApi.Doc;
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      const row = await getDoc(data.id);
      detail.value = {
        ...row,
        replaceDocLabel: await resolveReplaceDocLabel(row.replaceDocId),
      };
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`标准文件登记详情${detail?.nameCn ? ` - ${detail.nameCn}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
