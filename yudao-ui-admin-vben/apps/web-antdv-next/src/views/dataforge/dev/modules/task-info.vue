<script lang="ts" setup>
import type { FormInstance } from 'antdv-next';

import type { TaskFormModel } from '../list-model';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  RadioGroup,
  Select,
  Spin,
  Tag,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';
import {
  createDevTask,
  getDevDirList,
  getDevProgramType,
  getDevTask,
  isClusterTaskType,
  isNativeProgramNotReady,
  updateDevTask,
} from '#/api/dataforge/dev';
import { getSimpleUserList } from '#/api/system/user';
import { CronTab } from '#/components/cron-tab';

import {
  buildDirectorySelectTree,
  buildProcedureTemplate,
  defaultExecutionConfig,
  normalizeDirectoryId,
  resolveDirectoryName,
} from '../list-model';
import { nativeTaskTemplates, newNativeProgramConfig } from '../native-model';
import { DEV_REFERENCE_ASSETS } from '../reference-assets';
import { devTaskTypeOptions, newDevelopmentTask } from '../workload-mode';
import CronPreview from './cron-preview.vue';
import ProgramConfigFields from './program-config-fields.vue';
import TaskDetailView from './task-detail-view.vue';
import TaskTypeIcon from './task-type-icon.vue';

