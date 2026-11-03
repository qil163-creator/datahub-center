<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetQualityTaskApi } from '#/api/asset/qualitytask';
import type { QualityRuleApi } from '#/api/governance/quality/rule';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Button,
  Form,
  FormItem,
  Input,
  message,
  Radio,
  RadioGroup,
  Select,
  Table,
  Tag,
  Tooltip,
} from 'antdv-next';

import {
  createAssetQualityTask,
  updateAssetQualityTask,
} from '#/api/asset/qualitytask';
import { getQualityRuleSimpleList } from '#/api/governance/quality/rule';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import { columnDisplayName, SCHEDULE_TYPE_OPTIONS } from '../../data';
import RulePicker from './rule-picker.vue';

defineOptions({ name: 'AssetLegacyQualityTaskForm' });

const emit = defineEmits<{ (e: 'success'): void }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const allowed = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:quality-task:manage']),
);
const canReadRules = computed(
  () => allowed.value && hasAccessByCodes(['governance:quality-rule:query']),
);
const saves = createLatestWins();
const recommendations = createLatestWins();
let opened = false;
let saving = false;

interface RuleRow extends AssetQualityTaskApi.QualityRule {
  checkLevel?: string;
  description?: string;
  strategyKey?: string;
}

const assetId = ref<DataAssetApi.Id>(0);
const columns = ref<DataAssetApi.MetadataColumn[]>([]);
const editingId = ref<DataAssetApi.Id>();
const form = reactive({
  taskName: '',
  scheduleType: 0,
  cronExpression: '',
  enabled: 1,
  remark: '',
});
const rules = ref<RuleRow[]>([]);
const recommending = ref(false);

const title = computed(() =>
  editingId.value ? '修改资产质量任务' : '新增资产质量任务',
);

const columnOptions = computed(() =>
  columns.value.map((column) => ({
    label: `${column.columnName}（${columnDisplayName(column)}）`,
    value: column.id!,
  })),
);

const ruleTableColumns = [
  { title: '编号', key: 'index', width: 60 },
  { title: '评测名称', key: 'ruleName', width: 200 },
  { title: '评测字段', key: 'column', width: 230 },
  { title: '质量规则', key: 'auditRule', width: 170 },
  { title: '规则描述', key: 'description', ellipsis: true },
  { title: '质量维度', key: 'dimension', width: 110 },
  { title: '状态', key: 'status', width: 90 },
  { title: '操作', key: 'actions', width: 96, fixed: 'right' as const },
];

const [RulePickerModal, rulePickerApi] = useVbenModal({
  connectedComponent: RulePicker,
  destroyOnClose: true,
});

function addRuleFromPicker(rule: QualityRuleApi.Rule) {
  if (!opened || !allowed.value) return;
  rules.value.push({
    qualityRuleId: rule.id!,
    ruleName: rule.ruleName!,
    dimensionName: rule.qualityDim,
    checkLevel: rule.checkLevel,
    description: rule.description,
    strategyKey: rule.strategyKey,
    metaColumnId: undefined,
  });
}

function onColumnChange(row: RuleRow, metaColumnId?: DataAssetApi.Id) {
  row.metaColumnId = metaColumnId;
  const column = columns.value.find((item) => item.id === metaColumnId);
  row.columnName = column?.columnName;
}

function removeRule(index: number) {
  rules.value.splice(index, 1);
}

function isCharType(type?: string) {
  return /char|text|string|clob/i.test(type ?? '');
}

/**
 * 按字段特征自动匹配启用中的质量规则（长度 / 小数位 / 唯一性 / 码值），
 * 已存在的「规则 + 字段」组合会跳过
 */
