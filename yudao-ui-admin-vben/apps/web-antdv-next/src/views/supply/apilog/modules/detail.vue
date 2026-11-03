<script lang="ts" setup>
import type { SupplyApiLogApi } from '#/api/supply/apilog';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onBeforeUnmount, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { getApiLog } from '#/api/supply/apilog';
import { formatJsonText } from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { formatSupplyDateTime } from '../../utils/format';
import { createLatestGuard } from '../../utils/latest';

defineOptions({ name: 'SupplyApiLogDetail' });

const detail = ref<SupplyApiLogApi.ApiLog>({});
const detailGuard = createLatestGuard();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'apiName', label: 'API服务名称', value: row.apiName },
    { key: 'traceId', label: '追踪编号', value: row.traceId },
    { key: 'rejectStage', label: '拒绝阶段', value: row.rejectStage },
    { key: 'releaseId', label: '发布快照', value: row.releaseId },
    { key: 'callerIp', label: '调用者IP', value: row.callerIp },
    {
      key: 'callPath',
      label: '调用接口地址',
      value: row.callPath,
      span: 2,
    },
    {
      key: 'paramsJson',
      label: '参数名称与配置摘要',
      value: formatJsonText(row.paramsJson),
      span: 2,
      type: 'textarea',
      rows: 6,
    },
    {
      key: 'callTime',
      label: '调用时间',
      value: formatSupplyDateTime(row.callTime),
    },
    {
      key: 'durationMs',
      label: '调用耗时(秒)',
      value: String((row.durationMs ?? 0) / 1000),
    },
    { key: 'method', label: '请求方式', value: row.method },
    { key: 'dataCount', label: '调用数据量', value: row.dataCount },
    {
      key: 'errorInfo',
      label: '异常信息记录',
      value: row.errorInfo,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'status',
      label: '状态',
      value: row.status === 1 ? '成功' : '失败',
    },
  ];
});

const [Drawer, drawerApi] = useVbenDrawer<{ id?: SupplyApiLogApi.Id }>({
  class: 'w-[960px] max-w-full',
  placement: 'right',
  title: 'API服务调用日志详情',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    const isStale = detailGuard.next();
    detail.value = {};
    drawerApi.setState({ loading: false });
    if (!isOpen) {
      return;
    }
    const data = drawerApi.getData();
    if (data?.id === undefined || data.id === null) {
      return;
    }
    drawerApi.setState({ loading: true });
    try {
      const result = await getApiLog(data.id);
      if (!isStale()) {
        detail.value = result;
      }
    } finally {
      if (!isStale()) {
        drawerApi.setState({ loading: false });
      }
    }
  },
});

onBeforeUnmount(() => detailGuard.invalidate());
</script>

<template>
  <Drawer>
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Drawer>
</template>