const props = defineProps<{ workloadMode?: DataforgeDevApi.WorkloadMode }>();
const emit = defineEmits<{
  saved: [id: DataforgeDevApi.Id, configure: boolean];
}>();
const taskTypes = computed(() => devTaskTypeOptions(props.workloadMode));
const visible = ref(false);
const readonly = ref(false);
const loading = ref(false);
const saving = ref(false);
const failed = ref(false);
const accessStore = useAccessStore();
const formRef = ref<FormInstance>();
const advancedFields = ref<HTMLDetailsElement>();
const form = reactive<TaskFormModel>({});
const dirs = ref<DataforgeDevApi.Dir[]>([]);
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const productTypes = ref<FoundationDatabaseTypeApi.DatabaseType[]>([]);
const users = ref<{ id: string; label: string; mobile?: string }[]>([]);
const selectedTemplate = ref('');
const templatesVisible = ref(false);
let generation = 0;
const directoryTree = computed(() =>
  buildDirectorySelectTree(dirs.value, form.dirId),
);
const databaseTypes = computed(() => {
  const options = new Map<string, string>();
  productTypes.value.forEach((product) => {
    const code = (product.productId || product.typeCode)?.toUpperCase();
    if (code) options.set(code, product.typeName || code);
  });
  databases.value.forEach((db) => {
    const code = databaseProduct(db);
    if (code)
      options.set(
        code.toUpperCase(),
        db.databaseTypeName ?? code.toUpperCase(),
      );
  });
  if (form.dbType) options.set(form.dbType, form.dbType);
  return [...options].map(([value, label]) => ({ value, label }));
});
const databaseOptions = computed(() =>
  databases.value
    .filter((db) => !form.dbType || databaseProduct(db) === form.dbType)
    .map((db) => ({ label: db.name ?? String(db.id), value: String(db.id) })),
);
const connectionTypeOptions = computed(() => [
  ...databaseTypes.value.map((option) => ({
    label: option.label,
    value: `DB:${option.value}`,
  })),
  ...taskTypes.value
    .filter((option) => option.value !== 'SQL' && option.value !== 'PROCEDURE')
    .map((option) => ({ label: option.label, value: `TASK:${option.value}` })),
]);
const connectionTypeValue = computed(() => {
  if (form.taskType === 'SQL' || form.taskType === 'PROCEDURE') {
    return form.dbType ? `DB:${form.dbType}` : undefined;
  }
  return `TASK:${form.taskType}`;
});
function selectConnectionType(value: unknown) {
  if (typeof value !== 'string' || form.id !== undefined) return;
  form.databaseId = undefined;
  if (value.startsWith('DB:')) {
    form.dbType = value.slice(3);
    form.taskType = 'SQL';
  } else {
    const option = taskTypes.value.find(
      (item) => `TASK:${item.value}` === value,
    );
    if (option) {
      form.taskType = option.value;
      form.dbType = undefined;
    }
  }
}
const ownerOptions = computed(() =>
  users.value.map((user) => ({ label: user.label, value: user.id })),
);
const directoryName = computed(() =>
  resolveDirectoryName(form.dirId, dirs.value),
);
const ownerName = computed(
  () =>
    users.value.find((user) => user.id === String(form.ownerId))?.label ||
    form.ownerName ||
    (form.ownerId === undefined || form.ownerId === null
      ? '-'
      : String(form.ownerId)),
);
const creatorName = computed(
  () =>
    users.value.find((user) => user.id === form.creator)?.label || form.creator,
);
const databaseName = computed(
  () =>
    databases.value.find(
      (database) => String(database.id) === String(form.databaseId),
    )?.name,
);
const rules = computed(() => ({
  name: [{ required: true, message: '请输入任务名称' }],
  dirId: [{ required: true, message: '请选择数据开发类目' }],
  ownerId: [{ required: true, message: '请选择责任人' }],
  dbType: [
    {
      required: form.taskType !== 'SHELL' && !isClusterTaskType(form.taskType),
      message: '请选择数据连接类型',
    },
  ],
}));
const templates = computed(() => {
  if (form.taskType === 'SHELL') return [];
  if (isClusterTaskType(form.taskType))
    return nativeTaskTemplates(form.taskType);
  const suffix = (form.dbType || 'SQL').toLowerCase();
  const procedure = buildProcedureTemplate(form.dbType);
  return [
    {
      key: 'query',
      disabled: false,
      name: `query.${suffix}`,
      description: '查询语句 · 保存后运行',
      content: 'SELECT 1 AS result;',
    },
    {
      key: 'table',
      disabled: false,
      name: `create.table.${suffix}`,
      description: '建表语句 · 请配置目标表与字段',
      content:
        '-- 请修改表名和字段，并确认目标数据源\nCREATE TABLE example_data (\n  id BIGINT NOT NULL,\n  value VARCHAR(100),\n  PRIMARY KEY (id)\n);',
    },
    {
      key: 'procedure',
      name: `create.procedure.${suffix}`,
      ...procedure,
    },
  ];
});
async function open(
  id?: DataforgeDevApi.Id,
  dirId?: DataforgeDevApi.Id,
  detail = false,
) {
  const seq = ++generation;
  visible.value = true;
  readonly.value = detail;
  loading.value = true;
  saving.value = false;
  failed.value = false;
  selectedTemplate.value = '';
  templatesVisible.value = false;
  Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
  Object.assign(form, {
    dirId: normalizeDirectoryId(dirId),
    taskType: 'SQL',
    status: 0,
    scheduler: 'DOLPHIN_SCHEDULER',
    timeoutSeconds: 300,
    maxRows: 1000,
    executionConfig: defaultExecutionConfig(),
    ...newDevelopmentTask(props.workloadMode),
  });
  dirs.value = [];
  users.value = [];
  databases.value = [];
  productTypes.value = [];
  try {
    const [task, directoryList, dbList, userList, typeList] = await Promise.all(
      [
        id === undefined ? undefined : getDevTask(id),
        getDevDirList(),
        getDatabaseSimpleList(),
        getSimpleUserList(),
        getDatabaseTypeSimpleList(),
      ],
    );
    if (seq !== generation || !visible.value) return;
    dirs.value = directoryList;
    databases.value = dbList;
    productTypes.value = typeList;
    users.value = userList.map((user) => ({
      id: String(user.id),
      label: user.nickname || user.username,
      mobile: user.mobile,
    }));
    if (task)
      Object.assign(form, task, {
        expectedRevision: task.revision,
        cronExpression: task.cronExpression ?? undefined,
        dirId: normalizeDirectoryId(task.dirId),
        ownerId:
          task.ownerId === undefined || task.ownerId === null
            ? undefined
            : String(task.ownerId),
        databaseId:
          task.databaseId === null || task.databaseId === undefined
            ? undefined
            : String(task.databaseId),
        executionConfig: {
          ...defaultExecutionConfig(),
          ...task.executionConfig,
        },
      });
  } catch {
    if (seq === generation) failed.value = true;
  } finally {
    if (seq === generation) loading.value = false;
  }
}
function close() {
  ++generation;
  visible.value = false;
  saving.value = false;
  loading.value = false;
}
function selectOwner(id: unknown) {
  form.contactNumber = users.value.find((user) => user.id === id)?.mobile;
}
function databaseProduct(db: FoundationDatabaseApi.DatabaseSimple) {
  const product = productTypes.value.find(
    (item) => String(item.id) === String(db.databaseType),
  );
  return (
    db.productId ||
    product?.productId ||
    product?.typeCode
  )?.toUpperCase();
}
function selectDatabase(id: unknown) {
  const db = databases.value.find((item) => String(item.id) === id);
  if (db) form.dbType = databaseProduct(db);
}
function chooseTemplate(template: {
  content: string;
  disabled?: boolean;
  executionConfig?: DataforgeDevApi.ExecutionConfig;
  key: string;
  preserveTaskType?: boolean;
  referenceOnly?: boolean;
}) {
  if (template.disabled) return;
  selectedTemplate.value = template.key;
  if (template.referenceOnly) return;
  form.content = template.content;
  if (template.preserveTaskType) return;
  form.taskType = template.key === 'procedure' ? 'PROCEDURE' : 'SQL';
  form.executionConfig = template.executionConfig ?? {
    ...defaultExecutionConfig(),
    sqlType: template.key === 'table' ? 'NON_QUERY' : 'QUERY',
  };
}
async function save(configure = false) {
  if (saving.value || loading.value || failed.value) return;
  const seq = generation;
  saving.value = true;
  try {
    try {
      await formRef.value?.validate();
    } catch {
      if (seq === generation && visible.value && advancedFields.value) {
        advancedFields.value.open = true;
      }
      return;
    }
    if (seq !== generation || !visible.value) return;
    const id =
      form.id === undefined
        ? await createDevTask({ ...form, scheduler: 'DOLPHIN_SCHEDULER' })
        : (await updateDevTask({ ...form, scheduler: 'DOLPHIN_SCHEDULER' }),
          form.id);
    if (seq !== generation || !visible.value) return;
    message.success(form.id === undefined ? '任务已新增' : '任务已保存');
    close();
    emit('saved', id, configure);
  } finally {
    if (seq === generation) saving.value = false;
  }
}
onBeforeUnmount(close);
watch(() => [accessStore.accessToken, accessStore.refreshToken], close);
watch(
  () => form.taskType,
  (type) => {
    if (form.id !== undefined) return;
    if (isClusterTaskType(type)) form.databaseId = undefined;
    form.programConfig = newNativeProgramConfig(type);
    form.executionConfig = {
      ...form.executionConfig,
      workloadMode: props.workloadMode ?? 'BATCH',
    };
    if (type === 'SQL' && (!form.timeoutSeconds || form.timeoutSeconds < 1)) form.timeoutSeconds = 300;
    if (isClusterTaskType(type) && props.workloadMode) {
      form.programConfig = {
        ...form.programConfig,
        schemaVersion: 1,
        workloadMode: type === 'SPARK_SQL' ? 'BATCH' : props.workloadMode,
      };
      form.timeoutSeconds = form.programConfig.workloadMode === 'STREAMING' ? 0 : form.timeoutSeconds || 300;
    }
    if (
      type === 'SPARK_SQL' &&
      (!form.timeoutSeconds || form.timeoutSeconds < 1)
    )
      form.timeoutSeconds = 300;
  },
);
defineExpose({ open, close });
</script>

