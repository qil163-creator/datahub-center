<script setup lang="ts">
import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { computed, reactive, ref, watch } from 'vue';

import {
  Button,
  Input,
  InputNumber,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import {
  createIntegration,
  updateIntegration,
} from '#/api/dataforge/integration';
import { CronTab } from '#/components/cron-tab';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  categoryTree,
  defaultTask,
  priorityOptions,
  saveTask,
  strategyOptions,
} from '../model';

const props = defineProps<{
  categories: Api.Category[];
  mode: Api.Mode;
  open: boolean;
  owners: Api.Owner[];
  task?: Api.Task;
}>();

const emit = defineEmits<{
  configure: [id: Api.Id];
  saved: [];
  'update:open': [value: boolean];
}>();

const referenceAssets = '/assets/qdata-integration';

const form = ref<Api.SaveTask>(defaultTask(props.mode));
const busy = ref(false);
const errors = reactive({
  categoryId: '',
  name: '',
  ownerId: '',
  cronExpression: '',
  engine: '',
});
const request = useObjectRequest(
  () => `${props.open}/${props.mode}/${props.task?.id ?? 'new'}`,
);
const tree = computed(() => categoryTree(props.categories, true));
const owners = computed(() =>
  props.owners.map((owner) => ({ value: String(owner.id), label: owner.name })),
);
const contactNumber = computed(
  () =>
    props.owners.find(
      (owner) => String(owner.id) === String(form.value.ownerId),
    )?.contactNumber ??
    (String(form.value.ownerId) === String(props.task?.ownerId)
      ? props.task?.contactNumber
      : '') ??
    '',
);
const engines = computed<
  {
    badge: string;
    description: string;
    image: string;
    name: string;
    value: Api.Engine;
  }[]
