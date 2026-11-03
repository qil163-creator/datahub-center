<script lang="ts" setup>
import type { DetailFieldItem } from '../../modelcommon/detail-field-form.vue';

import type { FoundationDwLayerApi } from '#/api/modeling/dwlayer';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { Alert, Button } from 'antdv-next';

import { getDwLayer } from '#/api/modeling/dwlayer';

import DetailFieldForm from '../../modelcommon/detail-field-form.vue';
import { useModelingRequest } from '../../modelcommon/use-modeling-request';

const detail = ref<FoundationDwLayerApi.DwLayer>();
const selectedId = ref<FoundationDwLayerApi.Id>();
const parentName = ref('');
const failed = ref(false);
const requests = useModelingRequest(
  () => selectedId.value ?? '',
  () => {
    detail.value = undefined;
    failed.value = false;
    drawerApi.setState({ loading: false });
  },
);

const fields = computed<DetailFieldItem[]>(() => [
  { key: 'layerName', label: '名称', value: detail.value?.layerName },
  { key: 'layerCode', label: '分层编码', value: detail.value?.layerCode },
  { key: 'parent', label: '所属分类', value: parentName.value },
  { key: 'owner', label: '负责人', value: detail.value?.ownerName },
  { key: 'tablePrefix', label: '表前缀', value: detail.value?.tablePrefix },
  {
    key: 'defaultTableType',
    label: '默认表类型',
    value:
      getDictOptions(DICT_TYPE.DEFAULT_TABLE_TYPE, 'string').find(
        (option) => option.value === detail.value?.defaultTableType,
      )?.label ?? detail.value?.defaultTableType,
  },
  {
    key: 'logicModelCount',
    label: '逻辑模型数',
    value: detail.value?.logicModelCountAvailable
      ? detail.value.logicModelCount
      : '暂无查询权限',
  },
  {
    key: 'status',
    label: '状态',
    value: detail.value?.status === CommonStatusEnum.ENABLE ? '启用' : '停用',
  },
  {
    key: 'builtin',
    label: '节点来源',
    value: detail.value?.isBuiltin === 1 ? '内置' : '自定义',
  },
  { key: 'sortOrder', label: '排序', value: detail.value?.sortOrder },
  {
    key: 'createTime',
    label: '创建时间',
    value: detail.value?.createTime
      ? formatDateTime(detail.value.createTime)
      : undefined,
  },
  {
    key: 'description',
    label: '说明',
    value: detail.value?.description,
    type: 'textarea',
    span: 2,
  },
  {
    key: 'remark',
    label: '备注',
    value: detail.value?.remark,
    type: 'textarea',
    span: 2,
  },
]);

async function loadDetail() {
  if (selectedId.value === undefined) return;
  const current = requests.begin();
  failed.value = false;
  detail.value = undefined;
  drawerApi.setState({ loading: true });
  try {
    const result = await getDwLayer(selectedId.value);
    if (current()) detail.value = result;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) drawerApi.setState({ loading: false });
  }
}

const [Drawer, drawerApi] = useVbenDrawer({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    requests.begin();
    detail.value = undefined;
    failed.value = false;
    if (!isOpen) {
      selectedId.value = undefined;
      parentName.value = '';
      drawerApi.setState({ loading: false });
      return;
    }
    const data = drawerApi.getData() as {
      id: FoundationDwLayerApi.Id;
      parentName?: string;
    };
    selectedId.value = data.id;
    parentName.value = data.parentName ?? '';
    await loadDetail();
  },
});
</script>

<template>
  <Drawer
    :title="`数仓分层详情${detail?.layerName ? ` · ${detail.layerName}` : ''}`"
    class="w-[800px] max-w-full"
  >
    <Alert
      v-if="failed"
      type="error"
      message="分层详情加载失败，请重试"
      show-icon
    >
      <template #action>
        <Button size="small" @click="loadDetail">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <DetailFieldForm v-else-if="detail" :fields="fields" class="mx-2" />
  </Drawer>
</template>