<template>
  <Modal
    v-model:open="visible"
    class="dev-task-info-modal"
    :title="
      readonly
        ? '数据开发详情'
        : form.id === undefined
          ? '新增任务'
          : '修改配置'
    "
    :width="readonly ? 1160 : '60vw'"
    :style="{ minWidth: 'min(900px, calc(100vw - 32px))' }"
    :styles="{
      container: { borderRadius: '1px' },
      body: {
        maxHeight: 'calc(100vh - 220px)',
        overflowY: 'auto',
        padding: '30px 40px',
      },
    }"
    destroy-on-hidden
    :mask-closable="!saving"
    @cancel="close"
  >
    <Spin :spinning="loading">

      <TaskDetailView
        v-if="!failed && readonly && !loading"
        :task="form"
        :directory-name="directoryName"
        :owner-name="ownerName"
        :creator-name="creatorName"
        :database-name="databaseName"
      />
      <Form
        v-else-if="!failed && !readonly"
        ref="formRef"
        :model="form"
        :rules="rules"
        :colon="false"
        :disabled="loading || saving"
        :label-col="{ style: { width: '146px' } }"
        :wrapper-col="{ style: { flex: '1 1 0', minWidth: 0 } }"
        label-wrap
        class="task-info-form"
      >
        <h4 class="task-section-title">基本信息</h4>
        <div class="task-info-fields">
          <FormItem label="数据开发类目" name="dirId">
            <TreeSelect
              v-model:value="form.dirId"
              :tree-data="directoryTree as any"
              tree-default-expand-all
              show-search
              tree-node-filter-prop="label"
              placeholder="请选择数据开发类目"
            />
          </FormItem>
          <FormItem label="任务名称" name="name">
            <Input
              v-model:value="form.name"
              :maxlength="100"
              placeholder="建议格式：业务域_源到目标_指标名_全量或增量_周期"
            />
          </FormItem>
          <FormItem label="责任人" name="ownerId">
            <Select
              v-model:value="form.ownerId"
              :options="ownerOptions"
              show-search
              option-filter-prop="label"
              placeholder="任务维护责任人，用于异常跟进和追溯"
              @change="selectOwner"
            />
          </FormItem>
          <FormItem label="联系电话">
            <Input
              :value="form.contactNumber"
              disabled
              placeholder="责任人联系电话，随责任人自动带出"
            />
          </FormItem>
          <FormItem
            label="数据连接类型"
            :name="isClusterTaskType(form.taskType) ? 'taskType' : 'dbType'"
          >
            <Select
              :value="connectionTypeValue"
              :options="connectionTypeOptions"
              :disabled="form.id !== undefined"
              show-search
              option-filter-prop="label"
              placeholder="请选择数据连接类型"
              @change="selectConnectionType"
            >
              <template v-if="connectionTypeValue" #prefix>
                <TaskTypeIcon
                  :task-type="form.taskType"
                  :db-type="form.dbType"
                  :size="18"
                />
              </template>
            </Select>
          </FormItem>
          <FormItem
            label="执行策略"
            required
            extra="定义任务节点的执行组织方式"
          >
            <Select
              value="SERIAL"
              :options="[{ label: '串行', value: 'SERIAL' }]"
              disabled
            />
          </FormItem>
          <FormItem label="任务状态">
            <RadioGroup
              :value="form.status ?? 0"
              :options="[
                { label: '未上线', value: 0 },
                { label: '已上线', value: 1 },
              ]"
              disabled
            />
          </FormItem>
          <FormItem label="描述" class="task-info-full">
            <TextArea
              v-model:value="form.description"
              :rows="4"
              :maxlength="500"
              placeholder="请输入描述"
              show-count
            />
          </FormItem>
        </div>
        <h4 class="task-section-title scheduler-section-title">
          调度设置<span>设置任务的调度周期及调度系统。</span>
        </h4>
        <FormItem label="调度系统" required>
          <div class="task-schedulers" role="radiogroup" aria-label="调度系统">
            <div
              class="task-scheduler selected"
              role="radio"
              aria-checked="true"
            >
              <img :src="DEV_REFERENCE_ASSETS.schedulerDolphin" alt="" />
              <div>
                <b>DolphinScheduler <Tag color="blue">企业级</Tag></b>
                <p>企业级调度，适用于复杂工作流编排。</p>
              </div>
              <span class="scheduler-radio"></span>
            </div>
            <div
              class="task-scheduler disabled"
              role="radio"
              aria-checked="false"
              aria-disabled="true"
            >
              <img :src="DEV_REFERENCE_ASSETS.schedulerQuartz" alt="" />
              <div>
                <b>Quartz <Tag color="blue">轻量级</Tag></b>
                <p>轻量级调度，适用于简单定时任务。</p>
              </div>
            </div>
          </div>
        </FormItem>
        <FormItem label="调度周期">
          <div class="task-cron">
            <CronTab
              :model-value="form.cronExpression ?? ''"
              week-mode="quartz"
              @update:model-value="(value) => (form.cronExpression = value)"
            >
              <template #preview="{ expression, compact }">
                <CronPreview :expression="expression" :compact="compact" />
              </template>
            </CronTab>
            <small>定义任务自动触发的时间和频率</small>
            <small v-if="form.legacyCronExpression">原周期 {{ form.legacyCronExpression }} 已保留，请重新配置 DS
              周期。</small>
          </div>
        </FormItem>
        <details ref="advancedFields" class="task-advanced">
          <summary>扩展配置</summary>
          <div class="task-info-fields">
            <FormItem label="任务类型">
              <Select
                v-model:value="form.taskType"
                :disabled="form.id !== undefined"
                :options="taskTypes"
              />
            </FormItem>
            <FormItem
              v-if="
                form.id !== undefined &&
                !isClusterTaskType(form.taskType) &&
                getDevProgramType(form.taskType) === 'SQL'
              "
              label="数据源连接"
            >
              <Select
                v-model:value="form.databaseId"
                :options="databaseOptions"
                allow-clear
                show-search
                option-filter-prop="label"
                placeholder="请选择数据源连接"
                @change="selectDatabase"
              />
            </FormItem>
          </div>
          <template v-if="form.programConfig">
            <Alert
              v-if="isNativeProgramNotReady(form)"
              class="mb-4"
              type="warning"
              show-icon
              :message="
                form.executionCapabilityReason ||
                '任务配置未完成，完善后保存才可运行'
              "
            />
            <ProgramConfigFields
              v-model="form.programConfig"
              :task-type="form.taskType"
              :workload-mode="
                form.taskType === 'SPARK_SQL' ? 'BATCH' : props.workloadMode
              "
              layout="grid"
              @workload-change="
                (mode) =>
                  (form.timeoutSeconds =
                    mode === 'STREAMING' ? 0 : form.timeoutSeconds || 300)
              "
            />
          </template>
        </details>
      </Form>
      <template
        v-if="
          !readonly && !failed && form.id === undefined && templates.length > 0
        "
      >
        <Button
          class="task-template-toggle"
          type="link"
          size="small"
          @click="templatesVisible = !templatesVisible"
        >
          {{ templatesVisible ? '⌄' : '›' }} 使用模板
        </Button>
        <div v-if="templatesVisible" class="task-templates">
          <button
            v-for="template in templates"
            :key="template.key"
            type="button"
            class="task-template"
            :class="{ selected: selectedTemplate === template.key }"
            :disabled="saving || loading || template.disabled"
            @click="chooseTemplate(template)"
          >
            <b>{{ template.name }}</b>
            <TaskTypeIcon
              :task-type="form.taskType"
              :db-type="form.dbType"
              :size="18"
            />
            <small>{{ template.description }}</small>
            <pre>{{ template.content }}</pre>
          </button>
        </div>
        <p
          v-if="
            templatesVisible && getDevProgramType(form.taskType) === 'PYTHON'
          "
          class="text-muted-foreground mt-2 text-xs"
        >
          Python 示例仅供制品源码参考；需将 .py
          文件登记到受管部署档案，任务执行所选制品，不执行此示例文本。
        </p>
      </template>
    </Spin>
    <template #footer>
      <Button v-if="readonly" @click="close">关闭</Button>
      <template v-else-if="form.id !== undefined">
        <Button :disabled="saving" @click="close">关闭</Button>
        <Button
          type="primary"
          :loading="saving"
          :disabled="loading || failed"
          @click="save(true)"
          >
