<script setup lang="ts">
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, ref } from 'vue';

import { formatDateTime } from '@vben/utils';

import {
  Descriptions,
  DescriptionsItem,
  Empty,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import { DEV_TASK_TYPE_OPTIONS, getDevProgramType } from '#/api/dataforge/dev';
import SqlEditor from '#/components/monaco/sql-editor.vue';

import {
  DEV_RECORD_STATUS_OPTIONS,
  DEV_SCHEDULE_SYNC_OPTIONS,
  formatDuration,
  optionColor,
  optionLabel,
} from '../data';
import {
  cronSummary,
  defaultExecutionConfig,
  PRIORITY_OPTIONS,
} from '../list-model';
import TaskTypeIcon from './task-type-icon.vue';

const props = withDefaults(
  defineProps<{
    creatorName?: string;
    databaseIconPath?: string;
    databaseName?: string;
    directoryName?: string;
    ownerName?: string;
    presentation?: 'config' | 'dialog' | 'page';
    task: DataforgeDevApi.Task;
  }>(),
  {
    creatorName: '',
    databaseIconPath: '',
    databaseName: '',
    directoryName: '',
    ownerName: '',
    presentation: 'dialog',
  },
);

const activeTab = ref<'code' | 'info'>('code');
const overviewColumns = { xs: 1, sm: 2, lg: 3 };
const detailColumns = computed(() => ({
  xs: 1,
  sm: 2,
  lg: props.presentation === 'page' ? 3 : 2,
}));
const editorHeight = computed(() =>
  props.presentation === 'page'
    ? 'clamp(360px, 54vh, 720px)'
    : 'min(46vh, 460px)',
);
const execution = computed(() => ({
  ...defaultExecutionConfig(),
  ...props.task.executionConfig,
}));
const program = computed(() => props.task.programConfig);
const programType = computed(() => getDevProgramType(props.task.taskType));
const taskTypeName = computed(
  () =>
    DEV_TASK_TYPE_OPTIONS.find((item) => item.value === props.task.taskType)
      ?.label ||
    props.task.taskType ||
    '-',
);
const language = computed(() => {
  if (programType.value === 'PYTHON') return 'python';
  if (props.task.taskType === 'SHELL') return 'shell';
  if (props.task.taskType === 'FLINK_SQL') return 'flinksql';
  if (props.task.taskType === 'SPARK_SQL') return 'sparksql';
  return props.task.dbType?.toUpperCase() === 'POSTGRESQL' ? 'pgsql' : 'mysql';
});
function display(value: null | number | string | undefined) {
  return value === undefined || value === null || String(value).trim() === ''
    ? '-'
    : String(value);
}
function time(value?: number) {
  return value === undefined || value === null ? '-' : formatDateTime(value);
}
function timeoutSummary(value?: number) {
  if (value === undefined) return '-';
  return value === 0 ? '长期运行' : `${value} 秒`;
}
const basics = computed(() => [
  {
    label: '任务优先级',
    value:
      PRIORITY_OPTIONS.find((item) => item.value === execution.value.priority)
        ?.label || execution.value.priority,
  },
  { label: 'Worker 分组', value: execution.value.workerGroup },
  { label: '失败重试次数', value: `${execution.value.retryTimes} 次` },
  {
    label: '失败重试间隔',
    value: `${execution.value.retryIntervalMinutes} 分钟`,
  },
  { label: '延迟执行时间', value: `${execution.value.delayMinutes} 分钟` },
  { label: '执行策略', value: '串行' },
  {
    label: '执行超时',
    value: timeoutSummary(props.task.timeoutSeconds),
  },
  { label: '最大返回行数', value: props.task.maxRows },
  {
    label: '调度系统',
    value: 'DolphinScheduler',
  },
  {
    label: '调度同步状态',
    value: optionLabel(
      DEV_SCHEDULE_SYNC_OPTIONS,
      props.task.scheduleSyncStatus,
    ),
  },
  { label: '调度同步说明', value: props.task.scheduleSyncReason },
  { label: '原调度周期', value: props.task.legacyCronExpression },
  { label: '调度周期', value: cronSummary(props.task.cronExpression) },
  { label: '最近运行时间', value: time(props.task.latestRecord?.startTime) },
  {
    label: '最近执行结果',
    value: props.task.latestRecord
      ? optionLabel(DEV_RECORD_STATUS_OPTIONS, props.task.latestRecord.status)
      : '未执行',
  },
]);
const resources = computed(() => {
  if (!program.value) return [];
  const cfg = program.value;
  const fields =
    props.task.engine === 'FLINK' ||
    props.task.taskType?.startsWith('FLINK') ||
    props.task.taskType === 'PYFLINK'
      ? [
          {
            label: 'JobManager 内存',
            value:
              cfg.jobManagerMemoryMb === undefined
                ? '部署默认值'
                : `${cfg.jobManagerMemoryMb} MB`,
          },
          {
            label: 'TaskManager 内存',
            value:
              cfg.taskManagerMemoryMb === undefined
                ? '部署默认值'
                : `${cfg.taskManagerMemoryMb} MB`,
          },
          { label: 'Slot 数量', value: cfg.taskManagerSlots },
          { label: '最大 TaskManager 数', value: cfg.taskManagerCount },
          { label: '并行度', value: cfg.parallelism },
          {
            label: 'Checkpoint 间隔',
            value:
              cfg.checkpointIntervalMs === undefined
                ? '-'
                : `${cfg.checkpointIntervalMs} ms`,
          },
        ]
      : [
          {
            label: 'Driver 内存',
            value:
              cfg.driverMemoryMb === undefined
                ? '部署默认值'
                : `${cfg.driverMemoryMb} MB`,
          },
          {
            label: 'Executor 内存',
            value:
              cfg.executorMemoryMb === undefined
                ? '部署默认值'
                : `${cfg.executorMemoryMb} MB`,
          },
          { label: 'Driver 核数', value: cfg.driverCores },
          { label: 'Executor 核数', value: cfg.executorCores },
          { label: 'Executor 数量', value: cfg.numExecutors },
        ];
  return [
    {
      label: '运行模式',
      value: cfg.workloadMode === 'STREAMING' ? '流任务' : '批任务',
    },
    { label: '部署档案', value: cfg.runtimeProfileCode },
    { label: '引擎版本', value: cfg.engineVersion },
    { label: '资源队列', value: cfg.queue },
    ...fields,
  ];
});
const sqlTypeNames = {
  AUTO: '自动识别',
  NON_QUERY: '非查询',
  PROCEDURE: '存储过程',
  QUERY: '查询',
};
</script>

<template>
  <div v-if="presentation === 'config'" class="task-reference-detail">
    <h4 class="task-reference-section">基本信息</h4>
    <dl class="task-reference-fields">
      <div>
        <dt>数据开发类目</dt>
        <dd class="is-source">{{ display(directoryName) }}</dd>
      </div>
      <div>
        <dt>任务名称</dt>
        <dd>{{ display(task.name) }}</dd>
      </div>
      <div>
        <dt>责任人</dt>
        <dd class="is-source">{{ display(ownerName || task.ownerName) }}</dd>
      </div>
      <div>
        <dt>联系电话</dt>
        <dd>{{ display(task.contactNumber) }}</dd>
      </div>
      <div>
        <dt>数据连接类型</dt>
        <dd class="is-source task-reference-type">
          <TaskTypeIcon
            :task-type="task.taskType"
            :engine="task.engine"
            :db-type="task.dbType"
            :database-icon-path="databaseIconPath"
            :size="20"
          />{{ taskTypeName }}
        </dd>
      </div>
      <div>
        <dt>执行策略</dt>
        <dd>串行</dd>
        <small>定义任务节点的执行组织方式</small>
      </div>
      <div>
        <dt>任务状态</dt>
        <dd>{{ task.status === 1 ? '已上线' : '未上线' }}</dd>
      </div>
      <div class="task-reference-full">
        <dt>描述</dt>
        <dd>{{ display(task.description) }}</dd>
      </div>
    </dl>
    <h4 class="task-reference-section">
      调度设置 <small>设置任务的调度周期及调度系统。</small>
    </h4>
    <dl class="task-reference-fields">
      <div class="task-reference-full">
        <dt>调度系统</dt>
        <dd>DOLPHINSCHEDULER</dd>
      </div>
      <div>
        <dt>调度周期</dt>
        <dd>{{ display(task.cronExpression) }}</dd>
        <small>定义任务自动触发的时间和频率</small>
      </div>
    </dl>
    <template
      v-if="!program || programType === 'SQL' || programType === 'PYTHON'"
    >
      <h4 class="task-reference-section">脚本配置</h4>
      <dl v-if="task.taskType !== 'SHELL'" class="task-reference-fields">
        <div>
          <dt>数据源连接</dt>
          <dd class="is-source">
            {{
              display(
                databaseName ||
                  task.datasourceName ||
                  task.databaseName ||
                  task.databaseId,
              )
            }}
          </dd>
        </div>
        <div>
          <dt>SQL类型</dt>
          <dd>
            {{
              task.taskType === 'SPARK_SQL'
                ? 'SparkSql开发'
                : execution.sqlType
                  ? sqlTypeNames[execution.sqlType]
                  : '-'
            }}
          </dd>
        </div>
        <div>
          <dt>分段执行符号</dt>
          <dd>{{ display(execution.statementSeparator) }}</dd>
        </div>
      </dl>
      <SqlEditor
        v-if="task.content"
        :key="`job-source-${task.id}-${task.latestVersion}`"
        :model-value="task.content"
        :language="language"
        height="280px"
        readonly
      />
      <Empty v-else description="尚未配置任务脚本" />
      <h4 class="task-reference-section task-reference-parameters">参数配置</h4>
      <div class="task-detail-parameter-container">
        <table
          v-if="execution.localParams?.length"
          class="task-detail-parameter-table"
        >
          <thead>
            <tr>
              <th>序号</th>
              <th>参数名称</th>
              <th>参数类型</th>
              <th>参数值</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(param, index) in execution.localParams" :key="index">
              <td>{{ index + 1 }}</td>
              <td>{{ param.prop }}</td>
              <td>{{ param.type }}</td>
              <td class="task-detail-mono">{{ param.value }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="task-detail-empty-params">未配置自定义参数</div>
      </div>
    </template>
    <details class="task-reference-more">
      <summary>运行配置</summary>
      <Descriptions
        class="task-detail-fields"
        bordered
        size="small"
        :column="{ xs: 1, md: 2 }"
      >
        <DescriptionsItem
          v-for="field in [...basics, ...resources]"
          :key="field.label"
          :label="field.label"
        >
          {{ display(field.value) }}
        </DescriptionsItem>
        <DescriptionsItem label="数据源连接">
          {{
            display(
              databaseName ||
                task.datasourceName ||
                task.databaseName ||
                task.databaseId,
            )
          }}
        </DescriptionsItem>
        <DescriptionsItem label="当前版本">
          {{
            task.latestVersion === undefined ? '-' : `v${task.latestVersion}`
          }}
        </DescriptionsItem>
      </Descriptions>
    </details>
  </div>
  <div
    v-else
    class="task-detail-view"
    :class="{ 'is-page': presentation === 'page' }"
  >
    <section class="task-detail-summary" aria-label="任务信息">
      <div class="task-detail-heading">
        <div class="task-detail-heading-main">
          <span class="task-detail-id">{{ task.id }}</span>
          <TaskTypeIcon
            :task-type="task.taskType"
            :engine="task.engine"
            :db-type="task.dbType"
            :database-icon-path="databaseIconPath"
            :size="22"
          />
          <component
            :is="presentation === 'page' ? 'h1' : 'h3'"
            class="task-detail-title"
          >
            {{ task.name || '数据开发任务' }}
          </component>
          <Tag :color="task.status === 1 ? 'green' : undefined">
            {{ task.status === 1 ? '已上线' : '未上线' }}
          </Tag>
          <span class="task-detail-type">{{ taskTypeName }}</span>
        </div>
        <div v-if="$slots.headingExtra" class="task-detail-heading-extra">
          <slot name="headingExtra"></slot>
        </div>
      </div>
      <Descriptions
        class="task-detail-overview"
        bordered
        size="small"
        :column="overviewColumns"
      >
        <DescriptionsItem label="数据开发类目">
          {{ display(directoryName) }}
        </DescriptionsItem>
        <DescriptionsItem label="数据连接类型">
          {{ display(task.dbType || task.engine) }}
        </DescriptionsItem>
        <DescriptionsItem label="调度状态">
          <span
            class="task-detail-schedule-state"
            :class="{ enabled: task.scheduleStatus === 1 }"
          >
            {{ task.scheduleStatus === 1 ? '已开启' : '已关闭' }}
          </span>
        </DescriptionsItem>
        <DescriptionsItem label="描述" :span="3">
          {{ display(task.description) }}
        </DescriptionsItem>
        <DescriptionsItem label="责任人">
          {{ display(ownerName || task.ownerName) }}
          <small v-if="task.contactNumber">{{ task.contactNumber }}</small>
        </DescriptionsItem>
        <DescriptionsItem label="创建人">
          {{ display(creatorName || task.creator) }}
        </DescriptionsItem>
        <DescriptionsItem label="创建时间">
          {{ time(task.createTime) }}
        </DescriptionsItem>
        <DescriptionsItem label="更新时间">
          {{ time(task.updateTime) }}
        </DescriptionsItem>
        <DescriptionsItem label="当前版本">
          {{
            task.latestVersion === undefined ? '-' : `v${task.latestVersion}`
          }}
        </DescriptionsItem>
        <DescriptionsItem label="最近执行">
          <Tag
            v-if="task.latestRecord"
            :color="
              optionColor(DEV_RECORD_STATUS_OPTIONS, task.latestRecord.status)
            "
          >
            {{
              optionLabel(DEV_RECORD_STATUS_OPTIONS, task.latestRecord.status)
            }}
          </Tag>
          <span v-else>未执行</span>
          <small v-if="task.latestRecord?.durationMs !== undefined">
            {{ formatDuration(task.latestRecord.durationMs) }}
          </small>
        </DescriptionsItem>
      </Descriptions>
    </section>
    <section class="task-detail-content" aria-label="任务详情">
      <Tabs v-model:active-key="activeTab" :animated="false">
        <TabPane key="code" tab="开发详情">
          <div class="task-detail-code">
            <div class="task-detail-code-heading">
              <h4>
                {{
                  programType === 'JAR'
                    ? '执行制品'
                    : programType === 'PYTHON'
                      ? '源码参考'
                      : '任务脚本'
                }}
              </h4>
              <Tag>只读</Tag>
            </div>
            <Descriptions
              v-if="programType === 'JAR'"
              class="task-detail-fields"
              bordered
              size="small"
              :column="detailColumns"
            >
              <DescriptionsItem label="制品编号">
                {{ display(program?.artifactFileId) }}
              </DescriptionsItem>
              <DescriptionsItem label="入口类">
                {{ display(program?.entryPoint) }}
              </DescriptionsItem>
              <DescriptionsItem label="制品校验值" :span="3">
                <span class="task-detail-mono">
                  {{ display(program?.artifactSha256) }}
                </span>
              </DescriptionsItem>
            </Descriptions>
            <template v-else>
              <p v-if="programType === 'PYTHON'" class="task-detail-hint">
                任务运行所选受管 Python 制品，以下文本仅作源码参考。
              </p>
              <SqlEditor
                v-if="task.content"
                :model-value="task.content"
                :language="language"
                readonly
                :height="editorHeight"
              />
              <Empty v-else description="尚未配置任务脚本" />
            </template>
          </div>
        </TabPane>
        <TabPane key="info" tab="详细信息">
          <div class="task-detail-sections">
            <h4>基础配置</h4>
            <Descriptions
              class="task-detail-fields"
              bordered
              size="small"
              :column="detailColumns"
            >
              <DescriptionsItem
                v-for="field in basics"
                :key="field.label"
                :label="field.label"
              >
                {{ display(field.value) }}
              </DescriptionsItem>
            </Descriptions>
            <template v-if="program">
              <h4>运行环境与资源</h4>
              <Descriptions
                class="task-detail-fields"
                bordered
                size="small"
                :column="detailColumns"
              >
                <DescriptionsItem
                  v-for="field in resources"
                  :key="field.label"
                  :label="field.label"
                >
                  {{ display(field.value) }}
                </DescriptionsItem>
              </Descriptions>
              <template v-if="programType !== 'SQL'">
                <h4>程序配置</h4>
                <Descriptions
                  class="task-detail-fields"
                  bordered
                  size="small"
                  :column="detailColumns"
                >
                  <DescriptionsItem label="制品编号">
                    {{ display(program.artifactFileId) }}
                  </DescriptionsItem>
                  <DescriptionsItem label="入口类">
                    {{ display(program.entryPoint) }}
                  </DescriptionsItem>
                  <DescriptionsItem label="程序参数" :span="3">
                    <span class="task-detail-params">
                      <code
                        v-for="(argument, index) in program.arguments"
                        :key="index"
                      >
                        {{ argument }}
                      </code>
                      <span v-if="!program.arguments?.length">-</span>
                    </span>
                  </DescriptionsItem>
                </Descriptions>
              </template>
            </template>
            <template v-else>
              <h4>数据连接与 SQL</h4>
              <Descriptions
                class="task-detail-fields"
                bordered
                size="small"
                :column="detailColumns"
              >
                <DescriptionsItem label="数据源连接">
                  {{
                    display(
                      databaseName ||
                        task.datasourceName ||
                        task.databaseName ||
                        task.databaseId,
                    )
                  }}
                </DescriptionsItem>
                <DescriptionsItem label="SQL 类型">
                  {{
                    execution.sqlType ? sqlTypeNames[execution.sqlType] : '-'
                  }}
                </DescriptionsItem>
                <DescriptionsItem label="分段执行符号">
                  <span class="task-detail-mono">
                    {{ display(execution.statementSeparator) }}
                  </span>
                </DescriptionsItem>
                <DescriptionsItem label="数据连接类型">
                  {{ display(task.dbType) }}
                </DescriptionsItem>
              </Descriptions>
              <h4>参数配置</h4>
              <div class="task-detail-parameter-container">
                <table
                  v-if="execution.localParams?.length"
                  class="task-detail-parameter-table"
                >
                  <thead>
                    <tr>
                      <th>参数名称</th>
                      <th>参数类型</th>
                      <th>参数值</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(param, index) in execution.localParams"
                      :key="index"
                    >
                      <td>{{ param.prop }}</td>
                      <td>{{ param.type }}</td>
                      <td class="task-detail-mono">{{ param.value }}</td>
                    </tr>
                  </tbody>
                </table>
                <div v-else class="task-detail-empty-params">
                  未配置自定义参数
                </div>
              </div>
            </template>
            <p v-if="task.executionCapabilityReason" class="task-detail-hint">
              {{ task.executionCapabilityReason }}
            </p>
          </div>
        </TabPane>
      </Tabs>
    </section>
  </div>
</template>

<style scoped>
.task-reference-detail {
  min-width: 0;
  padding: 4px 16px 18px;
  font-size: 14px;
  color: hsl(var(--foreground));
}

.task-reference-section {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 28px;
  font-size: 16px;
  font-weight: 500;
}

.task-reference-section::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 3px;
}

.task-reference-section small {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
  color: hsl(var(--muted-foreground));
}

.task-reference-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px 50px;
  margin: 0 0 28px;
  font-size: 14px;
}