>(() =>
  props.mode === 'REALTIME'
    ? [
        {
          value: 'FLINK',
          name: 'Flink',
          badge: '流处理',
          image: 'flink.svg',
          description: '适合处理实时流计算任务。',
        },
      ]
    : [
        {
          value: 'DATAX',
          name: 'DataX',
          badge: '轻量级',
          image: 'datax.png',
          description:
            '轻量级数据同步引擎，单机运行，部署简单，适合关联数据抽取与导入场景。',
        },
        {
          value: 'SPARK',
          name: 'Spark',
          badge: '分布式',
          image: 'spark.svg',
          description:
            '适合批处理、大规模数据计算。若为每日全量同步，可选“SPARK”。',
        },
        {
          value: 'FLINK',
          name: 'Flink',
          badge: '批处理',
          image: 'flink.svg',
          description: '适合处理大规模离线数据与定时批处理任务。',
        },
      ],
);
interface EngineField {
  key: keyof Api.EngineConfig;
  label: string;
  help: string;
  type?: 'text';
  unit?: string;
  min?: number;
}
const engineFields = computed<EngineField[]>(() => {
  if (form.value.engine === 'DATAX')
    return [
      {
        key: 'jvmInitialMemory',
        label: 'JVM初始内存',
        help: 'DataX 进程启动时申请的 JVM 堆内存',
        unit: 'MB',
        min: 64,
      },
      {
        key: 'jvmMaximumMemory',
        label: 'JVM最大内存',
        help: 'DataX 进程可使用的 JVM 最大堆内存',
        unit: 'MB',
        min: 64,
      },
      {
        key: 'channel',
        label: 'Channel并发数',
        help: 'Reader 与 Writer 同时运行的数据传输通道数',
        min: 1,
      },
      {
        key: 'byteSpeed',
        label: '字节限速',
        help: '每秒最大传输字节数，0 表示不限速',
      },
      {
        key: 'recordSpeed',
        label: '记录限速',
        help: '每秒最大传输记录数，0 表示不限速',
      },
      {
        key: 'errorLimit',
        label: '脏数据上限',
        help: '允许的最大脏数据条数，超过后任务失败',
      },
    ];
  if (form.value.engine === 'SPARK')
    return [
      {
        key: 'driverCores',
        label: 'Driver核心数',
        help: '定义任务节点的执行组织方式',
        min: 1,
      },
      {
        key: 'driverMemory',
        label: 'Driver内存数',
        help: '单个 Spark Executor 的内存大小',
        type: 'text',
      },
      {
        key: 'executorCount',
        label: 'Executor数量',
        help: 'Spark Executor 的进程数量',
        min: 1,
      },
      {
        key: 'executorMemory',
        label: 'Executor内存数',
        help: '单个 Spark Executor 的内存大小',
        type: 'text',
      },
      {
        key: 'executorCores',
        label: 'Executor核心数',
        help: '单个 Executor 使用的 CPU 核心数',
        min: 1,
      },
      {
        key: 'yarnQueue',
        label: 'Yarn队列',
        help: 'Spark 作业提交使用的 Yarn 资源队列',
        type: 'text',
      },
    ];
  return [
    {
      key: 'jobManagerMemory',
      label: 'JobManager内存数',
      help: '主控节点内存大小，如 1G、2G',
      type: 'text',
    },
    {
      key: 'taskManagerMemory',
      label: 'TaskManager内存数',
      help: '工作节点内存大小，如 2G、4G',
      type: 'text',
    },
    {
      key: 'slots',
      label: 'Slot数量',
      help: '单节点任务槽数，建议设为CPU核数',
      min: 1,
    },
    {
      key: 'taskManagerCount',
      label: 'TaskManager数量',
      help: '运行任务的工作节点总数',
      min: 1,
    },
    {
      key: 'parallelism',
      label: '并行度',
      help: '全局并发数，建议 ≤ 节点数×Slot数',
      min: 1,
    },
    {
      key: 'yarnQueue',
      label: 'Yarn队列',
      help: 'Flink使用的Yarn队列，默认default',
      type: 'text',
    },
  ];
});
function numberValue(key: keyof Api.EngineConfig) {
  const value = form.value.engineConfig[key];
  return typeof value === 'number' ? value : undefined;
}
function textValue(key: keyof Api.EngineConfig) {
  const value = form.value.engineConfig[key];
  return typeof value === 'string' ? value : '';
}
function setEngineValue(
  key: keyof Api.EngineConfig,
  value: null | number | string,
) {
  Object.assign(form.value.engineConfig, { [key]: value ?? 0 });
}
function ownerChange(value: Api.Id) {
  form.value.ownerId = value;
}
watch(
  () => [props.open, props.mode, props.task],
  () => {
    if (!props.open) return;
    form.value = props.task ? saveTask(props.task) : defaultTask(props.mode);
    busy.value = false;
    Object.assign(errors, {
      categoryId: '',
      name: '',
      ownerId: '',
      cronExpression: '',
      engine: '',
    });
  },
  { immediate: true },
);
function close() {
  if (!busy.value) emit('update:open', false);
}
async function submit(configure = false) {
  errors.engine =
    props.mode === 'REALTIME' && form.value.engine !== 'FLINK'
      ? '实时任务只能使用Flink执行引擎'
      : '';
  if (form.value.mode !== props.mode)
    errors.engine = '任务模式与当前页面不一致，请重新打开任务';
  errors.categoryId = form.value.categoryId ? '' : '请选择任务类目';
  errors.name = form.value.name.trim() ? '' : '请输入任务名称';
  errors.ownerId = form.value.ownerId ? '' : '请选择责任人';
  errors.cronExpression =
    props.mode === 'OFFLINE' && !form.value.cronExpression?.trim()
      ? '请配置调度周期'
      : '';
  if (Object.values(errors).some(Boolean) || busy.value) return;
  const current = request.begin();
  busy.value = true;
  try {
    const data = {
      ...form.value,
      name: form.value.name.trim(),
      engineConfig: { ...form.value.engineConfig },
    };
    const id =
      data.id === undefined
        ? await createIntegration(data)
        : (await updateIntegration(data), data.id);
    if (!current()) return;
    message.success('保存成功');
    emit('saved');
    emit('update:open', false);
    if (configure) emit('configure', id);
  } finally {
    if (current()) busy.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open"
    :title="task ? '编辑任务' : '新增任务'"
    width="60vw"
    wrap-class-name="qi-modal qi-task-modal"
    :mask-closable="false"
    :keyboard="!busy"
    :closable="!busy"
    @cancel="close"
  >
    <form class="qi-task-form" @submit.prevent="submit()">
      <section class="qi-form-section">
        <h3>基本信息</h3>
        <div class="qi-form-grid">
          <div
            class="qi-form-field"
            :class="{ 'qi-invalid': errors.categoryId }"
          >
            <label><em>*</em>任务类目</label>
            <div>
              <TreeSelect
                v-model:value="form.categoryId"
                :tree-data="tree"
                placeholder="请选择任务类目"
                tree-default-expand-all
                :status="errors.categoryId ? 'error' : undefined"
                aria-label="任务类目"
              /><small v-if="errors.categoryId" class="qi-error">{{
                errors.categoryId
              }}</small>
            </div>
          </div>
          <div class="qi-form-field" :class="{ 'qi-invalid': errors.name }">
            <label><em>*</em>任务名称</label>
            <div>
              <Input
                v-model:value="form.name"
                :maxlength="128"
                :placeholder="
                  mode === 'REALTIME'
                    ? '建议格式：业务域_实时源端到目标端_表名'
                    : '建议格式：业务域_源端到目标端_表名'
                "
                :status="errors.name ? 'error' : undefined"
                aria-label="任务名称"
              /><small v-if="errors.name" class="qi-error">{{
                errors.name
              }}</small>
            </div>
          </div>
          <div class="qi-form-field">
            <label><em>*</em>责任人</label>
            <div>
              <Select
                :value="form.ownerId || undefined"
                :options="owners"
                placeholder="请选择责任人"
                :status="errors.ownerId ? 'error' : undefined"
                aria-label="责任人"
                @change="(value) => ownerChange(value as Api.Id)"
              /><small v-if="errors.ownerId" class="qi-error">{{
                errors.ownerId
              }}</small>
            </div>
          </div>
          <div class="qi-form-field">
            <label>联系电话</label><Input
              :value="contactNumber"
              disabled
              placeholder="请输入联系电话"
              aria-label="联系电话"
            />
          </div>
          <div class="qi-form-field qi-field-full">
            <label><em>*</em>任务状态</label><RadioGroup v-model:value="form.status">
              <Radio :value="0">未上线</Radio><Radio :value="1" disabled>已上线</Radio>
            </RadioGroup>
          </div>
          <div class="qi-form-field qi-field-full">
            <label>描述</label><TextArea
              v-model:value="form.description"
              placeholder="请输入描述"
              :maxlength="256"
              show-count
              :rows="4"
              aria-label="描述"
            />
          </div>
        </div>
      </section>
      <section class="qi-form-section">
        <h3>
          执行设置
          <span>选择任务运行的引擎，不同引擎适用于不同的计算场景与技术栈。</span>
        </h3>
        <div class="qi-form-field qi-engine-field">
          <label><em>*</em>执行引擎</label>
          <div class="qi-engine-cards">
            <button
              v-for="engine in engines"
              :key="engine.value"
              type="button"
              class="qi-engine-card"
              :class="{ selected: form.engine === engine.value }"
              :disabled="!!task"
              :aria-pressed="form.engine === engine.value"
              @click="form.engine = engine.value"
            >
              <img :src="`/assets/qdata-integration/${engine.image}`" alt="" />
              <div>
                <div>
                  {{ engine.name }} <span>{{ engine.badge }}</span>
                </div>
                <p>{{ engine.description }}</p>
              </div>
              <i v-if="form.engine === engine.value"></i>
            </button>
          </div>
        </div>
        <div v-if="errors.engine" class="qi-field-error" role="alert">
          {{ errors.engine }}
        </div>
        <div class="qi-form-grid qi-engine-fields">
          <div
            v-for="field in engineFields"
            :key="field.key"
            class="qi-form-field"
          >
            <label>{{ field.label }}</label>
            <div>
              <div class="qi-unit-input">
                <Input
                  v-if="field.type === 'text'"
                  :value="textValue(field.key)"
                  :aria-label="field.label"
                  :placeholder="
                    field.key === 'yarnQueue' ? '请输入Yarn队列(选填)' : ''
                  "
                  @update:value="(value) => setEngineValue(field.key, value)"
                /><InputNumber
                  v-else
                  :value="numberValue(field.key)"
                  :min="field.min ?? 0"
                  :precision="0"
                  :aria-label="field.label"
                  @update:value="(value) => setEngineValue(field.key, value)"
                /><span v-if="field.unit">{{ field.unit }}</span>
              </div>
              <small>{{ field.help }}</small>
            </div>
          </div>
        </div>
      </section>
      <section v-if="mode === 'OFFLINE'" class="qi-form-section">
        <h3>调度设置 <span>设置任务的调度周期及调度系统。</span></h3>
        <div class="qi-form-field">
          <label>调度系统</label>
          <div class="qi-engine-cards qi-scheduler-cards">
            <button type="button" class="qi-engine-card selected">
              <img :src="`${referenceAssets}/dolphin.png`" alt="" />
              <div>
                <div>DolphinScheduler <span>企业级</span></div>
                <p>企业级调度，适用于复杂工作流编排。</p>
              </div>
              <i></i>
</button><button type="button" class="qi-engine-card" disabled>
              <img :src="`${referenceAssets}/quartz.png`" alt="" />
              <div>
                <div>Quartz <span>轻量级</span></div>
                <p>轻量级调度，适用于简单定时任务。</p>
              </div>
            </button>
          </div>
        </div>
        <div class="qi-form-grid">
          <div class="qi-form-field">
            <label><em>*</em>调度周期</label>
            <div>
              <CronTab
                v-model="form.cronExpression"
                week-mode="quartz"
                aria-label="调度周期"
              /><small>{{
                errors.cronExpression || '定义任务自动触发的时间和频率'
              }}</small>
            </div>
          </div>
          <div class="qi-form-field">
            <label>Worker分组</label>
            <div>
              <Input v-model:value="form.workerGroup" disabled /><small>指定该任务运行的 Worker 组，用于资源隔离和负载均衡</small>
            </div>
          </div>
        </div>
      </section>
      <section class="qi-form-section">
        <h3>运行策略 <span>配置任务运行时的容错策略。</span></h3>
        <div class="qi-form-grid">
          <div class="qi-form-field">
            <label>任务优先级</label>
            <div>
              <Select
                v-model:value="form.priority"
                :options="priorityOptions"
                aria-label="任务优先级"
              /><small>用于资源竞争时确定任务调度顺序，优先级越高越优先执行</small>
            </div>
          </div>
          <div class="qi-form-field">
            <label>失败重试次数</label>
            <div>
              <div class="qi-unit-input">
                <InputNumber
                  v-model:value="form.retryCount"
                  :min="0"
                  :max="100"
                  :precision="0"
                  aria-label="失败重试次数"
                /><span>次</span>
              </div>
              <small>定义任务失败后的最大自动重试次数</small>
            </div>
          </div>
          <div class="qi-form-field">
            <label>失败重试间隔</label>
            <div>
              <div class="qi-unit-input">
                <InputNumber
                  v-model:value="form.retryInterval"
                  :min="0"
                  :precision="0"
                  aria-label="失败重试间隔"
                /><span>分</span>
              </div>
              <small>定义相邻两次失败重试的间隔分钟数</small>
            </div>
          </div>
          <div class="qi-form-field">
            <label>延迟执行时间</label>
            <div>
              <div class="qi-unit-input">
                <InputNumber
                  v-model:value="form.delayMinutes"
                  :min="0"
                  :precision="0"
                  aria-label="延迟执行时间"
                /><span>分</span>
              </div>
              <small>定义调度触发后的延迟执行分钟数</small>
            </div>
          </div>
          <div class="qi-form-field">
            <label><em>*</em>执行策略</label>
            <div>
              <Select
                v-model:value="form.executionStrategy"
                :options="strategyOptions"
                aria-label="执行策略"
              /><small>定义任务节点的执行组织方式</small>
            </div>
          </div>
        </div>
      </section>
    </form>
    <template #footer>
      <Button :disabled="busy" @click="close">取消</Button><Button :loading="busy" @click="submit()">仅保存</Button><Button type="primary" :loading="busy" @click="submit(true)">
        保存并配置流程
      </Button>
    </template>
  </Modal>
</template>