保存
</Button>
      </template>
      <template v-else>
        <Button :disabled="saving" @click="close">取消</Button>
        <Button
          :loading="saving"
          :disabled="loading || failed"
          @click="save(false)"
          >
仅保存
</Button>
        <Button
          type="primary"
          :loading="saving"
          :disabled="loading || failed"
          @click="save(true)"
          >
保存并配置流程
</Button>
      </template>
    </template>
  </Modal>
</template>

<style scoped>
.task-info-form {
  font-size: 14px;
}

.task-section-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 28px;
  font-size: 16px;
  font-weight: 400;
}

.task-section-title::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 4px;
}

.task-section-title span {
  margin-left: 12px;
  font-size: 13px;
  color: #909399;
}

.task-info-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.task-info-form :deep(.ant-form-item-label > label) {
  font-size: 14px;
}

.task-info-form :deep(.ant-input),
.task-info-form :deep(.ant-select-selector) {
  border-radius: 1px;
}

.task-info-form :deep(.ant-form-item-extra) {
  min-height: 20px;
  padding-top: 2px;
  font-size: 12px;
  color: #909399;
}

.task-info-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 36px;
}

.task-info-full {
  grid-column: 1 / -1;
}

.scheduler-section-title {
  margin-top: 12px;
  margin-bottom: 24px;
}