.task-reference-fields > div {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr);
  gap: 0 12px;
  align-items: start;
  min-width: 0;
}

.task-reference-fields dt {
  padding-top: 7px;
  text-align: right;
}

.task-reference-fields dd {
  min-height: 33px;
  padding: 5px 10px;
  margin: 0;
  line-height: 22px;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 12%);
  border: 1px solid hsl(var(--border) / 65%);
  border-radius: 2px;
}

.task-reference-fields .is-source {
  background: hsl(var(--muted) / 50%);
}

.task-reference-fields small {
  grid-column: 2;
  margin-top: 3px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.task-reference-type {
  display: flex;
  gap: 8px;
  align-items: center;
}

.task-reference-fields .task-reference-full {
  grid-column: 1 / -1;
}

.task-reference-more summary {
  margin-bottom: 16px;
  color: #2d63ff;
  cursor: pointer;
}

.task-reference-parameters,
.task-reference-more {
  margin-top: 20px;
}

@media (width <= 760px) {
  .task-reference-fields {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }

  .task-reference-fields > div {
    grid-template-columns: 108px minmax(0, 1fr);
  }

  .task-reference-section {
    flex-wrap: wrap;
  }
}

.task-detail-view {
  min-width: 0;
  font-size: 14px;
}

.task-detail-summary {
  padding-bottom: 16px;
}

.task-detail-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0 20px;
}