async function recommendRules() {
  if (!opened || !canReadRules.value || recommending.value) return;
  const ticket = recommendations.next();
  recommending.value = true;
  try {
    const list = await getQualityRuleSimpleList();
    if (!ticket.isCurrent() || !opened) return;
    const byStrategy = new Map<string, QualityRuleApi.Rule>();
    (list ?? []).forEach((rule) => {
      if (rule.strategyKey && !byStrategy.has(rule.strategyKey)) {
        byStrategy.set(rule.strategyKey, rule);
      }
    });
    const existing = new Set(
      rules.value.map((row) => `${row.qualityRuleId}:${row.metaColumnId ?? 0}`),
    );
    let added = 0;
    const push = (
      rule: QualityRuleApi.Rule | undefined,
      column: DataAssetApi.MetadataColumn,
    ) => {
      if (!rule?.id) return;
      const key = `${rule.id}:${column.id}`;
      if (existing.has(key)) return;
      existing.add(key);
      rules.value.push({
        qualityRuleId: rule.id,
        ruleName: `${columnDisplayName(column)}-${rule.ruleName}`,
        dimensionName: rule.qualityDim,
        checkLevel: rule.checkLevel,
        description: rule.description,
        strategyKey: rule.strategyKey,
        metaColumnId: column.id,
        columnName: column.columnName,
      });
      added += 1;
    };
    columns.value.forEach((column) => {
      const standardRules = (list ?? []).filter(
        (rule) =>
          rule.standardElementId !== null &&
          rule.standardElementId !== undefined &&
          column.standardElementId !== null &&
          column.standardElementId !== undefined &&
          String(rule.standardElementId) === String(column.standardElementId),
      );
      if (standardRules.length > 0) {
        standardRules.forEach((rule) => push(rule, column));
        return;
      }
      if (column.pkFlag === 1) {
        push(byStrategy.get('COMPOSITE_UNIQUENESS_VALIDATION'), column);
      }
      if (column.dataLength && isCharType(column.dataType)) {
        push(byStrategy.get('LENGTH_VALIDATION'), column);
      }
      if ((column.dataScale ?? 0) > 0) {
        push(byStrategy.get('DECIMAL_PRECISION_VALIDATION'), column);
      }
    });
    if (added > 0) {
      message.success(`已根据字段特征推荐 ${added} 条评测规则，请确认后保存`);
    } else {
      message.info('当前表没有可自动匹配的新质量规则');
    }
  } finally {
    if (ticket.isCurrent()) recommending.value = false;
  }
}

