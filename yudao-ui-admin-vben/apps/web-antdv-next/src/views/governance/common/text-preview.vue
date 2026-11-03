<script lang="ts" setup>
import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

defineOptions({ name: 'GovernanceTextPreview' });

const title = ref('详情');
const body = ref('');

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      title.value = '详情';
      body.value = '';
      return;
    }
    const data = modalApi.getData() as { text?: string; title?: string };
    title.value = data.title || '详情';
    body.value = data.text || '';
  },
});
</script>

<template>
  <Modal :title="title" class="w-[960px]">
    <pre class="governance-text-preview mx-4 mb-2">{{ body || '-' }}</pre>
  </Modal>
</template>

<style scoped>
.governance-text-preview {
  max-height: 520px;
  padding: 12px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 50%);
  border-radius: 6px;
}
</style>
