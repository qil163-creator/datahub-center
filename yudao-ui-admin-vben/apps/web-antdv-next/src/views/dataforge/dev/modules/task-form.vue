<script lang="ts" setup>
import type { TaskFormModel } from '../list-model';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Spin,
  Switch,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import {
  createDevTask,
  getDevDirList,
  getDevProgramType,
  getDevTask,
  getDevTaskListByDir,
  isClusterTaskType,
  updateDevTask,
} from '#/api/dataforge/dev';
import { getSimpleUserList } from '#/api/system/user';
import { CronTab } from '#/components/cron-tab';

import {
  buildDirectorySelectTree,
  defaultExecutionConfig,
  normalizeDirectoryId,
} from '../list-model';
import { newNativeProgramConfig } from '../native-model';
import { devTaskTypeOptions, newDevelopmentTask } from '../workload-mode';
import ProgramConfigFields from './program-config-fields.vue';
import QualityTaskField from './quality-task-field.vue';
import TaskTypeIcon from './task-type-icon.vue';

const props = defineProps<{ workloadMode?: DataforgeDevApi.WorkloadMode }>();
const emit = defineEmits<{ cancelled: []; saved: [id: DataforgeDevApi.Id] }>();
const taskTypeOptions = computed(() => devTaskTypeOptions(props.workloadMode));
const schedulerIconPath = '/assets/qdata-integration/dolphin.png';

const visible = ref(false);
const saving = ref(false);
const loading = ref(false);
const loadFailed = ref(false);
const formRef = ref();
const editId = ref<DataforgeDevApi.Id>();
const accessStore = useAccessStore();

let openSeq = 0;
const dependencies = ref<{ label: string; value: string }[]>([]);
const formData = reactive<TaskFormModel>({});
const directories = ref<DataforgeDevApi.Dir[]>([]);
const dirTree = computed(() =>
  buildDirectorySelectTree(directories.value, formData.dirId),
);
const databases = ref<{ label: string; value: DataforgeDevApi.Id }[]>([]);
const users = ref<{ label: string; mobile?: string; value: string }[]>([]);
const ownerOptions = computed(() => {
  const options = [...users.value];
  if (
    formData.ownerId !== undefined &&
    !options.some((user) => user.value === String(formData.ownerId))
  ) {
    options.push({
      label: formData.ownerName || String(formData.ownerId),
      value: String(formData.ownerId),
    });
  }
  return options;
});
function changeOwner(value: unknown) {
  const owner = users.value.find((user) => user.value === String(value));
  formData.contactNumber = owner?.mobile;
}
const nativeConfigEnabled = computed({
  get: () =>
    formData.programConfig !== null && formData.programConfig !== undefined,
  set: (enabled: boolean) => {
    formData.programConfig = enabled
      ? { schemaVersion: 1, workloadMode: 'BATCH' }
      : undefined;
    if (enabled) {
      formData.databaseId = undefined;
      if (formData.executionConfig)
        formData.executionConfig = {
          ...formData.executionConfig,
          localParams: [],
        };
    }
  },
});
const isRealtimeSql = computed(
  () =>
    formData.taskType === 'SQL' &&
    formData.executionConfig?.workloadMode === 'STREAMING',
);

/** 页面归属独立于原生引擎实际运行模式；SparkSQL只执行有界批处理。 */
function applyNewTaskWorkloadMode() {
  formData.executionConfig = {
    ...defaultExecutionConfig(),
    ...formData.executionConfig,
    workloadMode: props.workloadMode ?? 'BATCH',
  };
  if (formData.taskType === 'SQL') {
    formData.programConfig = undefined;
    if (!formData.timeoutSeconds || formData.timeoutSeconds < 10)
      formData.timeoutSeconds = 60;
    return;
  }
  if (!formData.programConfig) return;
  const actualMode =
    formData.taskType === 'SPARK_SQL'
      ? 'BATCH'
      : (props.workloadMode ?? formData.programConfig.workloadMode ?? 'BATCH');
  formData.programConfig.workloadMode = actualMode;
  formData.timeoutSeconds =
    actualMode === 'STREAMING' ? 0 : formData.timeoutSeconds || 300;
}