.task-detail-heading-main {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.task-detail-title {
  min-width: 0;
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.task-detail-id {
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  line-height: 18px;
  color: hsl(var(--primary));
  overflow-wrap: anywhere;
  background: hsl(var(--primary) / 10%);
  border-radius: 4px;
}

.task-detail-type {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.task-detail-heading-extra {
  flex-shrink: 0;
}

.task-detail-view :deep(.ant-descriptions-item-label) {
  width: 140px;
  color: hsl(var(--muted-foreground));
}

.task-detail-view :deep(.ant-descriptions-item-content) {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.task-detail-overview small {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.task-detail-schedule-state::before {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  vertical-align: 2px;
  content: '';
  background: hsl(var(--muted-foreground) / 55%);
  border-radius: 50%;
}

.task-detail-schedule-state.enabled::before {
  background: #52a66c;
}

.task-detail-content {
  border-top: 10px solid hsl(var(--muted) / 50%);
}

.task-detail-content :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

.task-detail-code {
  min-height: 230px;
}

.task-detail-code-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.task-detail-code-heading h4,
.task-detail-sections h4 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 14px;
  font-size: 13px;
  font-weight: 500;
  color: hsl(var(--foreground));
}

.task-detail-code-heading h4::before,
.task-detail-sections h4::before {
  flex-shrink: 0;
  width: 3px;
  height: 14px;
  content: '';
  background: hsl(var(--primary));
  border-radius: 2px;
}

.task-detail-sections h4:not(:first-child) {
  margin-top: 24px;
}

.task-detail-hint {
  margin: 8px 0 12px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.task-detail-mono {
  font-family: Consolas, monospace;
}

.task-detail-params code {
  display: inline-block;
  padding: 2px 6px;
  margin-right: 6px;
  font-size: 12px;
  background: hsl(var(--muted));
  border-radius: 2px;
}

.task-detail-parameter-container {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.task-detail-parameter-table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
}

.task-detail-parameter-table th,
.task-detail-parameter-table td {
  padding: 9px 12px;
  text-align: left;
  overflow-wrap: anywhere;
  border-bottom: 1px solid hsl(var(--border));
}

.task-detail-parameter-table tr:last-child td {
  border-bottom: 0;
}

.task-detail-parameter-table th {
  font-weight: 400;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 55%);
}

.task-detail-empty-params {
  padding: 18px;
  color: hsl(var(--muted-foreground));
  text-align: center;
  background: hsl(var(--muted) / 35%);
}

.task-detail-view.is-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.is-page .task-detail-summary,
.is-page .task-detail-content {
  background: hsl(var(--card));
  border-radius: 8px;
}

.is-page .task-detail-summary {
  padding: 20px;
}

.is-page .task-detail-title {
  font-size: 18px;
}

.is-page .task-detail-content {
  min-height: 480px;
  padding: 8px 20px 20px;
  border-top: 0;
}

@media (width <= 640px) {
  .task-detail-view :deep(.ant-descriptions-item-label) {
    width: 100px;
  }

  .task-detail-heading-main {
    gap: 8px;
  }

  .task-detail-type {
    flex-basis: 100%;
  }

  .is-page .task-detail-summary {
    padding: 16px;
  }

  .is-page .task-detail-content {
    padding: 8px 16px 16px;
  }
}
</style>

<style scoped>
.task-detail-parameter-table thead th,
.task-detail-parameter-table tbody td {
  font-size: 12px;
}
</style>
