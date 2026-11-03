<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { SafetyRuleApi } from '#/api/governance/rule';
import type { SafetyStaticTaskApi } from '#/api/governance/statictask';

import { computed, onBeforeUnmount, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictLabel } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Input,
  InputNumber,
  message,
  RadioGroup,
  Select,
  TextArea,
} from 'antdv-next';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
} from '#/api/dataforge/database';
import { getRulePage } from '#/api/governance/rule';
import {
  createSafetyStaticTask,
  getSafetyStaticTask,
  getSafetyStaticTaskColumnList,
  updateSafetyStaticTask,
} from '#/api/governance/statictask';
import { CronTab } from '#/components/cron-tab';
import { $t } from '#/locales';
import { createLatestWins, sameResourceId } from '#/utils/resource-id';

import { WRITE_MODE_OPTIONS } from '../data';

defineOptions({ name: 'SafetyStaticTaskForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<SafetyStaticTaskApi.Task>({});
const fieldRules = ref<SafetyStaticTaskApi.FieldRule[]>([]);
const scheduleType = ref<'CRON' | 'MANUAL'>('MANUAL');

const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const sourceTables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const targetTables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const sourceColumns = ref<SafetyStaticTaskApi.TableColumn[]>([]);
const rules = ref<SafetyRuleApi.Rule[]>([]);
const sourceTablesLoading = ref(false);
const targetTablesLoading = ref(false);
const columnsLoading = ref(false);
const openGuard = createLatestWins();
const sourceTablesGuard = createLatestWins();
const targetTablesGuard = createLatestWins();
const columnsGuard = createLatestWins();

function invalidateRequests() {
  openGuard.invalidate();
  sourceTablesGuard.invalidate();
  targetTablesGuard.invalidate();
  columnsGuard.invalidate();
  sourceTablesLoading.value = false;
  targetTablesLoading.value = false;
  columnsLoading.value = false;
}

onBeforeUnmount(invalidateRequests);

const getTitle = computed(() =>
  model.id
    ? $t('ui.actionTitle.edit', ['静态脱敏任务'])
    : $t('ui.actionTitle.create', ['静态脱敏任务']),
);

function databaseOptions() {
  return databases.value.map((db) => ({
    label: `${db.name}${db.usageType === 2 ? '（中台库）' : ''}`,
    value: db.id,
  }));
}

function tableOptions(tables: FoundationDatabaseApi.DatabaseTable[]) {
  return tables.map((table) => ({
    label: table.remarks
      ? `${table.tableName}（${table.remarks}）`
      : table.tableName,
    value: table.tableName,
  }));
}

function columnOptions() {
  return sourceColumns.value.map((column) => ({
    label: column.typeName
      ? `${column.columnName}（${column.typeName}）`
      : column.columnName,
    value: column.columnName,
  }));
}

function ruleOptions() {
  return rules.value.map((rule) => ({
    label: `${rule.ruleName ?? rule.id}${
      rule.methodCode
        ? ` · ${getDictLabel(DICT_TYPE.METHOD_CODE, rule.methodCode)}`
        : ''
    }`,
    value: rule.id,
  }));
}

function resetModel() {
  Object.assign(model, {
    id: undefined,
    taskName: undefined,
    sourceDatabaseId: undefined,
    sourceTableName: undefined,
    targetDatabaseId: undefined,
    targetTableName: undefined,
    writeMode: 'OVERWRITE',
    batchSize: 1000,
    fieldRules: undefined,
    cronExpression: undefined,
    status: CommonStatusEnum.ENABLE,
    description: undefined,
    createTime: undefined,
  });
  fieldRules.value = [];
  scheduleType.value = 'MANUAL';
  sourceTables.value = [];
  targetTables.value = [];
  sourceColumns.value = [];
  databases.value = [];
  rules.value = [];
}

async function loadTables(
  databaseId: SafetyStaticTaskApi.Id | undefined,
  target: 'source' | 'target',
) {
  const loading =
    target === 'source' ? sourceTablesLoading : targetTablesLoading;
  const list = target === 'source' ? sourceTables : targetTables;
  const guard = target === 'source' ? sourceTablesGuard : targetTablesGuard;
  const ticket = guard.next();
  if (!databaseId) {
    list.value = [];
    loading.value = false;
    return;
  }
  list.value = [];
  loading.value = true;
  try {
    const result = await getDatabaseTableList(databaseId);
    if (ticket.isCurrent()) list.value = result ?? [];
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

async function loadColumns(databaseId?: SafetyStaticTaskApi.Id, tableName?: string) {
  const ticket = columnsGuard.next();
  if (!databaseId || !tableName) {
    sourceColumns.value = [];
    columnsLoading.value = false;
    return;
  }
  sourceColumns.value = [];
  columnsLoading.value = true;
  try {
    const result = await getSafetyStaticTaskColumnList(databaseId, tableName);
    if (ticket.isCurrent()) sourceColumns.value = result ?? [];
  } finally {
    if (ticket.isCurrent()) columnsLoading.value = false;
  }
}

function handleSourceDatabaseChange(value?: SafetyStaticTaskApi.Id) {
  model.sourceTableName = undefined;
  fieldRules.value = [];
  void loadColumns();
  void loadTables(value, 'source');
}

function handleTargetDatabaseChange(value?: SafetyStaticTaskApi.Id) {
  model.targetTableName = undefined;
  void loadTables(value, 'target');
}

function handleSourceTableChange(value?: string) {
  fieldRules.value = [];
  void loadColumns(model.sourceDatabaseId, value);
}

function handleScheduleTypeChange(value: any) {
  if (value === 'CRON' && !model.cronExpression) {
    model.cronExpression = '0 0 2 * * ?';
  }
}

function handleAddFieldRule() {
  fieldRules.value.push({ columnName: '', ruleId: undefined as any });
}

function handleRemoveFieldRule(index: number) {
  fieldRules.value.splice(index, 1);
}

function validate(): null | string {
  if (!model.taskName?.trim()) {
    return '请填写任务名称';
  }
  if (!model.sourceDatabaseId) {
    return '请选择源数据源';
  }
  if (!model.sourceTableName) {
    return '请选择源表';
  }
  if (!model.targetDatabaseId) {
    return '请选择目标数据源';
  }
  if (!model.targetTableName) {
    return '请选择目标表';
  }
  if (
    sameResourceId(model.sourceDatabaseId, model.targetDatabaseId) &&
    model.sourceTableName?.toLowerCase() ===
      model.targetTableName?.toLowerCase()
  ) {
    return '目标表不能与源表相同，静态脱敏不允许覆盖生产原表';
  }
  if (!model.writeMode) {
    return '请选择写入模式';
  }
  if (scheduleType.value === 'CRON' && !model.cronExpression?.trim()) {
    return '定时任务请配置 Cron 表达式';
  }
  if (sourceTablesLoading.value || targetTablesLoading.value || columnsLoading.value) {
    return '数据源信息正在加载，请稍候';
  }
  if (fieldRules.value.some((item) => !item.columnName || !item.ruleId)) {
    return '请为每一行选择字段和脱敏规则，或删除未完成的行';
  }
  const validRules = fieldRules.value;
  if (validRules.length === 0) {
    return '请至少配置一个脱敏字段';
  }
  const columnNames = validRules.map((item) => item.columnName);
  if (new Set(columnNames).size !== columnNames.length) {
    return '脱敏字段不能重复';
  }
  return null;
}

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    const error = validate();
    if (error) {
      message.error(error);
      return;
    }
    modalApi.lock();
    const payload: SafetyStaticTaskApi.Task = {
      id: model.id,
      taskName: model.taskName?.trim(),
      sourceDatabaseId: model.sourceDatabaseId,
      sourceTableName: model.sourceTableName,
      targetDatabaseId: model.targetDatabaseId,
      targetTableName: model.targetTableName,
      writeMode: model.writeMode,
      batchSize: model.batchSize ?? 1000,
      fieldRules: [...fieldRules.value],
      cronExpression:
        scheduleType.value === 'CRON' ? model.cronExpression?.trim() : '',
      status: model.status ?? CommonStatusEnum.ENABLE,
      description: model.description,
    };
    try {
      await (model.id
        ? updateSafetyStaticTask(payload)
        : createSafetyStaticTask(payload));
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      invalidateRequests();
      resetModel();
      return;
    }
    invalidateRequests();
    resetModel();
    const ticket = openGuard.next();
    modalApi.lock();
    try {
      const [databaseList, rulePage] = await Promise.all([
        getDatabaseSimpleList(),
        getRulePage({ pageNo: 1, pageSize: 100, status: 0 }),
      ]);
      if (!ticket.isCurrent()) return;
      databases.value = databaseList ?? [];
      if (!Number.isSafeInteger(rulePage.total) || rulePage.total < 0 || rulePage.total > 10_000) {
        message.error('脱敏规则数量异常或超过 10000 条，请联系管理员');
        return;
      }
      const allRules = [...(rulePage.list ?? [])];
      if (rulePage.total > 0 && allRules.length === 0) {
        message.error('脱敏规则列表未能完整读取，请重新打开表单重试');
        return;
      }
      let pageNo = 1;
      while (allRules.length < rulePage.total) {
        const page = await getRulePage({ pageNo: ++pageNo, pageSize: 100, status: 0 });
        if (!ticket.isCurrent()) return;
        if (!page.list?.length) {
          message.error('脱敏规则列表未能完整读取，请重新打开表单重试');
          return;
        }
        allRules.push(...page.list);
      }
      rules.value = allRules.filter(
        (item) => item.currentVerId !== undefined && item.currentVerId !== null,
      );
      const data = modalApi.getData() as null | SafetyStaticTaskApi.Task;
      if (!data?.id) {
        return;
      }
      const detail = await getSafetyStaticTask(data.id);
      if (!ticket.isCurrent()) return;
      Object.assign(model, detail);
      fieldRules.value = [...(detail.fieldRules ?? [])];
      scheduleType.value = detail.cronExpression ? 'CRON' : 'MANUAL';
      await Promise.all([
        loadTables(detail.sourceDatabaseId, 'source'),
        loadTables(detail.targetDatabaseId, 'target'),
        loadColumns(detail.sourceDatabaseId, detail.sourceTableName),
      ]);
    } finally {
      if (ticket.isCurrent()) modalApi.unlock();
    }
  },
});
</script>

<template>
  <ModalComponent :title="getTitle">
    <div class="static-task-form governance-form-item-horizontal">
      <div class="static-task-form__section">基本信息</div>
      <div class="static-task-form__grid">
        <div class="form-item form-item--span2">
          <label class="form-item__label form-item__label--required">
            任务名称
          </label>
          <Input
            v-model:value="model.taskName"
            allow-clear
            placeholder="如：用户表脱敏同步到测试库"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            写入模式
          </label>
          <RadioGroup
            v-model:value="model.writeMode"
            :options="WRITE_MODE_OPTIONS"
            option-type="button"
          />
          <div class="form-item__tip">
            全量覆盖会先清空目标表再写入；追加只往目标表插入
          </div>
        </div>
        <div class="form-item">
          <label class="form-item__label">批次大小</label>
          <InputNumber
            v-model:value="model.batchSize"
            :min="1"
            :precision="0"
            class="w-full"
            placeholder="默认 1000"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            调度方式
          </label>
          <RadioGroup
            v-model:value="scheduleType"
            :options="[
              { label: '仅手动', value: 'MANUAL' },
              { label: '定时执行', value: 'CRON' },
            ]"
            option-type="button"
            @change="handleScheduleTypeChange"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">状态</label>
          <RadioGroup
            v-model:value="model.status"
            :options="[
              { label: '上线', value: 0 },
              { label: '下线', value: 1 },
            ]"
            option-type="button"
          />
          <div class="form-item__tip">下线任务不参与定时调度</div>
        </div>
        <div v-if="scheduleType === 'CRON'" class="form-item form-item--span2">
          <label class="form-item__label form-item__label--required">
            Cron 表达式
          </label>
          <CronTab
            v-model="model.cronExpression"
            placeholder="例如：0 0 2 * * ?（每天凌晨 2 点）"
          />
        </div>
        <div class="form-item form-item--span2">
          <label class="form-item__label">任务描述</label>
          <TextArea
            v-model:value="model.description"
            :auto-size="{ minRows: 2, maxRows: 4 }"
            :maxlength="500"
            placeholder="说明用途，例如给开发/测试环境提供脱敏后的生产样本"
            show-count
          />
        </div>
      </div>

      <div class="static-task-form__section">源表与目标表</div>
      <div class="static-task-form__grid">
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            源数据源
          </label>
          <Select
            v-model:value="model.sourceDatabaseId"
            :options="databaseOptions()"
            allow-clear
            option-filter-prop="label"
            placeholder="请选择源数据源"
            show-search
            @change="handleSourceDatabaseChange"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">源表</label>
          <Select
            v-model:value="model.sourceTableName"
            :loading="sourceTablesLoading"
            :options="tableOptions(sourceTables)"
            allow-clear
            option-filter-prop="label"
            placeholder="请先选择源数据源"
            show-search
            @change="handleSourceTableChange"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            目标数据源
          </label>
          <Select
            v-model:value="model.targetDatabaseId"
            :options="databaseOptions()"
            allow-clear
            option-filter-prop="label"
            placeholder="请选择目标数据源"
            show-search
            @change="handleTargetDatabaseChange"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            目标表
          </label>
          <Select
            v-model:value="model.targetTableName"
            :loading="targetTablesLoading"
            :options="tableOptions(targetTables)"
            allow-clear
            option-filter-prop="label"
            placeholder="目标表需已存在，且包含源表全部字段"
            show-search
          />
          <div class="form-item__tip">
            本任务不负责建表，目标表结构需与源表一致或至少覆盖源表字段
          </div>
        </div>
      </div>

      <div class="static-task-form__section">字段脱敏配置</div>
      <div class="form-item__tip mb-2">
        未配置的字段会原样复制到目标表。请至少选择一个敏感字段并绑定脱敏规则库中的规则。
      </div>
      <div class="field-rule-table">
        <div class="field-rule-table__head">
          <span>源表字段</span>
          <span>脱敏规则</span>
          <span></span>
        </div>
        <div
          v-for="(item, index) in fieldRules"
          :key="index"
          class="field-rule-table__row"
        >
          <Select
            v-model:value="item.columnName"
            :loading="columnsLoading"
            :options="columnOptions()"
            allow-clear
            option-filter-prop="label"
            placeholder="请选择字段"
            show-search
          />
          <Select
            v-model:value="item.ruleId"
            :options="ruleOptions()"
            allow-clear
            option-filter-prop="label"
            placeholder="请选择脱敏规则"
            show-search
          />
          <Button danger type="link" @click="handleRemoveFieldRule(index)">
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            删除
          </Button>
        </div>
        <Button type="dashed" block @click="handleAddFieldRule">
          + 添加脱敏字段
        </Button>
      </div>
    </div>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';

.static-task-form {
  padding: 0 16px 8px;
}

.static-task-form__section {
  margin: 8px 0 12px;
  font-size: 16px;
  font-weight: 600;
  color: hsl(var(--foreground));
}

.static-task-form__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 16px;
  margin-bottom: 16px;
}

.form-item--span2 {
  grid-column: span 2;
}

.field-rule-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-rule-table__head,
.field-rule-table__row {
  display: grid;
  grid-template-columns: 1fr 1fr 64px;
  gap: 8px;
  align-items: center;
}

.field-rule-table__head {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>