function handleTaskTypeChange(value: unknown) {
  if (editId.value !== undefined) return;
  const option = taskTypeOptions.value.find((item) => item.value === value);
  if (!option) return;
  formData.taskType = option.value;
  if (isClusterTaskType(option.value)) formData.databaseId = undefined;
  formData.programConfig = newNativeProgramConfig(option.value);
  applyNewTaskWorkloadMode();
}

const rules = computed(() => ({
  name: [{ required: true, message: '请输入任务名称' }],
  databaseId: [
    {
      required:
        formData.taskType !== 'SHELL' && !isClusterTaskType(formData.taskType),
      message: '请选择数据源',
    },
  ],
}));

/**
 * @param id 编辑的任务编号
 * @param dirId 预选目录
 * @param preset 临时查询转任务时的预填内容
 */
async function open(
  id?: DataforgeDevApi.Id,
  dirId?: DataforgeDevApi.Id,
  preset?: Partial<DataforgeDevApi.Task>,
) {
  const seq = ++openSeq;
  editId.value = id;
  Object.keys(formData).forEach((key) => Reflect.deleteProperty(formData, key));
  formData.dirId = normalizeDirectoryId(dirId);
  formData.taskType = 'SQL';
  formData.scheduler = 'DOLPHIN_SCHEDULER';
  formData.timeoutSeconds = 300;
  formData.maxRows = 1000;
  Object.assign(formData, newDevelopmentTask(props.workloadMode));
  if (preset) {
    Object.assign(formData, preset, {
      cronExpression: preset.cronExpression ?? undefined,
    });
  }
  if (id === undefined) applyNewTaskWorkloadMode();
  visible.value = true;
  saving.value = false;
  loading.value = true;
  loadFailed.value = false;
  directories.value = [];
  dependencies.value = [];
  databases.value = [];
  users.value = [];
  try {
    const [task, dirs, tasks, databaseList, userList] = await Promise.all([
      id ? getDevTask(id) : Promise.resolve(undefined),
      getDevDirList(),
      getDevTaskListByDir(),
      getDatabaseSimpleList(),
      getSimpleUserList(),
    ]);
    if (seq !== openSeq || !visible.value) return;
    if (task) {
      Object.assign(formData, task, {
        expectedRevision: task.revision,
        cronExpression: task.cronExpression ?? undefined,
      });
    }
    formData.dirId = normalizeDirectoryId(formData.dirId);
    formData.databaseId =
      formData.databaseId === null || formData.databaseId === undefined
        ? undefined
        : String(formData.databaseId);
    formData.dependencyTaskIds = formData.dependencyTaskIds?.map(String);
    formData.ownerId =
      formData.ownerId === null || formData.ownerId === undefined
        ? undefined
        : String(formData.ownerId);
    users.value = userList.map((user) => ({
      label: user.nickname || user.username,
      value: String(user.id),
      mobile: user.mobile,
    }));
    if (formData.programConfig) {
      formData.programConfig.artifactFileId =
        formData.programConfig.artifactFileId === undefined ||
        formData.programConfig.artifactFileId === null
          ? undefined
          : String(formData.programConfig.artifactFileId);
      formData.programConfig.dependencyFileIds =
        formData.programConfig.dependencyFileIds?.map(String);
    }
    directories.value = dirs;
    dependencies.value = tasks
      .filter((item) => String(item.id) !== String(editId.value))
      .map((item) => ({
        label: item.name ?? String(item.id),
        value: String(item.id),
      }));
    databases.value = databaseList.map((item) => ({
      label: item.name ?? String(item.id),
      value: String(item.id),
    }));
  } catch {
    if (seq === openSeq) loadFailed.value = true;
  } finally {
    if (seq === openSeq) loading.value = false;
  }
}

