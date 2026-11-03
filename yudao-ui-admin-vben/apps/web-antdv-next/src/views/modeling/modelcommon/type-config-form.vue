<script lang="ts" setup>
import type { ModelingModelApi } from '#/api/modeling/model';

import { computed, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import { Alert, Input, Select, TextArea } from 'antdv-next';

import { getTableModelPage } from '#/api/modeling/tablemodel';

import {
  TYPE_CONFIG_DELETE_STRATEGY_OPTIONS,
  TYPE_CONFIG_HISTORY_STRATEGY_OPTIONS,
  TYPE_CONFIG_INGEST_MODE_OPTIONS,
  TYPE_CONFIG_LATE_ARRIVAL_OPTIONS,
  TYPE_CONFIG_ORGANIZATION_MODE_OPTIONS,
  TYPE_CONFIG_STALE_GROUP_OPTIONS,
  TYPE_CONFIG_TIME_GRANULARITY_OPTIONS,
  TYPE_CONFIG_UNKNOWN_VALUE_OPTIONS,
  TYPE_CONFIG_WRITE_MODE_OPTIONS,
} from './data';
import { useModelingRequest } from './use-modeling-request';

/**
 * 表类型专属配置表单
 *
 * 按模型的 tableType 渲染对应的一段：五类表要回答的问题完全不同，
 * 明细表要说清一行是什么，汇总表要说清按什么统计、重算怎么办。
 * 字段引用从当前版本的字段列表里选，发布时后端会校验引用是否存在。
 */
defineOptions({ name: 'ModelingTypeConfigForm' });

const props = defineProps<{
  disabled?: boolean;
  /** 当前版本字段，供字段选择器使用 */
  fields: ModelingModelApi.ModelField[];
  /** 模型表类型：SOURCE/DIMENSION/DETAIL/SUMMARY/APPLICATION */
  tableType?: string;
}>();

const config = defineModel<ModelingModelApi.ModelTypeConfig>('value', {
  default: () => ({}),
});

const fieldOptions = computed(() =>
  props.fields
    .filter((field) => field.fieldName?.trim())
    .map((field) => ({
      label: field.cnName
        ? `${field.fieldName}（${field.cnName}）`
        : field.fieldName!,
      value: field.fieldName!,
    })),
);

/** 各段按需初始化，避免模板里到处判空 */
watch(
  () => [props.tableType, config.value],
  () => {
    const section = {
      SOURCE: 'source',
      DETAIL: 'detail',
      DIMENSION: 'dimension',
      SUMMARY: 'summary',
      APPLICATION: 'application',
    }[props.tableType ?? ''] as
      | keyof ModelingModelApi.ModelTypeConfig
      | undefined;
    if (section && !config.value[section])
      config.value = { ...config.value, [section]: {} };
  },
  { immediate: true, flush: 'sync' },
);
const source = computed(() => config.value.source ?? {});
const dimension = computed(() => config.value.dimension ?? {});
const detail = computed(() => config.value.detail ?? {});
const summary = computed(() => config.value.summary ?? {});
const application = computed(() => config.value.application ?? {});
const access = useAccessStore();
const inputs = ref<{ label: string; value: number | string }[]>([]);
const inputLoading = ref(false);
const inputRequest = useModelingRequest(
  () => props.tableType,
  () => {
    inputs.value = [];
    inputLoading.value = false;
  },
);
const inputOptions = computed(() => {
  const result = [...inputs.value];
  for (const id of summary.value.inputModelIds ?? [])
    if (!result.some((row) => String(row.value) === String(id)))
      result.push({ label: `模型 #${id}`, value: id });
  return result;
});
async function searchInputs(name = '') {
  if (props.tableType !== 'SUMMARY') return;
  const current = inputRequest.begin();
  inputLoading.value = true;
  try {
    const result = await getTableModelPage({ pageNo: 1, pageSize: 50, name });
    if (current())
      inputs.value = result.list.map((row) => ({
        label: `${row.name} (${row.code ?? row.id})`,
        value: row.id!,
      }));
  } finally {
    if (current()) inputLoading.value = false;
  }
}
watch(
  () => `${access.accessToken}/${access.refreshToken}/${props.tableType}`,
  () => searchInputs(),
  { immediate: true },
);

/** 发布前必填项，与后端 ModelTypeConfigValidator 一致 */
const REQUIRED_HINTS: Record<string, string> = {
  APPLICATION: '发布前必填：消费场景、输出粒度描述',
  DETAIL: '发布前必填：组织方式、粒度描述、粒度字段、业务时间字段',
  DIMENSION:
    '发布前必填：业务键、历史策略；保留历史版本时还需生效/失效时间字段',
  SOURCE: '发布前必填：承接模式、来源业务键；增量事件模式还需事件序列字段',
  SUMMARY:
    '发布前必填：输出粒度字段、时间粒度、业务时间口径、统计口径说明；覆盖重算时还需说明失效分组处理',
};
</script>

<template>
  <div class="type-config">
    <Alert
      v-if="!tableType"
      message="模型尚未确认表类型，请先在基础信息中选择表类型，再填写专属配置"
      show-icon
      type="warning"
    />
    <template v-else>
      <Alert
        :message="REQUIRED_HINTS[tableType]"
        class="mb-3"
        show-icon
        type="info"
      />

      <!-- 贴源表 -->
      <div v-if="tableType === 'SOURCE'" class="type-config__grid">
        <label class="type-config__label">来源系统</label>
        <Input
          v-model:value="source.sourceSystem"
          :disabled="disabled"
          placeholder="如：政务服务审批系统"
        />
        <label class="type-config__label">来源表/接口</label>
        <Input
          v-model:value="source.sourceTable"
          :disabled="disabled"
          placeholder="来源库中的表名或接口"
        />
        <label class="type-config__label type-config__label--required">
          承接模式
        </label>
        <Select
          v-model:value="source.ingestMode"
          :disabled="disabled"
          :options="TYPE_CONFIG_INGEST_MODE_OPTIONS"
          allow-clear
          placeholder="当前状态 / 周期全量快照 / 增量事件日志"
        />
        <label class="type-config__label type-config__label--required">
          来源业务键
        </label>
        <Select
          v-model:value="source.sourceKeyFields"
          :disabled="disabled"
          :options="fieldOptions"
          mode="multiple"
          placeholder="复合键按顺序选择"
        />
        <template v-if="source.ingestMode === 'CDC_EVENT'">
          <label class="type-config__label type-config__label--required">
            事件序列字段
          </label>
          <Select
            v-model:value="source.eventSequenceField"
            :disabled="disabled"
            :options="fieldOptions"
            allow-clear
            placeholder="日志位置或事件序号字段"
          />
        </template>
        <label class="type-config__label">删除处理</label>
        <Select
          v-model:value="source.deleteStrategy"
          :disabled="disabled"
          :options="TYPE_CONFIG_DELETE_STRATEGY_OPTIONS"
          allow-clear
          placeholder="来源删除后如何处理"
        />
        <label class="type-config__label">数据保留</label>
        <Input
          v-model:value="source.retentionNote"
          :disabled="disabled"
          placeholder="如：事件保留 3 年"
        />
      </div>

      <!-- 维度表 -->
      <div v-else-if="tableType === 'DIMENSION'" class="type-config__grid">
        <label class="type-config__label type-config__label--required">
          业务键
        </label>
        <Select
          v-model:value="dimension.businessKeyFields"
          :disabled="disabled"
          :options="fieldOptions"
          mode="multiple"
          placeholder="来源业务唯一标识，复合键按顺序选择"
        />
        <label class="type-config__label type-config__label--required">
          历史策略
        </label>
        <Select
          v-model:value="dimension.historyStrategy"
          :disabled="disabled"
          :options="TYPE_CONFIG_HISTORY_STRATEGY_OPTIONS"
          allow-clear
          placeholder="固定值 / 覆盖最新值 / 保留历史版本"
        />
        <template v-if="dimension.historyStrategy === 'SCD2'">
          <label class="type-config__label type-config__label--required">
            生效时间字段
          </label>
          <Select
            v-model:value="dimension.effectiveFromField"
            :disabled="disabled"
            :options="fieldOptions"
            allow-clear
          />
          <label class="type-config__label type-config__label--required">
            失效时间字段
          </label>
          <Select
            v-model:value="dimension.effectiveToField"
            :disabled="disabled"
            :options="fieldOptions"
            allow-clear
          />
          <label class="type-config__label">当前标识字段</label>
          <Select
            v-model:value="dimension.currentFlagField"
            :disabled="disabled"
            :options="fieldOptions"
            allow-clear
            placeholder="选填"
          />
        </template>
        <label class="type-config__label">未知值处理</label>
        <Select
          v-model:value="dimension.unknownValueStrategy"
          :disabled="disabled"
          :options="TYPE_CONFIG_UNKNOWN_VALUE_OPTIONS"
          allow-clear
          placeholder="事实关联不上维度时怎么处理"
        />
      </div>

      <!-- 明细表 -->
      <div v-else-if="tableType === 'DETAIL'" class="type-config__grid">
        <label class="type-config__label type-config__label--required">
          组织方式
        </label>
        <Select
          v-model:value="detail.organizationMode"
          :disabled="disabled"
          :options="TYPE_CONFIG_ORGANIZATION_MODE_OPTIONS"
          allow-clear
          placeholder="事务事件 / 周期快照 / 累积快照"
        />
        <label class="type-config__label type-config__label--required">
          粒度描述
        </label>
        <Input
          v-model:value="detail.grainDescription"
          :disabled="disabled"
          placeholder="一行代表什么，如：一个去重后的受理事件的当前有效状态"
        />
        <label class="type-config__label type-config__label--required">
          粒度字段
        </label>
        <Select
          v-model:value="detail.grainFields"
          :disabled="disabled"
          :options="fieldOptions"
          mode="multiple"
          placeholder="唯一确定一行的字段"
        />
        <label class="type-config__label type-config__label--required">
          业务时间字段
        </label>
        <Select
          v-model:value="detail.businessTimeField"
          :disabled="disabled"
          :options="fieldOptions"
          allow-clear
          placeholder="事件发生时间，不是采集时间"
        />
        <label class="type-config__label">去重依据</label>
        <Select
          v-model:value="detail.dedupFields"
          :disabled="disabled"
          :options="fieldOptions"
          mode="multiple"
          placeholder="重复采集时按哪些字段判定同一事件"
        />
        <label class="type-config__label">迟到与更正</label>
        <Select
          v-model:value="detail.lateArrivalStrategy"
          :disabled="disabled"
          :options="TYPE_CONFIG_LATE_ARRIVAL_OPTIONS"
          allow-clear
          placeholder="迟到或更正数据怎么处理"
        />
      </div>

      <!-- 汇总表 -->
      <div v-else-if="tableType === 'SUMMARY'" class="type-config__grid">
        <label class="type-config__label">来源模型</label>
        <Select
          v-model:value="summary.inputModelIds"
          :disabled="disabled"
          :options="inputOptions"
          :loading="inputLoading"
          mode="multiple"
          show-search
          :filter-option="false"
          placeholder="搜索作为汇总输入的模型"
          @search="searchInputs"
        />
        <label class="type-config__label type-config__label--required">
          输出粒度字段
        </label>
        <Select
          v-model:value="summary.grainFields"
          :disabled="disabled"
          :options="fieldOptions"
          mode="multiple"
          placeholder="全部分组维度，如：区县 + 日期"
        />
        <label class="type-config__label type-config__label--required">
          时间粒度
        </label>
        <Select
          v-model:value="summary.timeGranularity"
          :disabled="disabled"
          :options="TYPE_CONFIG_TIME_GRANULARITY_OPTIONS"
          allow-clear
        />
        <template v-if="summary.timeGranularity === 'ROLLING_WINDOW'">
          <label class="type-config__label type-config__label--required">
            滚动窗口说明
          </label>
          <Input
            v-model:value="summary.rollingWindowNote"
            :disabled="disabled"
            placeholder="如：近 7 日"
          />
        </template>
        <label class="type-config__label type-config__label--required">
          业务时间口径
        </label>
        <Input
          v-model:value="summary.businessTimeField"
          :disabled="disabled"
          placeholder="以哪个时间划周期，如：受理时间 accept_time"
        />
        <label class="type-config__label type-config__label--required">
          统计口径说明
        </label>
        <TextArea
          v-model:value="summary.measureDefinition"
          :disabled="disabled"
          :rows="2"
          placeholder="数的是什么、如何去重、过滤了什么。如：去重后的有效受理事件数，排除已撤销"
        />
        <label class="type-config__label">过滤条件</label>
        <Input
          v-model:value="summary.filterCondition"
          :disabled="disabled"
          placeholder="如：status = 'VALID'"
        />
        <label class="type-config__label">写入方式</label>
        <Select
          v-model:value="summary.writeMode"
          :disabled="disabled"
          :options="TYPE_CONFIG_WRITE_MODE_OPTIONS"
          allow-clear
        />
        <label
          class="type-config__label"
          :class="{
            'type-config__label--required':
              summary.writeMode === 'OVERWRITE_RANGE',
          }"
        >
          失效分组处理
        </label>
        <Select
          v-model:value="summary.staleGroupStrategy"
          :disabled="disabled"
          :options="TYPE_CONFIG_STALE_GROUP_OPTIONS"
          allow-clear
          placeholder="重算时已消失的分组怎么处理，否则会遗留旧值"
        />
      </div>

      <!-- 应用表 -->
      <div v-else-if="tableType === 'APPLICATION'" class="type-config__grid">
        <label class="type-config__label type-config__label--required">
          消费场景
        </label>
        <Input
          v-model:value="application.consumerScene"
          :disabled="disabled"
          placeholder="如：政务服务驾驶舱-区县受理概览"
        />
        <label class="type-config__label">消费方</label>
        <Select
          v-model:value="application.consumers"
          :disabled="disabled"
          :open="false"
          mode="tags"
          placeholder="接口 / 报表 / 应用，回车添加"
        />
        <label class="type-config__label type-config__label--required">
          输出粒度描述
        </label>
        <Input
          v-model:value="application.grainDescription"
          :disabled="disabled"
          placeholder="一行代表什么，如：一个区县某日的展示记录"
        />
        <label class="type-config__label">刷新要求</label>
        <Input
          v-model:value="application.refreshRequirement"
          :disabled="disabled"
          placeholder="可接受延迟与刷新方式，如：T+1 日 08:00 前，全量覆盖"
        />
        <label class="type-config__label">兼容约定</label>
        <TextArea
          v-model:value="application.compatibilityNote"
          :disabled="disabled"
          :rows="2"
          placeholder="字段废弃、改名、口径变化如何通知消费方"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.type-config__grid {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 10px 12px;
  align-items: center;
}

.type-config__label {
  font-size: 14px;
  text-align: right;
}

.type-config__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}
</style>
