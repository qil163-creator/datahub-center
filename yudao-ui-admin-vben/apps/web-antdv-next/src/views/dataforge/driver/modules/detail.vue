<script lang="ts" setup>
import type { FoundationDatasourceDriverApi } from '#/api/dataforge/driver';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { getDatasourceDriver } from '#/api/dataforge/driver';
import { useDescription } from '#/components/description';

import { useDetailSchema } from '../data';

const formData = ref<FoundationDatasourceDriverApi.DatasourceDriver>();

const [Descriptions] = useDescription({
  bordered: true,
  column: 2,
  useCard: false,
  schema: useDetailSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      return;
    }
    const data =
      modalApi.getData() as FoundationDatasourceDriverApi.DatasourceDriver;
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getDatasourceDriver(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    title="数据源驱动详情"
    class="w-[960px]"
    :show-confirm-button="false"
    cancel-text="关闭"
  >
    <Descriptions :data="formData" class="mx-4" />
  </Modal>
</template>