async function handleOk() {
  if (saving.value || loading.value || loadFailed.value) return;
  const seq = openSeq;
  const id = editId.value;
  saving.value = true;
  try {
    await formRef.value?.validate();
    if (seq !== openSeq || !visible.value) return;
    let taskId: DataforgeDevApi.Id;
    if (id) {
      await updateDevTask({ ...formData, id, scheduler: 'DOLPHIN_SCHEDULER' });
      taskId = id;
    } else {
      taskId = await createDevTask({
        ...formData,
        scheduler: 'DOLPHIN_SCHEDULER',
      });
    }
    if (seq !== openSeq || !visible.value) return;
    message.success(id ? '修改成功' : '新增成功');
    visible.value = false;
    emit('saved', taskId);
  } finally {
    if (seq === openSeq) saving.value = false;
  }
}

defineExpose({ open });
watch(
  () => [accessStore.accessToken, accessStore.refreshToken],
  () => {
    ++openSeq;
    visible.value = false;
    saving.value = false;
    loading.value = false;
  },
);
onBeforeUnmount(() => {
  ++openSeq;
});
</script>

<template>
  <Modal
    v-model:open="visible"
    :confirm-loading="saving"
    :title="editId ? '修改配置' : '新增任务'"
    width="60vw"
    class="dev-reference-config-modal"
    :style="{
      minWidth: 'min(900px, calc(100vw - 32px))',
      maxWidth: 'calc(100vw - 32px)',
    }"
    :styles="{
      body: { maxHeight: '74vh', overflowY: 'auto', padding: '28px 40px' },
    }"
    :mask-closable="!saving"
    :ok-button-props="{ disabled: loading || loadFailed }"
    destroy-on-hidden
    ok-text="保存"
    cancel-text="关闭"
    @cancel="
      ++openSeq;
      loading = false;
      emit('cancelled');
    "
    @ok="handleOk"
  >
    <Spin :spinning="loading">

      <Form
        v-if="!loadFailed"
        ref="formRef"
        :label-col="{ style: { width: '132px' } }"
        :model="formData"
        :rules="rules"
        :wrapper-col="{ style: { flex: '1 1 0' } }"
        :disabled="loading || saving"
        label-wrap
        class="dev-task-form"
      >
        <div class="dev-task-form-grid">
          <h4 class="dev-task-form-section dev-task-form-full">基本信息</h4>
          <FormItem label="数据开发类目" name="dirId">
            <TreeSelect
              v-model:value="formData.dirId"
              :field-names="{
                children: 'children',
                label: 'label',
                value: 'value',
              }"
              :tree-data="dirTree as any"
              tree-default-expand-all
            />
          </FormItem>
          <FormItem label="任务名称" name="name">
            <Input
              v-model:value="formData.name"
              placeholder="建议格式：业务域_源到目标_指标名_全量或增量_周期"
            />
          </FormItem>
          <FormItem label="责任人" name="ownerId">
            <Select
              v-model:value="formData.ownerId"
              :options="ownerOptions"
              placeholder="请选择责任人"
              show-search
              option-filter-prop="label"
              allow-clear
              @change="changeOwner"
            />
          </FormItem>
          <FormItem label="联系电话" name="contactNumber">
            <Input
              v-model:value="formData.contactNumber"
              disabled
              placeholder="责任人联系电话，随责任人自动带出"
            />
          </FormItem>
          <FormItem label="数据连接类型" name="taskType">
            <Select
              v-model:value="formData.taskType"
              :disabled="!!editId"
              :options="taskTypeOptions"
              @change="handleTaskTypeChange"
            >
              <template #prefix>
                <TaskTypeIcon
                  :task-type="formData.taskType"
                  :engine="formData.engine"
                  :db-type="formData.dbType"
                  :size="18"
                />
              </template>
            </Select>
          </FormItem>
          <FormItem label="执行策略">
            <Select
              value="SERIAL"
              :options="[{ label: '串行', value: 'SERIAL' }]"
              disabled
            />
            <div class="dev-task-form-help">定义任务节点的执行组织方式</div>
          </FormItem>
          <FormItem label="任务状态" class="dev-task-form-full">
            <div class="dev-task-form-status" aria-label="任务状态">
              <span :class="{ selected: formData.status !== 1 }">未上线</span>
              <span :class="{ selected: formData.status === 1 }">已上线</span>
            </div>
          </FormItem>
          <FormItem label="描述" name="description" class="dev-task-form-full">
            <TextArea
              v-model:value="formData.description"
              :rows="4"
              :maxlength="500"
              show-count
              placeholder="请输入描述"
            />
          </FormItem>
          <h4 class="dev-task-form-section dev-task-form-full">
            调度设置 <small>设置任务的调度周期及调度系统。</small>
          </h4>
          <FormItem label="调度系统" class="dev-task-form-full">
            <div
              class="dev-task-scheduler-card"
              aria-label="DolphinScheduler 调度系统"
            >
              <img :src="schedulerIconPath" alt="DolphinScheduler" />
              <div>
                <div>DolphinScheduler <span>企业级</span></div>
                <p>企业级调度，适用于复杂工作流编排。</p>
              </div>
              <i aria-hidden="true"></i>
            </div>
          </FormItem>
          <FormItem
            label="调度周期"
            name="cronExpression"
            class="dev-task-form-full"
          >
            <CronTab
              :model-value="formData.cronExpression ?? ''"
              week-mode="quartz"
              placeholder="请配置调度周期"
              @update:model-value="(value) => (formData.cronExpression = value)"
            />
            <div class="dev-task-form-help">定义任务自动触发的时间和频率</div>
          </FormItem>
          <h4 class="dev-task-form-section dev-task-form-full">运行配置</h4>
          <Alert
            v-if="isRealtimeSql"
            class="dev-task-form-full mb-4"
            type="info"
            show-icon
            message="该 SQL 在实时任务列表管理，每次运行执行一次，并按设置的超时结束。"
          />
          <div class="dev-task-form-full">
            <QualityTaskField
              v-model:quality-task-id="formData.qualityTaskId"
            />
          </div>
          <FormItem label="依赖任务" class="dev-task-form-full">
            <Select
              v-model:value="formData.dependencyTaskIds"
              mode="multiple"
              :options="dependencies"
              placeholder="同业务日期需先成功的任务"
              allow-clear
            />
          </FormItem>
          <FormItem
            v-if="
              !isClusterTaskType(formData.taskType) &&
              formData.taskType !== 'SHELL' &&
              getDevProgramType(formData.taskType) === 'SQL'
            "
            label="数据源"
            name="databaseId"
          >
            <Select
              v-model:value="formData.databaseId"
              :options="databases"
              placeholder="请选择数据源"
              allow-clear
              show-search
            />
          </FormItem>
          <template v-if="isClusterTaskType(formData.taskType)">
            <FormItem
              v-if="
                editId &&
                getDevProgramType(formData.taskType) === 'SQL' &&
                !nativeConfigEnabled
              "
              label="原生引擎配置"
              class="dev-task-form-full"
            >
              <Switch
                v-model:checked="nativeConfigEnabled"
                checked-children="原生执行"
                un-checked-children="原有 SQL 通道"
              />
              <p class="text-muted-foreground mt-2 text-xs">
                切换后清空平台数据源与 JDBC 绑定参数；请将脚本适配为原生
                SQL，连接器由受管部署提供。
              </p>
            </FormItem>
            <Alert
              v-if="
                nativeConfigEnabled ||
                getDevProgramType(formData.taskType) !== 'SQL'
              "
              class="mb-4"
              style="grid-column: 1 / -1"
              type="warning"
              show-icon
              :message="
                formData.executionCapabilityReason ||
                '任务配置未完成，完善后保存才可运行'
              "
            />
            <div v-if="formData.programConfig" class="dev-task-form-full">
              <ProgramConfigFields
                v-if="formData.programConfig"
                v-model="formData.programConfig"
                :task-type="formData.taskType"
                :workload-mode="
                  formData.taskType === 'SPARK_SQL'
                    ? 'BATCH'
                    : workloadMode
                      ? (formData.programConfig.workloadMode ?? workloadMode)
                      : undefined
                "
                layout="grid"
                @workload-change="
                  (mode) =>
                    (formData.timeoutSeconds =
                      mode === 'STREAMING' ? 0 : formData.timeoutSeconds || 300)
                "
              />
            </div>
          </template>
          <FormItem label="执行超时(秒)" name="timeoutSeconds">
            <InputNumber
              v-model:value="formData.timeoutSeconds"
              :max="3600"
              :min="
                formData.programConfig?.workloadMode === 'STREAMING' ? 0 : 10
              "
              class="w-full"
            />
          </FormItem>
          <FormItem
            v-if="!nativeConfigEnabled"
            label="最大返回行数"
            name="maxRows"
          >
            <InputNumber
              v-model:value="formData.maxRows"
              :max="5000"
              :min="10"
              class="w-full"
            />
          </FormItem>
        </div>
      </Form>
    </Spin>
  </Modal>
