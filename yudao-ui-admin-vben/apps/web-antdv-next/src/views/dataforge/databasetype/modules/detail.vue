<script lang="ts" setup>
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert } from 'antdv-next';

import { getDatabaseType } from '#/api/dataforge/databasetype';
import { ConnectionIcon } from '#/components/connection-icon';
import { useObjectRequest } from '#/hooks/use-object-request';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

defineOptions({ name: 'FoundationDatabaseTypeDetail' });

const access = useAccessStore();
const detail = ref<FoundationDatabaseTypeApi.DatabaseType>();
const failed = ref(false);
const detailRequest = useObjectRequest(
  () => access.accessCodes.join('/'),
  () => {
    detail.value = undefined;
    failed.value = false;
    modalApi.setState({ loading: false });
    void modalApi.close();
  },
);

function dictLabel(
  type: string,
  value: number | string | undefined,
  valueType: 'number' | 'string' = 'string',
) {
  if (value === undefined || value === null || value === '') return '-';
  const options =
    valueType === 'number'
      ? getDictOptions(type, 'number')
      : getDictOptions(type, 'string');
  return (
    options.find((item) => String(item.value) === String(value))?.label ??
    String(value)
  );
}

const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '编号', value: detail.value?.id },
  { key: 'typeCode', label: '数据源编码', value: detail.value?.typeCode },
  { key: 'typeName', label: '数据源名称', value: detail.value?.typeName },
  { key: 'sort', label: '排序', value: detail.value?.sort },
  {
    key: 'categoryCode',
    label: '连接分类',
    value: dictLabel(DICT_TYPE.CATEGORY_CODE, detail.value?.categoryCode),
  },
  {
    key: 'protocolType',
    label: '连接协议类型',
    value: dictLabel(DICT_TYPE.PROTOCOL_TYPE, detail.value?.protocolType),
  },
  {
    key: 'status',
    label: '状态',
    value: dictLabel(DICT_TYPE.COMMON_STATUS, detail.value?.status, 'number'),
  },
  {
    key: 'createTime',
    label: '创建时间',
    value:
      detail.value?.createTime === undefined
        ? '-'
        : formatDateTime(detail.value.createTime),
  },
  {
    key: 'iconPath',
    label: '连接图标',
    value: detail.value?.iconPath,
    span: 2,
  },
  {
    key: 'description',
    label: '描述',
    value: detail.value?.description,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'urlTemplates',
    label: '数据源URL',
    value: detail.value?.urlTemplates,
    span: 2,
    type: 'textarea',
    rows: 2,
  },
  {
    key: 'portTemplates',
    label: '默认端口',
    value: detail.value?.portTemplates,
    span: 2,
  },
  {
    key: 'validationQuery',
    label: '连接测试SQL',
    value: detail.value?.validationQuery,
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
]);

const [Modal, modalApi] = useVbenModal<{
  id: FoundationDatabaseTypeApi.DatabaseType['id'];
}>({
  centered: true,
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen) {
    const current = detailRequest.begin();
    detail.value = undefined;
    failed.value = false;
    modalApi.setState({ loading: false });
    if (!isOpen) return;
    const data = modalApi.getData();
    if (data?.id === undefined || data.id === null) return;
    modalApi.setState({ loading: true });
    try {
      const result = await getDatabaseType(data.id);
      if (!current()) return;
      if (result) detail.value = result;
      else failed.value = true;
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) modalApi.setState({ loading: false });
    }
  },
});
</script>

<template>
  <Modal
    :title="`连接器详情${detail?.typeName ? ` - ${detail.typeName}` : ''}`"
    class="w-[960px]"
  >
    <Alert
      v-if="failed"
      class="mx-4"
      type="error"
      show-icon
      message="连接器详情加载失败，请重新打开。"
    />
    <DetailFieldForm v-else-if="detail" :fields="fields" class="mx-4">
      <template #iconPath>
        <ConnectionIcon
          :icon-path="detail.iconPath"
          :type-code="detail.typeCode || detail.productId"
          :alt="detail.typeName"
          class="size-16"
        />
      </template>
    </DetailFieldForm>
  </Modal>
</template>
