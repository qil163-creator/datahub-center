<script setup lang="ts">
import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { ref, watch } from 'vue';

import { Button, message, Modal, Radio, RadioGroup } from 'antdv-next';

import { setIntegrationSchedule } from '#/api/dataforge/integration';
import { CronTab } from '#/components/cron-tab';
import { useObjectRequest } from '#/hooks/use-object-request';

const props = defineProps<{
  editable: boolean;
  open: boolean;
  task?: Api.Task;
}>();
const emit = defineEmits<{ saved: []; 'update:open': [value: boolean] }>();
const expression = ref('');
const status = ref(0);
const busy = ref(false);
const request = useObjectRequest(() => `${props.open}/${props.task?.id ?? ''}`);
watch(
  () => [props.open, props.task],
  () => {
    if (props.open) {
      expression.value = props.task?.cronExpression || '0 0 0 * * ?';
      status.value = props.task?.scheduleStatus ?? 0;
      busy.value = false;
    }
  },
  { immediate: true },
);
async function save() {
  if (!props.task || busy.value || !props.editable) return;
  const current = request.begin();
  busy.value = true;
  try {
    await setIntegrationSchedule({
      id: props.task.id,
      revision: props.task.revision,
      cronExpression: expression.value,
      scheduleStatus: status.value,
    });
    if (!current()) return;
    message.success('调度周期保存成功');
    emit('saved');
    emit('update:open', false);
  } finally {
    if (current()) busy.value = false;
  }
}
</script>
<template>
  <Modal
    :open="open"
    title="调度周期"
    width="680px"
    wrap-class-name="qi-modal"
    :mask-closable="false"
    :closable="!busy"
    @cancel="!busy && $emit('update:open', false)"
  >
    <div class="qi-schedule-content">
      <p class="qi-schedule-name">{{ task?.name }}</p>
      <div class="qi-schedule-row">
        <label>调度状态</label><RadioGroup v-model:value="status" :disabled="!editable">
          <Radio :value="0">关闭</Radio><Radio :value="1">开启</Radio>
        </RadioGroup>
      </div>
      <div class="qi-schedule-row">
        <label>调度周期</label><CronTab v-model="expression" :disabled="!editable" />
      </div>
    </div>
    <template #footer>
      <Button :disabled="busy" @click="$emit('update:open', false)">
        取消
</Button><Button v-if="editable" type="primary" :loading="busy" @click="save">
        确定
      </Button>
    </template>
  </Modal>
</template>
