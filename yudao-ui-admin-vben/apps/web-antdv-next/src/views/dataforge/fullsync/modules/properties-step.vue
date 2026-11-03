<script setup lang="ts">
import type { FullSyncConfig } from '#/api/dataforge/fullsync';

import {
  Button,
  Input,
  InputNumber,
  Radio,
  RadioGroup,
  Select,
} from 'antdv-next';

import { CronTab } from '#/components/cron-tab';

const props = defineProps<{ config: FullSyncConfig }>();
const emit = defineEmits<{ 'update:config': [config: FullSyncConfig] }>();
function patch<K extends keyof FullSyncConfig>(
  key: K,
  value: FullSyncConfig[K],
) {
  emit('update:config', { ...props.config, [key]: value });
}
const priorities = [
  { label: '最高', value: 'HIGHEST' },
  { label: '高', value: 'HIGH' },
  { label: '中', value: 'MEDIUM' },
  { label: '低', value: 'LOW' },
  { label: '最低', value: 'LOWEST' },
];
const strategies = [
  { label: '并行', value: 'PARALLEL' },
  { label: '串行等待', value: 'SERIAL_WAIT' },
  { label: '串行抛弃', value: 'SERIAL_DISCARD' },
  { label: '串行优先', value: 'SERIAL_PRIORITY' },
];
function setStrategy(value: unknown) {
  if (
    typeof value === 'string' &&
    strategies.some((item) => item.value === value)
  )
    patch('executionStrategy', value);
}
function setPriority(value: unknown) {
  if (
    typeof value === 'string' &&
    priorities.some((item) => item.value === value)
  )
    patch('taskPriority', value as FullSyncConfig['taskPriority']);
}
const engines = [
  {
    value: 'SPARK',
    tag: '分布式',
    description: '适合批处理、大规模数据计算。若为每日全量同步，可选“SPARK”。',
  },
  {
    value: 'FLINK',
    tag: '分布式',
    description: '适合处理大规模离线数据与定时批处理任务。',
  },
  {
    value: 'DATAX',
    tag: '轻量级',
    description:
      '轻量级数据同步引擎，单机运行，部署简单，适合关联数据抽取与导入场景。',
  },
] as const;
const numericFields = [
  {
    key: 'retryTimes',
    label: '失败重试次数',
    unit: '次',
    hint: '定义任务失败后的最大自动重试次数',
    min: 0,
    max: 1000,
  },
  {
    key: 'retryInterval',
    label: '失败重试间隔',
    unit: '分',
    hint: '定义相邻两次失败重试的间隔分钟数',
    min: 0,
    max: 10_080,
  },
  {
    key: 'delayTime',
    label: '延迟执行时间',
    unit: '分',
    hint: '定义调度触发后的延迟执行分钟数',
    min: 0,
    max: 10_080,
  },
] as const;
const resourceFields = [
  {
    key: 'driverCores',
    label: 'Driver核心数',
    hint: '定义任务节点的执行组织方式',
    numeric: true,
  },
  {
    key: 'driverMemory',
    label: 'Driver内存数',
    hint: '单个 Spark Executor 的内存大小',
    numeric: false,
  },
  {
    key: 'executorCount',
    label: 'Executor数量',
    hint: 'Spark Executor 的进程数量',
    numeric: true,
  },
  {
    key: 'executorMemory',
    label: 'Executor内存数',
    hint: '单个 Spark Executor 的内存大小',
    numeric: false,
  },
  {
    key: 'executorCores',
    label: 'Executor核心数',
    hint: '单个 Executor 使用的 CPU 核心数',
    numeric: true,
  },
  {
    key: 'yarnQueue',
    label: 'Yarn队列',
    hint: 'Spark 作业提交使用的 Yarn 资源队列',
    numeric: false,
  },
] as const;
</script>