const [Modal, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    if (!opened || !allowed.value || saving) return;
    if (!form.taskName.trim()) {
      message.warning('请输入任务名称');
      return;
    }
    if (form.scheduleType === 1 && !form.cronExpression.trim()) {
      message.warning('定时调度必须填写 Cron 表达式');
      return;
    }
    if (rules.value.length === 0) {
      message.warning('请至少添加一条评测规则');
      return;
    }
    const missingColumn = rules.value.find(
      (row) => row.checkLevel !== 'TABLE' && !row.metaColumnId,
    );
    if (missingColumn) {
      message.warning(`字段级规则“${missingColumn.ruleName}”必须选择评测字段`);
      return;
    }
    const ticket = saves.next();
    saving = true;
    modalApi.lock();
    try {
      const payload: AssetQualityTaskApi.QualityTask = {
        id: editingId.value,
        assetId: assetId.value,
        taskName: form.taskName.trim(),
        scheduleType: form.scheduleType,
        cronExpression:
          form.scheduleType === 1 ? form.cronExpression.trim() : undefined,
        enabled: form.enabled,
        remark: form.remark || undefined,
        rules: rules.value.map((row) => ({
          qualityRuleId: row.qualityRuleId,
          ruleName: row.ruleName,
          dimensionName: row.dimensionName,
          metaColumnId:
            row.checkLevel === 'TABLE' ? undefined : row.metaColumnId,
          columnName: row.checkLevel === 'TABLE' ? undefined : row.columnName,
          remark: row.description,
        })),
      };
      await (editingId.value
        ? updateAssetQualityTask(payload)
        : createAssetQualityTask(payload));
      if (!ticket.isCurrent() || !opened) return;
      message.success('保存成功');
      await modalApi.close();
      emit('success');
    } finally {
      if (ticket.isCurrent()) {
        saving = false;
        modalApi.unlock();
      }
    }
  },
  async onOpenChange(isOpen: boolean) {
    opened = isOpen;
    saves.invalidate();
    recommendations.invalidate();
    saving = false;
    recommending.value = false;
    rulePickerApi.close();
    if (!isOpen || !allowed.value) {
      rules.value = [];
      return;
    }
    const data = modalApi.getData() as {
      assetId: number | string;
      columns: DataAssetApi.MetadataColumn[];
      task?: AssetQualityTaskApi.QualityTask;
    };
    assetId.value = data.assetId;
    columns.value = data.columns ?? [];
    editingId.value = data.task?.id;
    Object.assign(form, {
      taskName: data.task?.taskName ?? '',
      scheduleType: data.task?.scheduleType ?? 0,
      cronExpression: data.task?.cronExpression ?? '',
      enabled: data.task?.enabled ?? 1,
      remark: data.task?.remark ?? '',
    });
    rules.value = (data.task?.rules ?? []).map((rule) => ({
      ...rule,
      description: rule.remark,
      checkLevel: rule.metaColumnId ? 'FIELD' : 'TABLE',
    }));
  },
});
watch(
  owner,
  () => {
    opened = false;
    saves.invalidate();
    recommendations.invalidate();
    rules.value = [];
    rulePickerApi.close();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  saves.invalidate();
  recommendations.invalidate();
  rulePickerApi.close();
});
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    :title="title"
    class="w-[800px]"
  >
    <RulePickerModal @select="addRuleFromPicker" />
    <Form
      :label-col="{ style: { width: '130px' } }"
      :wrapper-col="{ style: { flex: '1 1 0' } }"
      class="mx-2"
    >
      <div class="grid grid-cols-2 gap-x-4">
        <FormItem label="任务名称" required>
          <Input
            v-model:value="form.taskName"
            :maxlength="200"
            placeholder="如：日报表核心字段完整性稽查"
          />
        </FormItem>
        <FormItem label="调度方式">
          <RadioGroup v-model:value="form.scheduleType">
            <Radio
              v-for="option in SCHEDULE_TYPE_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </Radio>
          </RadioGroup>
        </FormItem>
        <FormItem v-if="form.scheduleType === 1" label="Cron 表达式" required>
          <Input
            v-model:value="form.cronExpression"
            :maxlength="128"
            placeholder="如：0 0 2 * * ?"
          />
        </FormItem>
        <FormItem label="是否启用">
          <RadioGroup v-model:value="form.enabled">
            <Radio :value="1">启用</Radio>
            <Radio :value="0">停用</Radio>
          </RadioGroup>
        </FormItem>
      </div>
      <FormItem
        :label-col="{ span: 2 }"
        :wrapper-col="{ span: 21 }"
        label="备注"
      >
        <Input.TextArea
          v-model:value="form.remark"
          :maxlength="512"
          :rows="2"
        />
      </FormItem>
    </Form>

    <div class="mx-2 mb-2 flex items-center gap-2">
      <Button
        type="primary"
        :disabled="!canReadRules"
        @click="rulePickerApi.open()"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        新增
      </Button>
      <Tooltip
        title="按字段特征（主键 / 长度 / 小数位 / 关联数据元）自动匹配启用中的质量规则"
      >
        <Button
          :loading="recommending"
          :disabled="!canReadRules"
          @click="recommendRules"
        >
          <template #icon><IconifyIcon icon="lucide:wand-sparkles" /></template>
          获取质量规则
        </Button>
      </Tooltip>
      <span class="text-muted-foreground text-xs">
        字段级规则需指定评测字段；表级规则作用于整表
      </span>
    </div>

    <Table
      :columns="ruleTableColumns"
      :data-source="rules"
      :pagination="false"
      :scroll="{ x: 1200, y: 320 }"
      :row-key="
        (row: any) =>
          `${row.qualityRuleId}-${row.metaColumnId ?? 0}-${row.ruleName}`
      "
      class="mx-2"
      size="small"
    >
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'index'">{{ index + 1 }}</template>
        <template v-else-if="column.key === 'ruleName'">
          <Input
            v-model:value="(record as any).ruleName"
            :maxlength="200"
            size="small"
          />
        </template>
        <template v-else-if="column.key === 'column'">
          <Select
            v-if="(record as any).checkLevel !== 'TABLE'"
            :options="columnOptions"
            :value="(record as any).metaColumnId"
            allow-clear
            class="w-full"
            option-filter-prop="label"
            placeholder="选择评测字段"
            show-search
            size="small"
            @change="(value: any) => onColumnChange(record as any, value)"
          />
          <Tag v-else color="gold">整表</Tag>
        </template>
        <template v-else-if="column.key === 'auditRule'">
          {{
            (record as any).strategyKey ||
            `规则 #${(record as any).qualityRuleId}`
          }}
        </template>
        <template v-else-if="column.key === 'description'">
          <Tooltip :title="(record as any).description">
            {{ (record as any).description || '-' }}
          </Tooltip>
        </template>
        <template v-else-if="column.key === 'dimension'">
          <Tag :bordered="false" color="blue">
            {{ (record as any).dimensionName || '-' }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'status'">
          <Tag :color="form.enabled === 1 ? 'green' : 'default'">
            {{ form.enabled === 1 ? '启用' : '停用' }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button danger size="small" type="link" @click="removeRule(index)">
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            删除
          </Button>
        </template>
      </template>
      <template #emptyText>
        <div class="text-muted-foreground py-8">
          暂无评测规则，点击“新增”或“获取质量规则”添加
        </div>
      </template>
    </Table>
  </Modal>
</template>

<style scoped src="../../../../table-font.css"></style>
