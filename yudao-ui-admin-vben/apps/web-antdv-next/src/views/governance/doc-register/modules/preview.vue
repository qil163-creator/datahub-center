<script lang="ts" setup>
import type { StandardDocApi } from '#/api/governance/doc';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import { message } from 'antdv-next';

import { getDocPreviewUrl } from '#/api/governance/doc';
import { useObjectRequest } from '#/hooks/use-object-request';
import { buildKkFileViewPreviewUrl } from '#/utils/kk-file-view';

const previewSrc = ref('');
const previewTitle = ref('标准原文预览');
const access = useAccessStore();
const identity = ref('closed');
const request = useObjectRequest(
  () => identity.value + access.accessCodes.join(','),
  () => {
    previewSrc.value = '';
  },
);
let sequence = 0;

const hasPreview = computed(() => Boolean(previewSrc.value));

function getSafeFileUrl(fileUrl: string) {
  try {
    const parsedUrl = new URL(fileUrl);
    return parsedUrl.protocol === 'http:' || parsedUrl.protocol === 'https:'
      ? parsedUrl.href
      : null;
  } catch {
    return null;
  }
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen: boolean) {
    identity.value = isOpen ? String(++sequence) : 'closed';
    const current = request.begin();
    if (!isOpen) {
      previewSrc.value = '';
      previewTitle.value = '标准原文预览';
      modalApi.unlock();
      return;
    }
    const data = modalApi.getData() as StandardDocApi.Doc;
    if (!data?.id) {
      return;
    }
    previewTitle.value = data.fileName
      ? `标准原文预览 - ${data.fileName}`
      : `标准原文预览 - ${data.nameCn}`;
    modalApi.lock();
    try {
      const fileUrl = await getDocPreviewUrl(data.id);
      if (!current()) return;
      const safeFileUrl = getSafeFileUrl(fileUrl);
      if (!safeFileUrl) {
        message.error('标准原文预览地址不安全，已阻止打开');
        await modalApi.close();
        return;
      }
      previewSrc.value = buildKkFileViewPreviewUrl(safeFileUrl, data.fileName);
    } catch (error) {
      if (current()) await modalApi.close();
      throw error;
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes.join(',')],
  () => modalApi.close(),
);

const isFullscreen = modalApi.useStore((state) => state.fullscreen);

const contentClass = computed(() =>
  isFullscreen.value
    ? 'flex h-full min-h-0 flex-col overflow-hidden p-0'
    : 'overflow-hidden p-0',
);

const previewBodyClass = computed(() =>
  isFullscreen.value ? 'min-h-0 w-full flex-1' : 'h-[75vh] w-full',
);
</script>

<template>
  <Modal
    :title="previewTitle"
    class="w-[90%]"
    :show-cancel-button="false"
    :show-confirm-button="false"
    :content-class="contentClass"
  >
    <div :class="previewBodyClass">
      <iframe
        v-if="hasPreview"
        :src="previewSrc"
        class="size-full border-0"
        title="标准原文预览"
      ></iframe>
      <div
        v-else
        class="text-muted-foreground flex size-full items-center justify-center"
      >
        预览加载中...
      </div>
    </div>
  </Modal>
</template>