<template>
  <section class="fws-properties">
    <h2 class="fws-section-title">调度周期</h2>
    <p class="fws-section-description">
      设置任务的运行频率，例如每天、每小时或按自定义 Cron
      表达式执行，让任务按照计划自动触发。
    </p>
    <div class="fws-schedule-row">
      <label class="fws-required">执行方式</label>
      <RadioGroup
        :value="config.executionMethod"
        @update:value="patch('executionMethod', $event)"
      >
        <Radio value="MANUAL">手动执行</Radio>
        <Radio value="CRON">Cron表单式</Radio>
      </RadioGroup>
      <template v-if="config.executionMethod === 'CRON'">
        <label class="fws-required fws-cron-label">调度周期</label>
        <CronTab
          :model-value="config.cron || '0 0 0 * * ?'"
          class="fws-cron-input"
          modal-class="fws-cron-modal"
          :modal-width="912"
          placeholder="请配置调度周期"
          @update:model-value="patch('cron', $event)"
        >
          <template #trigger="{ open }">
            <Button type="primary" @click="open">配置</Button>
          </template>
        </CronTab>
      </template>
    </div>

    <h2 class="fws-section-title">异常处理策略</h2>
    <p class="fws-section-description">
      用于控制任务在执行异常或数据异常发生时的处理方式。
    </p>
    <div class="fws-policy-row">
      <div class="fws-policy-label">
        <strong class="fws-required">错误处理策略：</strong>
        <p>创建数据表、数据库连接异常等非数据基本错误。</p>
      </div>
      <div
        class="fws-policy-options"
        role="radiogroup"
        aria-label="错误处理策略"
      >
        <button
          v-for="option in [
            { value: true, title: '错误继续执行' },
            { value: false, title: '错误暂停任务' },
          ]"
          :key="String(option.value)"
          type="button"
          class="fws-policy-option"
          :class="{ selected: config.errorContinue === option.value }"
          role="radio"
          :aria-checked="config.errorContinue === option.value"
          @click="patch('errorContinue', option.value)"
        >
          <span class="fws-policy-heading"><img
              :src="`/assets/qdata-fullsync/policy-${config.errorContinue === option.value ? 'active' : 'normal'}.png`"
              alt=""
            />{{ option.title
            }}<span v-if="option.value" class="fws-recommend">推荐</span></span>
          <span class="fws-policy-description">记录错误，跳过当前步骤，任务继续运行。</span>
        </button>
      </div>
    </div>
    <div class="fws-policy-row">
      <div class="fws-policy-label">
        <strong class="fws-required">脏数据处理策略：</strong>
        <p>主键冲突、必填字段为空等情况。</p>
      </div>
      <div
        class="fws-policy-options"
        role="radiogroup"
        aria-label="脏数据处理策略"
      >
        <button
          v-for="option in [
            {
              value: 'IGNORE',
              title: '忽略',
              description: '跳过脏数据行，不影响任务继续执行。',
            },
            {
              value: 'STOP',
              title: '暂停任务',
              description: '出现脏数据时立即中断整个任务。',
            },
            {
              value: 'CONTINUE',
              title: '继续执行',
              description: '记录脏数据，但任务不受影响继续执行。',
            },
          ] as const"
          :key="option.value"
          type="button"
          class="fws-policy-option"
          :class="{ selected: config.dirtyDataStrategy === option.value }"
          role="radio"
          :aria-checked="config.dirtyDataStrategy === option.value"
          @click="patch('dirtyDataStrategy', option.value)"
        >
          <span class="fws-policy-heading"><img
              :src="`/assets/qdata-fullsync/policy-${config.dirtyDataStrategy === option.value ? 'active' : 'normal'}.png`"
              alt=""
            />{{ option.title
            }}<span v-if="option.value === 'IGNORE'" class="fws-recommend">推荐</span></span>
          <span class="fws-policy-description">{{ option.description }}</span>
        </button>
      </div>
    </div>

    <h2 class="fws-section-title">执行参数配置</h2>
    <p class="fws-section-description">
      在这里您可以为任务设置执行引擎、失败处理策略以及运行所需的计算资源。
    </p>
    <div class="fws-resource-grid">
      <div class="fws-field fws-engine-field">
        <label class="fws-required">执行引擎</label>
        <div class="fws-engine-options" role="radiogroup" aria-label="执行引擎">
          <button
            v-for="engine in engines"
            :key="engine.value"
            type="button"
            class="fws-engine-option"
            :class="{ selected: config.engine === engine.value }"
            :disabled="engine.value !== 'SPARK'"
            role="radio"
            :aria-checked="config.engine === engine.value"
            @click="patch('engine', engine.value)"
          >
            <img
              :src="`/assets/qdata-fullsync/engine-${engine.value.toLowerCase()}.png`"
              alt=""
            />
            <span class="fws-engine-content"><span class="fws-engine-heading">{{ engine.value }}<span>{{ engine.tag }}</span></span><span class="fws-engine-description">{{
                engine.description
              }}</span></span>
            <span
              v-if="config.engine === engine.value"
              class="fws-engine-check"
              aria-hidden="true"
            ></span>
          </button>
        </div>
      </div>
      <div class="fws-field">
        <label class="fws-required">执行策略</label>
        <div>
          <Select
            :value="config.executionStrategy"
            :options="strategies"
            placeholder="请选择执行策略"
            @update:value="setStrategy"
          />
          <p class="fws-field-hint">定义任务节点的执行组织方式</p>
        </div>
      </div>
      <div class="fws-field">
        <label>任务优先级</label>
        <div>
          <Select
            :value="config.taskPriority"
            :options="priorities"
            placeholder="请选择任务优先级"
            @update:value="setPriority"
          />
          <p class="fws-field-hint">定义任务调度的优先级顺序</p>
        </div>
      </div>
      <div class="fws-field">
        <label>Worker分组</label>
        <div>
          <Input
            :value="config.workerGroup"
            disabled
            placeholder="请输入Worker分组"
          />
          <p class="fws-field-hint">指定执行任务的计算资源池</p>
        </div>
      </div>
      <div v-for="field in numericFields" :key="field.key" class="fws-field">
        <label>{{ field.label }}</label>
        <div>
          <div class="fws-unit-input">
            <InputNumber
              :value="config[field.key]"
              :min="field.min"
              :max="field.max"
              :precision="0"
              :placeholder="`请输入${field.label}`"
              @update:value="patch(field.key, $event ?? 0)"
            /><span>{{ field.unit }}</span>
          </div>
          <p class="fws-field-hint">{{ field.hint }}</p>
        </div>
      </div>
      <div v-for="field in resourceFields" :key="field.key" class="fws-field">
        <label>{{ field.label }}</label>
        <div>
          <InputNumber
            v-if="field.numeric"
            :value="Number(config[field.key])"
            :min="1"
            :precision="0"
            :placeholder="`请输入${field.label}`"
            @update:value="patch(field.key, $event ?? 1)"
          /><Input
            v-else
            :value="String(config[field.key] ?? '')"
            :placeholder="`请输入${field.label}${field.key === 'yarnQueue' ? '(选填)' : ''}`"
            @update:value="patch(field.key, $event)"
          />
          <p class="fws-field-hint">{{ field.hint }}</p>
        </div>
      </div>
    </div>
  </section>
</template>