.task-schedulers {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  max-width: 570px;
}

.task-scheduler {
  position: relative;
  display: flex;
  gap: 12px;
  align-items: center;
  min-height: 86px;
  padding: 14px;
  border: 1px solid hsl(var(--border));
  border-radius: 1px;
}

.task-scheduler.selected {
  background: linear-gradient(180deg, hsl(var(--background)), #f8faff);
  border-color: #2d63ff;
}

.task-scheduler.disabled {
  color: #a8abb2;
  background: hsl(var(--muted) / 35%);
  opacity: 0.55;
}

.task-scheduler > img {
  flex: none;
  width: 36px;
  height: 36px;
  object-fit: contain;
}

.task-scheduler b {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  font-size: 14px;
  font-weight: 400;
}

.task-scheduler b :deep(.ant-tag) {
  padding-inline: 4px;
  margin-inline-end: 0;
  font-size: 11px;
  line-height: 18px;
  border: 0;
}

.task-scheduler p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
}

.scheduler-radio {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 14px;
  height: 14px;
  border: 4px solid #2d63ff;
  border-radius: 50%;
}

.task-cron {
  max-width: 348px;
}

.task-cron small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #9298a2;
}

.task-advanced {
  margin: 18px 0 8px;
}

.task-advanced summary {
  width: fit-content;
  margin-bottom: 14px;
  color: #2d63ff;
  cursor: pointer;
}

