<script lang="ts" setup>
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Form,
  FormItem,
  message,
  Modal,
  Spin,
  Switch,
  Tag,
} from 'antdv-next';

import {
  getDevTask,
  isNativeProgramNotReady,
  isNativeProgramTask,
  updateDevTask,
  updateDevTaskSchedule,
} from '#/api/dataforge/dev';
import { CronTab } from '#/components/cron-tab';

import { DEV_SCHEDULE_SYNC_OPTIONS, optionColor, optionLabel } from '../data';
import CronPreview from './cron-preview.vue';
import TaskTypeIcon from './task-type-icon.vue';

const emit = defineEmits<{ saved: [] }>();
const { hasAccessByCodes } = useAccess();
const accessStore = useAccessStore();

const visible = ref(false);
const loading = ref(false);
const loadError = ref('');
const saving = ref(false);
const currentTask = ref<DataforgeDevApi.Task>();
const taskReady = computed(
  () =>
    currentTask.value !== undefined &&
    currentTask.value.currentVersionId !== undefined &&
    currentTask.value.currentVersionId !== null &&
    currentTask.value.status === 1 &&
    !isNativeProgramNotReady(currentTask.value),
);
let generation = 0;

const formData = reactive<{
  cronExpression?: string;
  id?: DataforgeDevApi.Id;
  scheduleStatus?: number;
}>({});
const canEnableSchedule = computed(
  () => taskReady.value && Boolean(formData.cronExpression?.trim()),
);

async function open(taskId: DataforgeDevApi.Id) {
  const seq = ++generation;
  visible.value = true;
  loading.value = true;
  loadError.value = '';
  saving.value = false;
  currentTask.value = undefined;
  formData.id = taskId;
  formData.cronExpression = undefined;
  formData.scheduleStatus = 0;
  try {
    const task = await getDevTask(taskId);
    if (seq !== generation || !visible.value) return;
    currentTask.value = task;
    formData.scheduleStatus = task.scheduleStatus ?? 0;
    formData.cronExpression = task.cronExpression ?? undefined;
  } catch {
    if (seq === generation && visible.value)
      loadError.value = '任务配置加载失败，请重试。';
  } finally {
    if (seq === generation) loading.value = false;
  }
}

function canSchedule() {
  return (
    !!currentTask.value &&
    hasAccessByCodes(['dataforge:dev-task:run']) &&
    (!isNativeProgramTask(currentTask.value) ||
      hasAccessByCodes(['dataforge:dev-task:execute-native']))
  );
}

async function handleOk() {
  if (saving.value || !currentTask.value || !canSchedule()) return;
  if (formData.scheduleStatus === 1 && !canEnableSchedule.value) return;
  const seq = generation;
  saving.value = true;
  try {
    const changed =
      formData.cronExpression !==
      (currentTask.value.cronExpression ?? undefined);
    if (changed && !hasAccessByCodes(['dataforge:dev-task:save'])) return;
    if (changed)
      await updateDevTask({
        ...currentTask.value,
        scheduler: 'DOLPHIN_SCHEDULER',
        cronExpression: formData.cronExpression,
        expectedRevision: currentTask.value.revision,
      });
    if (seq !== generation || !visible.value) return;
    await updateDevTaskSchedule({
      id: formData.id!,
      scheduleStatus: formData.scheduleStatus ?? 0,
    });
    if (seq !== generation || !visible.value) return;
    message.success('调度设置已保存，正在同步到 DS');
    close();
    emit('saved');
  } finally {
    if (seq === generation) saving.value = false;
  }
}

function reset() {
  if (!currentTask.value || saving.value) return;
  formData.cronExpression = currentTask.value.cronExpression ?? undefined;
  formData.scheduleStatus = currentTask.value.scheduleStatus ?? 0;
}

function close() {
  ++generation;
  visible.value = false;
  currentTask.value = undefined;
  loading.value = false;
  saving.value = false;
  loadError.value = '';
}
onBeforeUnmount(close);
watch(() => [accessStore.accessToken, accessStore.refreshToken], close);
defineExpose({ open, close });
</script>

