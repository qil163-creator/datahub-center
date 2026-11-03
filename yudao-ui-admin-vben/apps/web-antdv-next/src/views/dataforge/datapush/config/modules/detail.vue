<script lang="ts" setup>
import type { DataforgeDataPushConfigApi } from '#/api/dataforge/datapush/config';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { getDataPushConfig } from '#/api/dataforge/datapush/config';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { getConfigDetailFields } from '../detail-fields';

defineOptions({ name: 'DataforgeDataPushConfigDetail' });

const detail = ref<DataforgeDataPushConfigApi.Config>();

const fields = computed(() => getConfigDetailFields(detail.value ?? {}));

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      detail.value = undefined;
      return;
    }
    const data = modalApi.getData() as undefined | { id?: number | string };
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getDataPushConfig(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`推送配置详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