.task-template-toggle {
  padding-left: 0;
  margin-block: 2px 12px;
  color: #2d63ff;
}

.task-templates {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 10px;
  background: hsl(var(--muted) / 40%);
}

.task-template {
  position: relative;
  min-width: 0;
  padding: 12px;
  text-align: left;
  cursor: pointer;
  background: hsl(var(--background));
  border: 1px solid transparent;
  border-radius: 2px;
}

.task-template > :deep(.dev-task-type-icon) {
  position: absolute;
  top: 12px;
  right: 12px;
}

.task-template:focus-visible {
  outline: 2px solid #2d63ff;
  outline-offset: 2px;
}

.task-template:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.task-template.selected {
  border-color: #2d63ff;
}

.task-template b,
.task-template small {
  display: block;
  padding-right: 20px;
}

.task-template small {
  min-height: 34px;
  margin: 6px 0;
  font-size: 11px;
  line-height: 16px;
  color: #909399;
}

.task-template pre {
  height: 100px;
  padding: 8px;
  overflow: auto;
  font-size: 10px;
  color: hsl(var(--foreground));
  background: hsl(var(--muted) / 60%);
}

@media (max-width: 760px) {
  .task-info-fields,
  .task-schedulers,
  .task-templates {
    grid-template-columns: 1fr;
  }

  .task-section-title {
    flex-wrap: wrap;
  }

  .task-section-title span {
    margin-left: 14px;
  }

  .task-info-form :deep(.ant-form-item-row) {
    flex-direction: column;
  }

  .task-info-form :deep(.ant-form-item-label) {
    flex: none !important;
    width: auto !important;
    text-align: left;
  }
}
</style>