</template>

<style scoped>
.dev-task-form {
  font-size: 14px;
}

.dev-task-form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 42px;
}

.dev-task-form-full {
  grid-column: 1 / -1;
}

.dev-task-form-section {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 0;
  margin: 4px 0 28px;
  font-size: 16px;
  font-weight: 500;
  color: hsl(var(--foreground));
  border-radius: 2px;
}

.dev-task-form-section::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 3px;
}

.dev-task-form-section small {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
  color: hsl(var(--muted-foreground));
}

.dev-task-form-help {
  margin-top: 3px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.dev-task-form-status {
  display: flex;
  gap: 28px;
  align-items: center;
  min-height: 32px;
  color: hsl(var(--muted-foreground));
}

.dev-task-form-status span {
  display: inline-flex;
  gap: 8px;
  align-items: center;
}

.dev-task-form-status span::before {
  width: 14px;
  height: 14px;
  content: '';
  border: 1px solid hsl(var(--border));
  border-radius: 50%;
}

.dev-task-form-status .selected::before {
  background: hsl(var(--muted-foreground));
  border: 4px solid hsl(var(--muted));
}

.dev-task-scheduler-card {
  display: flex;
  gap: 12px;
  align-items: center;
  max-width: 420px;
  padding: 14px;
  background: hsl(var(--primary) / 2%);
  border: 1px solid #2d63ff;
  border-radius: 2px;
}

.dev-task-scheduler-card img {
  width: 38px;
  height: 44px;
  object-fit: contain;
}

.dev-task-scheduler-card span {
  padding: 2px 4px;
  margin-left: 8px;
  font-size: 11px;
  color: #2d63ff;
  background: hsl(var(--primary) / 5%);
}

.dev-task-scheduler-card p {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: hsl(var(--muted-foreground));
}

.dev-task-scheduler-card i {
  flex: 0 0 14px;
  align-self: flex-start;
  height: 14px;
  border: 4px solid #2d63ff;
  border-radius: 50%;
}

.dev-task-form :deep(.ant-form-item) {
  margin-bottom: 18px;
}

.dev-task-form :deep(.ant-form-item-label > label) {
  font-size: 14px;
}

.dev-task-form :deep(.ant-input),
.dev-task-form :deep(.ant-select-selector),
.dev-task-form :deep(.ant-input-number) {
  border-radius: 2px;
}

@media (max-width: 760px) {
  .dev-task-form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
