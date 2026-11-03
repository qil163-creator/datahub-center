<script setup lang="ts">
import type { TableColumnsType } from 'antdv-next';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { Alert, Descriptions, DescriptionsItem, Table, Tag } from 'antdv-next';

import { getMasterModel } from '#/api/governance/masterdata';

import { useMasterScope } from '../../mastercommon/use-master-scope';
import { fieldTypeLabel, MODEL_STATUS_OPTIONS } from '../data';

defineOptions({ name: 'GovernanceMasterModelDetail' });

type Field = GovernanceMasterDataApi.Column & { rowKey: string };
const detail = ref<GovernanceMasterDataApi.Model>();
const failed = ref(false);
const scope = useMasterScope(() => {
  reset();
  void modalApi.close();
});
const status = computed(() =>
  MODEL_STATUS_OPTIONS.find((item) => item.value === detail.value?.status),
);
const columns = computed<Field[]>(() =>
  (detail.value?.columns ?? []).map((field, index) => ({
    ...field,
    rowKey: field.id === undefined ? `field-${index}` : String(field.id),
  })),
);
const fieldColumns: TableColumnsType<Field> = [
  { title: '字段名', dataIndex: 'name', key: 'name', width: 180 },
  {
    title: '属性名称',
    dataIndex: 'commentStr',
    key: 'commentStr',
    width: 180,
  },
  {
    title: '类型',
    key: 'fieldType',
    width: 140,
    render: (_value, record) =>
      fieldTypeLabel(record.fieldType) === '-' && record.fieldType !== undefined
        ? `未知类型（${record.fieldType}）`
        : fieldTypeLabel(record.fieldType),
  },
  {
    title: '长度',
    key: 'fieldLength',
    width: 90,
    render: (_value, record) => record.fieldLength ?? '-',
  },
  {
    title: '小数位',
    key: 'fieldScale',
    width: 90,
    render: (_value, record) => record.fieldScale ?? '-',
  },
  {
    title: '主键',
    key: 'pk',
    width: 80,
    render: (_value, record) => flagLabel(record.pk),
  },
  {
    title: '可空',
    key: 'nullable',
    width: 80,
    render: (_value, record) => flagLabel(record.nullable),
  },
];

function flagLabel(value?: number) {
  if (value === 1) return '是';
  if (value === 0) return '否';
  return '-';
}

function reset() {
  scope.invalidate();
  detail.value = undefined;
  failed.value = false;
  modalApi.setState({ loading: false });
}

const [Modal, modalApi] = useVbenModal<{
  id?: GovernanceMasterDataApi.Id;
}>({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(open) {
    reset();
    if (!open) return;
    const data = modalApi.getData();
    if (data?.id === undefined) {
      failed.value = true;
      return;
    }
    const current = scope.begin('detail');
    modalApi.setState({ loading: true });
    try {
      const result = await getMasterModel(data.id);
      if (current()) detail.value = result;
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
    :title="`主数据模型详情${detail?.tableCn ? ` - ${detail.tableCn}` : ''}`"
    class="w-[1000px] max-w-full"
  >
    <Alert
      v-if="failed"
      class="mx-4"
      type="error"
      show-icon
      message="主数据模型详情加载失败，请重新打开。"
    />
    <div v-else-if="detail" class="mx-4 space-y-5">
      <Descriptions :column="2" bordered size="small">
        <DescriptionsItem label="模型名称">
          {{ detail.tableCn || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="模型编码 / 物理表名">
          {{ detail.tableName || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="所属主数据域">
          {{ detail.domainName || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="目标数据源">
          {{ detail.databaseName || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="发布状态">
          <Tag :color="status?.color ?? 'default'">
            {{ status?.label ?? `未知状态（${detail.status ?? '-'}）` }}
          </Tag>
        </DescriptionsItem>
        <DescriptionsItem label="版本">
          {{ detail.version === undefined ? '-' : `V${detail.version}` }}
        </DescriptionsItem>
        <DescriptionsItem label="创建时间" :span="2">
          {{ formatDateTime(detail.createTime) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="模型描述" :span="2">
          <span class="whitespace-pre-wrap break-words">
            {{ detail.description || '-' }}
          </span>
        </DescriptionsItem>
      </Descriptions>
      <div>
        <div class="mb-3 font-medium">字段定义（{{ columns.length }} 个）</div>
        <Table
          :columns="fieldColumns"
          :data-source="columns"
          :pagination="false"
          :scroll="{ x: 840, y: 360 }"
          row-key="rowKey"
          size="small"
        />
      </div>
    </div>
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