<template>
  <Modal
    :open="visible"
    :width="1040"
    :style="{ top: '5vh', maxWidth: 'calc(100vw - 32px)' }"
    :styles="{ body: { maxHeight: '75vh', overflowY: 'auto' } }"
    :closable="!saving"
    :mask-closable="!saving"
    :keyboard="!saving"
    destroy-on-hidden
    title="调度周期"
    @cancel="close"
  >
    <div class="dev-schedule">
      <div v-if="loading" class="schedule-state" role="status">
        <Spin />
        <span>正在加载任务配置…</span>
      </div>
      <template v-else-if="!loadError && currentTask">
        <div class="schedule-task-summary">
          <div class="schedule-task-identity">
            <TaskTypeIcon
              :task-type="currentTask.taskType"
              :db-type="currentTask.dbType"
              :size="24"
            />
            <div>
              <h2>{{ currentTask.name || '未命名任务' }}</h2>
              <span class="schedule-task-id">
                任务编号 {{ currentTask.id }}
              </span>
            </div>
            <Tag :color="taskReady ? 'success' : 'warning'">
              {{
                taskReady
                  ? '可运行'
                  : currentTask.currentVersionId && currentTask.status !== 1
                    ? '已停用'
                    : '配置未完成'
              }}
            </Tag>
          </div>
          <div class="schedule-status-control">
            <span>定时调度</span>
            <Switch
              :checked="formData.scheduleStatus === 1"
              :disabled="
                (!canEnableSchedule && formData.scheduleStatus !== 1) ||
                saving ||
                !canSchedule()
              "
              checked-children="开启"
              un-checked-children="关闭"
              @change="(checked) => (formData.scheduleStatus = checked ? 1 : 0)"
            />
          </div>
        </div>
        <Alert
          class="schedule-notice"
          :message="
            taskReady
              ? 'DS 按当前版本的周期触发。保存调度设置后，等待 DS 同步完成。'
              : currentTask.currentVersionId && currentTask.status !== 1
                ? '任务已停用，启用任务后可开启定时调度。'
                : '任务配置未完成，完善后保存才可运行'
          "
          show-icon
          type="info"
        />
        <section class="schedule-edit-section">
          <div
            v-if="currentTask.scheduleSyncStatus"
            class="schedule-cron-help mb-3"
          >
            <Tag
              :color="
                optionColor(
                  DEV_SCHEDULE_SYNC_OPTIONS,
                  currentTask.scheduleSyncStatus,
                )
              "
            >
              {{
                optionLabel(
                  DEV_SCHEDULE_SYNC_OPTIONS,
                  currentTask.scheduleSyncStatus,
                )
              }}
            </Tag>
            {{ currentTask.scheduleSyncReason }}
          </div>
          <Alert
            v-if="currentTask.legacyCronExpression"
            class="schedule-notice"
            type="warning"
            show-icon
            :message="`原周期 ${currentTask.legacyCronExpression} 已保留，请重新配置 DS 周期后开启调度。`"
          />
          <h3 class="schedule-section-title">配置调度规则</h3>
          <Form layout="vertical" :model="formData">
            <FormItem class="schedule-cron-field" label="Cron 表达式">
              <CronTab
                v-if="hasAccessByCodes(['dataforge:dev-task:save'])"
                :model-value="formData.cronExpression ?? ''"
                :disabled="saving"
                placeholder="请输入表达式，或使用右侧生成器配置周期"
                week-mode="quartz"
                @update:model-value="
                  (value) => (formData.cronExpression = value)
                "
              >
                <template #preview="{ expression, compact }">
                  <CronPreview
                    :expression="expression"
                    :compact="compact"
                    :active="visible"
                  />
                </template>
              </CronTab>
              <code v-else class="schedule-readonly-cron">
                {{ formData.cronExpression || '未配置调度周期' }}
              </code>
              <div class="schedule-cron-help">
                使用 DS Quartz 表达式：星期日为 1，星期六为
                7，可选年份。调度时区为 Asia/Shanghai。
              </div>
            </FormItem>
          </Form>
        </section>
        <CronPreview :expression="formData.cronExpression" :active="visible" />
      </template>
    </div>
    <template #footer>
      <div class="schedule-footer">
        <Button :disabled="!currentTask || saving" @click="reset">
<template #icon><IconifyIcon icon="lucide:rotate-ccw" /></template>
          恢复原配置
        </Button>
        <div>
          <Button :disabled="saving" @click="close">关闭</Button>
          <Button
            class="schedule-save"
            type="primary"
            :loading="saving"
            :disabled="loading || !canSchedule()"
            @click="handleOk"
          >
            保存调度设置
          </Button>
        </div>
      </div>
    </template>
  </Modal>
</template>

<style scoped>
.dev-schedule {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding-top: 8px;
}

.schedule-task-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  background: hsl(var(--muted) / 35%);
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.schedule-task-identity {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  min-width: 0;
}

.schedule-task-identity h2 {
  margin: 0 0 3px;
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.schedule-task-id {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.schedule-status-control {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: center;
  font-size: 13px;
}

.schedule-notice {
  font-size: 12px;
}

.schedule-edit-section {
  padding: 16px 18px;
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.schedule-section-title {
  padding-left: 10px;
  margin: 0 0 16px;
  font-size: 13px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.schedule-cron-field {
  margin-bottom: 0;
}

.schedule-cron-help {
  margin-top: 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.schedule-readonly-cron {
  display: block;
  padding: 8px 12px;
  font-family: Consolas, SFMono-Regular, monospace;
  font-size: 13px;
  background: hsl(var(--muted) / 35%);
  border: 1px solid hsl(var(--border));
  border-radius: 3px;
}

.schedule-state {
  display: flex;
  flex-direction: column;
  gap: 18px;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  color: hsl(var(--muted-foreground));
}

.schedule-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.schedule-save {
  margin-left: 10px;
}

@media (width <= 760px) {
  .schedule-task-summary,
  .schedule-edit-section {
    padding: 14px 12px;
  }
}
</style>
