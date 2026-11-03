<script setup lang="ts">
import type { AssetQualityTaskApi } from '#/api/asset/qualitytask';

import { computed, onBeforeUnmount, ref, toRaw, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Form, FormItem, message, Radio, RadioGroup } from 'antdv-next';

import { updateAssetQualityTask } from '#/api/asset/qualitytask';
import { CronTab } from '#/components/cron-tab';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

const emit = defineEmits<{ refresh: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const allowed = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:quality-task:manage']),
);
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const task = ref<AssetQualityTaskApi.QualityTask>();
const scheduleType = ref(0);
const cron = ref('0 0 0 1 * ?');
const saving = ref(false);
const guard = createLatestWins();
let opened = false;
const [Modal, modalApi] = useVbenModal({
  title: '调度周期',
  fullscreenButton: false,
  closeOnClickModal: false,
  onOpenChange(value) {
    opened = value;
    guard.invalidate();
    task.value = undefined;
    saving.value = false;
    modalApi.unlock();
    if (!value || !allowed.value) return;
    const data = modalApi.getData() as {
      task: AssetQualityTaskApi.QualityTask;
    };
    if (!parseResourceId(data?.task?.id)) return;
    task.value = structuredClone(toRaw(data.task));
    scheduleType.value = data.task.scheduleType;
    cron.value = data.task.cronExpression || '0 0 0 1 * ?';
  },
  async onConfirm() {
    if (!opened || !allowed.value || !task.value || saving.value) return;
    if (scheduleType.value === 1 && !cron.value.trim()) {
      message.warning('请输入调度周期');
      return;
    }
    const ticket = guard.next();
    const data = {
      ...toRaw(task.value),
      scheduleType: scheduleType.value,
      cronExpression: scheduleType.value === 1 ? cron.value.trim() : undefined,
    };
    saving.value = true;
    modalApi.lock();
    try {
      await updateAssetQualityTask(data);
      if (!ticket.isCurrent() || !opened) return;
      emit('refresh');
      message.success('调度配置已保存');
      await modalApi.close();
    } catch {
      if (ticket.isCurrent()) emit('refresh');
    } finally {
      if (ticket.isCurrent()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
});
watch(
  owner,
  () => {
    opened = false;
    guard.invalidate();
    task.value = undefined;
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => guard.invalidate());
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    class="!top-[60px] !w-[1200px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
    :confirm-disabled="!allowed || !task || saving"
  >
    <Form class="mb-4" :colon="false">
      <FormItem label="调度方式">
        <RadioGroup v-model:value="scheduleType">
          <Radio :value="0">手动执行</Radio><Radio :value="1">定时调度</Radio>
        </RadioGroup>
      </FormItem>
    </Form>
    <CronTab
      v-model:model-value="cron"
      :disabled="scheduleType !== 1"
      week-mode="quartz"
    />
  </Modal>
</template>
