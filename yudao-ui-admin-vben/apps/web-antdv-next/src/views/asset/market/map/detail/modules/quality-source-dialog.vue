<script setup lang="ts">
import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';

import { computed, h, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, Button, Form, FormItem, Input, Table, Tag } from 'antdv-next';

import { CronTab } from '#/components/cron-tab';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

const props = defineProps<{ snapshot?: ImportedAssetApi.Snapshot }>();
const mode = ref<'rule' | 'schedule' | 'task'>('task');
const selected = ref<ImportedAssetApi.Evaluation>();
const title = computed(() => {
  if (mode.value === 'schedule') return '调度周期';
  if (mode.value === 'rule')
    return `评测规则详情-${selected.value?.ruleName || selected.value?.ruleCode || ''}`;
  return '资产质量任务';
});
const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  showCancelButton: false,
  fullscreenButton: false,
});
function open(
  value: 'rule' | 'schedule' | 'task',
  evaluation?: ImportedAssetApi.Evaluation,
) {
  mode.value = value;
  selected.value = evaluation;
  modalApi.open();
}
defineExpose({ open, close: () => modalApi.close() });
const columns = [
  { title: '编号', dataIndex: 'sourceId', key: 'sourceId', width: 70 },
  { title: '评测名称', dataIndex: 'name', key: 'name' },
  { title: '评测字段', dataIndex: 'columnName', key: 'columnName' },
  { title: '稽查规则', dataIndex: 'ruleName', key: 'ruleName' },
  {
    title: '规则描述',
    dataIndex: 'description',
    key: 'description',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '状态', key: 'status', width: 80 },
  { title: '操作', key: 'actions', width: 80 },
];
function text(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
const configEntries = computed(() =>
  Object.entries(selected.value?.config ?? {}).filter(
    ([key]) => key !== 'evaColumns',
  ),
);
const configLabels: Record<string, string> = {
  minLength: '最小长度',
  maxLength: '最大长度',
  minValue: '最小值',
  maxValue: '最大值',
  ignoreNullValue: '忽略空值',
  includeBoundary: '包含边界',
};
function configValue(key: string, value: unknown) {
  if (['ignoreNullValue', 'includeBoundary'].includes(key))
    return String(value) === '1' ? '是' : '否';
  return text(value);
}
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    :title="title"
    class="!top-[60px] !w-[1200px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
  >
    <Alert
      type="info"
      message="来源质量记录为导入快照，供查阅；接入本地数据连接后可创建并执行质量任务。"
      class="mb-4"
    />
    <div v-if="mode === 'task'" class="source-task">
      <Table
        :columns="columns"
        :data-source="snapshot?.quality?.evaluations ?? []"
        :pagination="false"
        row-key="sourceId"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag color="blue">
              {{
                String((record as ImportedAssetApi.Evaluation).status) === '1'
                  ? '上线'
                  : '下线'
              }}
            </Tag>
          </template>
          <template v-if="column.key === 'actions'">
            <Button
              type="link"
              size="small"
              @click="open('rule', record as ImportedAssetApi.Evaluation)"
            >
              查看
            </Button>
          </template>
        </template>
      </Table>
    </div>
    <template v-else-if="mode === 'schedule'">
      <Form
        :colon="false"
        :label-col="{ style: { width: '120px' } }"
        class="source-schedule"
      >
        <FormItem label="执行策略">
          <Input
            :value="text(snapshot?.quality?.task?.executeStrategy)"
            disabled
          />
        </FormItem>
        <FormItem label="任务优先级">
          <Input :value="text(snapshot?.quality?.task?.priority)" disabled />
        </FormItem>
        <FormItem label="失败重试次数">
          <Input :value="text(snapshot?.quality?.task?.retryCount)" disabled />
        </FormItem>
        <FormItem label="重试间隔">
          <Input
            :value="text(snapshot?.quality?.task?.retryInterval)"
            disabled
          />
        </FormItem>
      </Form>
      <CronTab
        :model-value="snapshot?.quality?.task?.cronExpression || ''"
        disabled
        week-mode="quartz"
      />
    </template>
    <div v-else-if="selected" class="source-rule">
      <h3>基础信息</h3>
      <Form :colon="false" :label-col="{ style: { width: '110px' } }">
        <div class="source-rule__grid">
          <FormItem label="评测名称">
            <Input :value="selected.name || '-'" disabled />
          </FormItem>
          <FormItem label="稽查规则编号">
            <Input :value="selected.ruleCode || '-'" disabled />
          </FormItem>
          <FormItem label="稽查规则名称">
            <Input :value="selected.ruleName || '-'" disabled />
          </FormItem>
          <FormItem label="告警等级">
            <Input
              :value="
                selected.warningLevel === '2'
                  ? '严重'
                  : selected.warningLevel || '-'
              "
              disabled
            />
          </FormItem>
          <FormItem label="状态">
            <Input
              :value="String(selected.status) === '1' ? '上线' : '下线'"
              disabled
            />
          </FormItem>
        </div>
        <FormItem
          v-for="entry in [
            { key: 'description' as const, label: '规则描述' },
            { key: 'errorDescription' as const, label: '错误示例' },
            { key: 'repairAdvice' as const, label: '修复建议' },
            { key: 'whereClause' as const, label: 'Where 条件' },
          ]"
          :key="entry.key"
          :label="entry.label"
        >
          <Input.TextArea
            :value="selected[entry.key] || '-'"
            :rows="3"
            disabled
          />
        </FormItem>
        <h3>规则配置</h3>
        <FormItem label="检查字段">
          <Input :value="selected.columnName || '整表'" disabled />
        </FormItem>
        <FormItem
          v-for="[key, value] in configEntries"
          :key="key"
          :label="configLabels[key] || key"
        >
          <Input :value="configValue(key, value)" disabled />
        </FormItem>
      </Form>
    </div>
    <template #footer><Button @click="modalApi.close()">关闭</Button></template>
  </Modal>
</template>

<style scoped>
.source-task {
  min-height: 420px;
}

.source-rule {
  max-height: 65vh;
  padding: 0 16px;
  overflow-y: auto;
}

.source-rule h3 {
  position: relative;
  padding-left: 12px;
  margin: 12px 0 20px;
  font-weight: 400;
}

.source-rule h3::before {
  position: absolute;
  top: 4px;
  left: 0;
  width: 5px;
  height: 14px;
  content: '';
  background: hsl(var(--primary));
  border-radius: 2px;
}

.source-rule__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 18px;
}

.source-schedule {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (width <= 760px) {
  .source-rule__grid,
  .source-schedule {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

<style scoped src="../../../../table-font.css"></style>
