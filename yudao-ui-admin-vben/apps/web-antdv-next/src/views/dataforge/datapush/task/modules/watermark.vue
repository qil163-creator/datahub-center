<script lang="ts" setup>
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, message } from 'antdv-next';

import {
  getDataPushTaskWatermark,
  resetDataPushTaskWatermark,
} from '#/api/dataforge/datapush/task';

import { isExecutionBlocked } from '../run-state';

defineOptions({ name: 'DataforgeDataPushTaskWatermark' });

const emit = defineEmits<{ (e: 'success'): void }>();

const RESET_WATERMARK_AUTH = 'dataforge:data-push:run';

const { hasAccessByCodes } = useAccess();

const task = ref<DataforgeDataPushTaskApi.Task>();
const watermark = ref<DataforgeDataPushTaskApi.Watermark>();
const loading = ref(false);
const resetting = ref(false);
let requestSeq = 0;

const canReset = computed(() => hasAccessByCodes([RESET_WATERMARK_AUTH]));

const getTitle = computed(() =>
  task.value ? `增量水位 - ${task.value.name}` : '增量水位',
);

async function loadWatermark() {
  if (!task.value?.id) {
    return;
  }
  const seq = ++requestSeq;
  loading.value = true;
  try {
    const result = await getDataPushTaskWatermark(task.value.id);
    if (seq !== requestSeq) {
      return;
    }
    watermark.value = result;
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

async function handleReset() {
  if (!task.value?.id) {
    return;
  }
  if (isExecutionBlocked(task.value)) {
    message.warning('任务执行中或结果未知，禁止重置水位；停止不等于外部未写入');
    return;
  }
  await confirm(
    watermark.value?.initStartVal
      ? `确认重置「${task.value.name}」的增量水位吗？下次增量将从配置的首次起始水位「${watermark.value.initStartVal}」扫到当前 MAX。已发布任务也可重置。`
      : `确认重置「${task.value.name}」的增量水位吗？下次增量将视为首次，按当前 MAX 重新扫描。已发布任务也可重置。`,
  );
  resetting.value = true;
  try {
    await resetDataPushTaskWatermark(task.value.id);
    message.success(
      watermark.value?.initStartVal
        ? '水位已重置，下次从配置的首次起始水位继续'
        : '水位已重置，下次增量将按首次执行（从最小到当前 MAX）',
    );
    await loadWatermark();
    emit('success');
  } finally {
    resetting.value = false;
  }
}

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      requestSeq++;
      watermark.value = undefined;
      return;
    }
    task.value = modalApi.getData() as
      | DataforgeDataPushTaskApi.Task
      | undefined;
    await loadWatermark();
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[960px]">
    <div class="mx-4 pb-2">
      <Alert
        class="mb-3"
        message="水位是上次成功增量的上界。重置后下次按首次规则重跑：有起始水位则从该值之后开始，否则扫到当前 MAX。"
        show-icon
        type="info"
      />
      <div v-if="loading" class="text-muted-foreground text-sm">加载中...</div>
      <template v-else>
        <div class="watermark-grid">
          <div class="watermark-row">
            <span>增量字段</span>
            <strong>{{
              watermark?.incrementColumn || task?.incrementColumn || '-'
            }}</strong>
          </div>
          <div class="watermark-row">
            <span>下界（不含）</span>
            <strong>{{
              watermark?.exists
                ? (watermark.startVal ?? '（首次为空）')
                : '尚未产生'
            }}</strong>
          </div>
          <div class="watermark-row">
            <span>上界（含）</span>
            <strong>{{
              watermark?.exists ? (watermark.endVal ?? '-') : '尚未产生'
            }}</strong>
          </div>
          <div class="watermark-row">
            <span>更新时间</span>
            <strong>{{
              watermark?.updateTime
                ? formatDateTime(watermark.updateTime) || '-'
                : '-'
            }}</strong>
          </div>
          <div class="watermark-row">
            <span>首次起始水位</span>
            <strong>{{
              watermark?.initStartVal || '未配置（首次全量区间）'
            }}</strong>
          </div>
          <div v-if="watermark?.resetTime" class="watermark-row">
            <span>最近重置</span>
            <strong>
              {{ formatDateTime(watermark.resetTime) }}
              ·
              {{ watermark.resetUserName || watermark.resetUserId || '-' }}
              · 旧上界 {{ watermark.resetOldEnd || '（无）' }}
            </strong>
          </div>
        </div>
        <div v-if="canReset" class="mt-4 flex justify-end">
          <Button
            :disabled="isExecutionBlocked(task ?? {}) || !task?.id"
            :loading="resetting"
            danger
            @click="handleReset"
          >
            <template #icon><IconifyIcon icon="lucide:rotate-ccw" /></template>
            重置水位
          </Button>
        </div>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
.watermark-grid {
  display: grid;
  gap: 10px;
}

.watermark-row {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

.watermark-row span {
  color: hsl(var(--muted-foreground));
}
</style>
