<script lang="ts" setup>
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Input, InputNumber, RadioGroup, Select } from 'antdv-next';

import {
  defaultExecutionConfig,
  PARAM_TYPE_OPTIONS,
  PRIORITY_OPTIONS,
} from '../list-model';

const props = defineProps<{
  modelValue?: DataforgeDevApi.ExecutionConfig;
  native?: boolean;
  readonly?: boolean;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: DataforgeDevApi.ExecutionConfig];
}>();
const config = computed(() => ({
  ...defaultExecutionConfig(),
  ...props.modelValue,
}));
function update<K extends keyof DataforgeDevApi.ExecutionConfig>(
  key: K,
  value: DataforgeDevApi.ExecutionConfig[K],
) {
  emit('update:modelValue', { ...config.value, [key]: value });
}
function updateParam(
  index: number,
  key: keyof DataforgeDevApi.LocalParam,
  value: unknown,
) {
  const rows = (config.value.localParams ?? []).map((row, i) =>
    i === index ? { ...row, [key]: value } : row,
  );
  update('localParams', rows);
}
function addParam() {
  update('localParams', [
    ...(config.value.localParams ?? []),
    { prop: '', type: 'VARCHAR', value: '' },
  ]);
}
</script>

<template>
  <div class="execution-properties">
    <h4>
      <IconifyIcon
        class="size-4 shrink-0"
        icon="ri:bookmark-2-fill"
        aria-hidden="true"
      />
      基础配置
    </h4>
    <label>
      <span class="property-label">任务优先级</span>
      <Select
        :value="config.priority"
        :options="PRIORITY_OPTIONS"
        :disabled="readonly"
        @update:value="
          (value) =>
            update(
              'priority',
              value as DataforgeDevApi.ExecutionConfig['priority'],
            )
        "
      />
    </label>
    <label>
      <span class="property-label">Worker 分组</span>
      <Input :value="config.workerGroup" disabled />
    </label>
    <label>
      <span class="property-label">失败重试次数</span>
      <InputNumber
        :value="config.retryTimes"
        :min="0"
        :max="20"
        :disabled="readonly"
        @update:value="(value) => update('retryTimes', value ?? 0)"
      />
      <span>次</span>
    </label>
    <label>
      <span class="property-label">失败重试间隔</span>
      <InputNumber
        :value="config.retryIntervalMinutes"
        :min="1"
        :max="1440"
        :disabled="readonly"
        @update:value="(value) => update('retryIntervalMinutes', value ?? 1)"
      />
      <span>分</span>
    </label>
    <label>
      <span class="property-label">延迟执行时间</span>
      <InputNumber
        :value="config.delayMinutes"
        :min="0"
        :max="1440"
        :disabled="readonly"
        @update:value="(value) => update('delayMinutes', value ?? 0)"
      />
      <span>分</span>
    </label>
    <slot name="runtime"></slot>
    <template v-if="!native">
      <h4>
        <IconifyIcon
          class="size-4 shrink-0"
          icon="ri:bookmark-2-fill"
          aria-hidden="true"
        />
        SQL 配置
      </h4>
      <slot name="datasource"></slot>
      <label class="sql-type">
        <span class="property-label">SQL 类型</span>
        <RadioGroup
          :value="config.sqlType"
          :disabled="readonly"
          :options="[
            { label: '自动识别', value: 'AUTO' },
            { label: '查询', value: 'QUERY' },
            { label: '非查询', value: 'NON_QUERY' },
            { label: '存储过程', value: 'PROCEDURE' },
          ]"
          @update:value="
            (value) =>
              update(
                'sqlType',
                value as DataforgeDevApi.ExecutionConfig['sqlType'],
              )
          "
        />
      </label>
      <p v-if="config.sqlType === 'PROCEDURE'" class="parameter-hint">
        存储过程脚本需先定义过程，再在同一脚本内调用；独立调用既有过程无法通过保存校验。
      </p>
      <label>
        <span class="property-label">分段执行符号</span>
        <Input
          :value="config.statementSeparator"
          :maxlength="16"
          :disabled="readonly"
          aria-label="分段执行符号"
          @update:value="(value) => update('statementSeparator', value)"
        />
      </label>
      <h4>
        <IconifyIcon
          class="size-4 shrink-0"
          icon="ri:bookmark-2-fill"
          aria-hidden="true"
        />
        参数配置
      </h4>
      <p class="parameter-hint">
        SQL 中使用 ${参数名称}
        引用，自定义参数由执行引擎按类型绑定，值以文本保存。
      </p>
      <div
        v-for="(param, index) in config.localParams"
        :key="index"
        class="parameter-row"
      >
        <Input
          :value="param.prop"
          :disabled="readonly"
          aria-label="参数名称"
          placeholder="参数名称"
          @update:value="(value) => updateParam(index, 'prop', value)"
        />
        <Select
          :value="param.type"
          :options="PARAM_TYPE_OPTIONS"
          :disabled="readonly"
          aria-label="参数类型"
          @update:value="(value) => updateParam(index, 'type', value)"
        />
        <Input
          :value="param.value"
          :disabled="readonly"
          aria-label="参数值"
          placeholder="参数值"
          @update:value="(value) => updateParam(index, 'value', value)"
        />
        <Button
          :disabled="readonly"
          type="text"
          danger
          size="small"
          :aria-label="`删除参数 ${param.prop || index + 1}`"
          @click="
            update(
              'localParams',
              config.localParams?.filter((_, i) => i !== index),
            )
          "
        >
          <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
          删除
        </Button>
      </div>
      <Button
        :disabled="readonly"
        block
        type="dashed"
        size="small"
        @click="addParam"
      >
        + 添加配置项
      </Button>
    </template>
    <p v-else class="parameter-hint">
      原生 SQL 执行编辑器脚本；JAR/Python 执行所选受管制品。SQL
      连接器由受管部署提供，JDBC 分段符和绑定参数不用于原生运行。
    </p>
  </div>
</template>

<style scoped>
.execution-properties {
  container-type: inline-size;
}

.execution-properties h4 {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 8px 12px;
  margin: 14px 0 16px;
  font-size: 13px;
  font-weight: 500;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 6%);
  border-radius: 2px;
}

.execution-properties label {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 10px 0;
  font-size: 13px;
  white-space: nowrap;
}

.execution-properties label > :deep(.ant-select),
.execution-properties label > :deep(.ant-input),
.execution-properties label > :deep(.ant-input-number) {
  flex: 1;
  min-width: 0;
}

.execution-properties label > span:not(.property-label) {
  width: 14px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.execution-properties .property-label {
  flex: 0 0 106px;
  text-align: right;
}

.execution-properties :deep(.ant-input),
.execution-properties :deep(.ant-select-selector),
.execution-properties :deep(.ant-input-number) {
  border-radius: 2px;
}

.execution-properties .sql-type {
  align-items: flex-start;
}

.sql-type :deep(.ant-radio-group) {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.parameter-hint {
  margin-bottom: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: hsl(var(--muted-foreground));
}

.parameter-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px minmax(0, 1fr) auto;
  gap: 6px;
  padding: 8px;
  margin-bottom: 8px;
  background: hsl(var(--muted) / 35%);
  border: 1px solid hsl(var(--border));
  border-radius: 2px;
}

.parameter-row > :nth-child(3) {
  grid-column: auto;
}

.parameter-row :deep(.ant-select) {
  width: 100%;
}

@container (max-width: 380px) {
  .parameter-row {
    grid-template-columns: minmax(0, 1fr) 96px;
  }

  .parameter-row > :nth-child(3) {
    grid-column: 1;
  }
}
</style>
