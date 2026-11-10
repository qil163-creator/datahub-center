<script setup lang="ts">
import type { GovernanceMetaCollectInstanceApi as Api } from '#/api/governance/metacollectinstance';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Button, message } from 'antdv-next';

import { getMetaCollectInstanceLogList } from '#/api/governance/metacollectinstance';
import { useObjectRequest } from '#/hooks/use-object-request';

import { metadataTime } from '../../metadata-common/helpers';
import MetadataIcon from '../../metadata-common/metadata-icon.vue';

const instance = ref<Api.CollectInstance>();
const logs = ref<Api.CollectLog[]>([]);
const loading = ref(false);
const failed = ref(false);
const identity = ref('closed');
const requests = useObjectRequest(
  () => identity.value,
  () => {
    logs.value = [];
  },
);
const text = computed(() =>
  logs.value
    .map((log) =>
      [
        metadataTime(log.logTime, 'YYYY-MM-DD HH:mm:ss.SSS'),
        '-',
        log.logLevel && log.logLevel !== 'INFO' ? `[${log.logLevel}]` : '',
        log.objectRef || '',
        log.content || '',
      ]
        .filter(Boolean)
        .join(' '),
    )
    .join('\n'),
);
async function load() {
  if (instance.value?.id === undefined) return;
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const rows = await getMetaCollectInstanceLogList(instance.value.id);
    if (current()) logs.value = rows;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function copy() {
  try {
    await navigator.clipboard.writeText(text.value);
    message.success('日志已复制');
  } catch {
    message.error('复制失败，请选择日志文本复制');
  }
}
const [Modal, modalApi] = useVbenModal({
  title: '日志详情',
  class: 'metadata-modal top-[6vh] w-[800px] max-w-[calc(100vw-32px)]',
  fullscreenButton: false,
  confirmText: '确定',
  headerClass: 'metadata-modal__header',
  contentClass: 'metadata-modal__body',
  footerClass: 'metadata-modal__footer',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(open) {
    if (!open) {
      identity.value = 'closed';
      requests.begin();
      logs.value = [];
      return;
    }
    instance.value = modalApi.getData() as Api.CollectInstance;
    identity.value = String(instance.value?.id);
    await load();
  },
});
</script>
<template>
  <Modal>
    <div class="log-actions">
      <Button
        type="link"
        size="small"
        :disabled="loading || failed || !logs.length"
        @click="copy"
      >
        <template #icon><MetadataIcon name="copy" /></template>
        复制
      </Button>
    </div>
    <pre class="metadata-log">{{
      loading ? '正在读取日志…' : text || '暂无日志'
    }}</pre>
  </Modal>
</template>
<style scoped>
.log-actions {
  display: flex;
  justify-content: flex-end;
}

.metadata-log {
  min-height: 400px;
  max-height: 70vh;
  padding: 0 20px 20px;
  margin: 0;
  overflow: auto;
  line-height: 1.4;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: hsl(var(--card));
}
</style>
