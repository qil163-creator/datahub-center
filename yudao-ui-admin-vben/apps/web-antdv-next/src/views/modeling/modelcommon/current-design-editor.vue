<script lang="ts" setup>
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Button,
  Checkbox,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Tabs,
  Tag,
  Tooltip,
} from 'antdv-next';

import { createObjectRequestGuard } from '#/hooks/object-request-guard';

import { stableDesignValue } from './current-design-state';
import {
  DATA_TYPE_OPTIONS,
  DORIS_AGG_TYPE_OPTIONS,
  optionLabel,
  TABLE_TYPE_OPTIONS,
} from './data';
import {
  ensureStableFieldUids,
  generateFieldUid,
  isDecimalField,
  needsDecimalConfirmation,
} from './field-state';
import StandardPicker from './standard-picker.vue';
import TypeConfigForm from './type-config-form.vue';
import { useModelingRequest } from './use-modeling-request';

/** 当前设计编辑器：字段和表类型配置使用同一修订原子保存。 */
defineOptions({ name: 'ModelingCurrentDesignEditor' });

const props = defineProps<{
  api: {
    getDesign: (modelId: number | string) => Promise<TableModelApi.Design>;
    publish: (
      modelId: number | string,
      expectedRevision: number | string,
    ) => Promise<boolean>;
    saveDraft: (data: {
      expectedRevision: number | string;
      fields: ModelingModelApi.ModelField[];
      typeConfig: ModelingModelApi.ModelTypeConfig | null;
    }) => Promise<unknown>;
  };
  /** 是否展示 Doris 聚合类型列（Doris 聚合模型） */
  dorisAggregate?: boolean;
  /** 模型编号，变化时重新加载 */
  modelId?: number | string;
  readonly?: boolean;
  /** 是否展示字段关联的数据标准列 */
  showStandard?: boolean;
  /** 模型表类型，决定专属配置渲染哪一段 */
  tableType?: string;
}>();

const emit = defineEmits<{ (e: 'changed'): void }>();

const { hasAccessByCodes } = useAccess();
const accessStore = useAccessStore();
const designIdentity = () =>
  `${accessStore.accessToken}/${accessStore.refreshToken}/${props.modelId}`;
const designRequestGuard = createObjectRequestGuard(designIdentity);

const loading = ref(false);
const failed = ref(false);
const saving = ref(false);
const design = ref<TableModelApi.Design>();
const designRevision = ref<number | string>();
const fields = ref<ModelingModelApi.ModelField[]>([]);
const fieldFilter = ref('');
const activeTab = ref<'fields' | 'typeConfig'>('fields');
const issueRoute = useRoute();
function applyIssueFocus() {
  const section = issueRoute.query.focusSection;
  if (section === 'typeConfig') activeTab.value = 'typeConfig';
  else if (section === 'fields') activeTab.value = 'fields';
  const field = String(issueRoute.query.focusField ?? '');
  fieldFilter.value = field
    ? (fields.value.find((item) => item.fieldUid === field)?.fieldName ?? field)
    : '';
}
watch(
  () => [issueRoute.query.focusSection, issueRoute.query.focusField],
  applyIssueFocus,
);
const typeConfig = ref<ModelingModelApi.ModelTypeConfig>({});
const savedDefinition = ref('');
const dirty = computed(
  () =>
    !!design.value &&
    design.value.status !== 'DISCARDED' &&
    savedDefinition.value !==
      stableDesignValue({ fields: fields.value, typeConfig: typeConfig.value }),
);
const savingTypeConfig = ref(false);
const mutationRequest = useModelingRequest(designIdentity, () => {
  saving.value = false;
  savingTypeConfig.value = false;
});
const repairedFieldUidCount = ref(0);

const editable = computed(
  () =>
    !props.readonly &&
    !loading.value &&
    !saving.value &&
    !savingTypeConfig.value &&
    hasAccessByCodes(['modeling:model:save']) &&
    !!design.value &&
    design.value.status !== 'DISCARDED',
);
const resolvedTableType = computed(() => props.tableType);
const unconfirmedDecimalCount = computed(
  () => fields.value.filter(needsDecimalConfirmation).length,
);
const filteredFields = computed(() => {
  const keyword = fieldFilter.value.trim();
  return keyword
    ? fields.value.filter(
        (field) =>
          field.cnName?.includes(keyword) || field.fieldName?.includes(keyword),
      )
    : fields.value;
});

async function loadDesign() {
  const isCurrent = designRequestGuard.begin();
  const modelId = props.modelId;
  design.value = undefined;
  failed.value = false;
  if (modelId === undefined) {
    syncFields();
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await props.api.getDesign(modelId);
    if (!isCurrent()) return;
    if (String(result.modelId) !== String(modelId))
      throw new Error('当前设计与模型不匹配');
    design.value = result;
    syncFields();
    applyIssueFocus();
  } catch {
    if (isCurrent()) failed.value = true;
  } finally {
    if (isCurrent()) loading.value = false;
  }
}

function syncFields() {
  designRevision.value = design.value?.revision;
  const nextFields = JSON.parse(
    JSON.stringify(design.value?.fields ?? []),
  ) as ModelingModelApi.ModelField[];
  const seen = new Set<string>();
  repairedFieldUidCount.value = nextFields.filter((field) => {
    const uid = field.fieldUid?.trim();
    if (!uid || seen.has(uid)) {
      return true;
    }
    seen.add(uid);
    return false;
  }).length;
  fields.value = ensureStableFieldUids(nextFields);
  typeConfig.value = JSON.parse(
    JSON.stringify(design.value?.typeConfig ?? {}),
  ) as ModelingModelApi.ModelTypeConfig;
  const section = {
    SOURCE: 'source',
    DETAIL: 'detail',
    DIMENSION: 'dimension',
    SUMMARY: 'summary',
    APPLICATION: 'application',
  }[resolvedTableType.value ?? ''] as
    | keyof ModelingModelApi.ModelTypeConfig
    | undefined;
  if (section) typeConfig.value[section] ??= {};
  savedDefinition.value = stableDesignValue({
    fields: fields.value,
    typeConfig: typeConfig.value,
  });
}

watch(
  designIdentity,
  () => {
    design.value = undefined;
    fields.value = [];
    typeConfig.value = {};
    saving.value = false;
    savingTypeConfig.value = false;
    fieldFilter.value = '';
    activeTab.value = 'fields';
    loadDesign();
  },
  { immediate: true },
);

function confirmLeave(): Promise<boolean> {
  if (!dirty.value) return Promise.resolve(true);
  return new Promise((resolve) => {
    Modal.confirm({
      title: '有未保存的设计',
      content: '离开会丢弃本地字段和类型配置修改。是否继续？',
      onOk: () => {
        resolve(true);
      },
      onCancel: () => {
        resolve(false);
      },
    });
  });
}

defineExpose({ reload: () => loadDesign(), confirmLeave, dirty });

/** ==================== 专属配置 ==================== */

async function handleSaveTypeConfig() {
  return handleSaveFields();
}

/** ==================== 字段编辑 ==================== */

function addField() {
  fields.value.push({
    cnName: '',
    fieldName: '',
    dataType: 'varchar',
    dataLength: 255,
    dataPrecision: null,
    sort: fields.value.length + 1,
    comment: '',
    fieldUid: generateFieldUid(),
    pkFlag: false,
    nullableFlag: true,
  });
}

function removeField(field: ModelingModelApi.ModelField) {
  const index = fields.value.indexOf(field);
  if (index !== -1) {
    fields.value.splice(index, 1);
  }
}

async function handleSaveFields() {
  if (!editable.value || props.modelId === undefined) {
    return;
  }
  if (designRevision.value === undefined || designRevision.value === null) {
    message.warning('当前设计缺少修订号，请刷新后重试');
    return;
  }
  const invalid = fields.value.some((field) => !field.fieldName?.trim());
  if (invalid) {
    message.warning('存在字段名称为空的行，请补全后保存');
    return;
  }
  const current = mutationRequest.begin();
  saving.value = true;
  try {
    const data = {
      expectedRevision: designRevision.value,
      fields: JSON.parse(JSON.stringify(fields.value)),
      typeConfig: JSON.parse(JSON.stringify(typeConfig.value)),
    };
    const saved = await props.api.saveDraft(data);
    if (!current() || saved === false) return;
    saving.value = false;
    message.success('设计已保存');
    await loadDesign();
  } finally {
    if (current()) saving.value = false;
  }
}

/** 发布已保存的当前设计，未保存的本地修改不参与发布。 */
function handlePublish() {
  const modelId = props.modelId;
  const expectedRevision = designRevision.value;
  if (
    props.readonly ||
    design.value?.status === 'DISCARDED' ||
    modelId === undefined ||
    expectedRevision === undefined
  )
    return;
  if (dirty.value) {
    message.warning('请先保存当前设计，再发布');
    return;
  }
  const identity = designIdentity();
  Modal.confirm({
    title: '系统提示',
    content:
      '是否发布当前已保存设计？发布会核验修订号及配置完整性；实库建表由部署流程执行。',
    onOk: async () => {
      if (identity !== designIdentity()) return;
      const current = mutationRequest.begin();
      const saved = await props.api.publish(modelId, expectedRevision);
      if (!current() || saved === false) return;
      message.success('发布成功');
      await loadDesign();
      emit('changed');
    },
  });
}

/** ==================== 关联标准 ==================== */

const standardPickerRef = ref<InstanceType<typeof StandardPicker>>();
const standardTarget = ref<ModelingModelApi.ModelField>();

function openStandardPicker(field: ModelingModelApi.ModelField) {
  standardTarget.value = field;
  standardPickerRef.value?.open();
}

/**
 * 关联标准后把标准的治理属性带到字段上：类型、长度、精度、小数位、可空、格式、单位、值域、默认值。
 * 带出的是默认值，用户仍可修改；与其他字段及专属配置一同保存到当前设计。
 */
function handleStandardPicked(
  standard: GovernanceStandardElementApi.StandardElement,
) {
  const field = standardTarget.value;
  if (!field) {
    return;
  }
  field.standardId = standard.id;
  field.standardCode = standard.code;
  field.standardName = standard.name;
  // 快照标准字段类型，供后续比对标准变更
  field.standardColumnType = standard.columnType;
  if (standard.columnType) {
    field.dataType = standard.columnType.toLowerCase();
  }
  if (standard.dataLength !== null && standard.dataLength !== undefined) {
    field.dataLength = standard.dataLength;
  }
  if (standard.dataPrecision !== null && standard.dataPrecision !== undefined) {
    field.dataPrecision = standard.dataPrecision;
  }
  if (standard.dataScale !== null && standard.dataScale !== undefined) {
    field.dataScale = standard.dataScale;
  }
  if (standard.nullableFlag !== null && standard.nullableFlag !== undefined) {
    field.nullableFlag = standard.nullableFlag === 1;
  }
  field.dataFormat = standard.dataFormat || field.dataFormat;
  field.measureUnit = standard.measureUnit || field.measureUnit;
  field.valueRange = standard.valueRange || field.valueRange;
  field.defaultValue = standard.defaultValue || field.defaultValue;
  if (!field.cnName?.trim() && standard.name) {
    field.cnName = standard.name;
  }
}

/** 数据标准列的悬浮说明：把带出的治理属性摆出来，不占表格宽度 */
function standardTooltip(field: ModelingModelApi.ModelField) {
  if (!field.standardName) {
    return '';
  }
  const parts = [
    field.standardCode ? `编码 ${field.standardCode}` : '',
    field.dataScale === null || field.dataScale === undefined
      ? ''
      : `小数位 ${field.dataScale}`,
    field.dataFormat ? `格式 ${field.dataFormat}` : '',
    field.measureUnit ? `单位 ${field.measureUnit}` : '',
    field.valueRange ? `值域 ${field.valueRange}` : '',
    field.defaultValue ? `默认值 ${field.defaultValue}` : '',
  ].filter(Boolean);
  return parts.length > 0 ? parts.join('　') : field.standardName;
}

onBeforeUnmount(() => {
  designRequestGuard.dispose();
});
</script>

<template>
  <div class="current-design-editor">
    <div class="mb-3 flex items-center justify-between gap-3">
      <strong>当前设计</strong>
      <Button
        v-if="!readonly"
        v-access:code="'modeling:model:publish'"
        :disabled="
          loading || saving || !design || design.status === 'DISCARDED'
        "
        type="primary"
        @click="handlePublish"
      >
        <template #icon><IconifyIcon icon="lucide:rocket" /></template>发布
      </Button>
    </div>

    <Tabs v-model:active-key="activeTab" class="mt-2" size="small">
      <Tabs.TabPane key="fields" tab="字段" />
      <Tabs.TabPane key="typeConfig">
        <template #tab>
          专属配置
          <Tag v-if="resolvedTableType" class="ml-1" color="blue">
            {{ optionLabel(TABLE_TYPE_OPTIONS, resolvedTableType) }}
          </Tag>
        </template>
      </Tabs.TabPane>
    </Tabs>

    <!-- 专属配置：按表类型回答「这张表到底是什么」 -->
    <div v-if="activeTab === 'typeConfig'" class="manage-type-config">
      <TypeConfigForm
        v-if="resolvedTableType"
        v-model:value="typeConfig"
        :disabled="!editable"
        :fields="fields"
        :table-type="resolvedTableType"
      />
      <div class="mt-3 flex items-center gap-2">
        <Button
          :disabled="!editable || !resolvedTableType"
          :loading="savingTypeConfig"
          size="small"
          type="primary"
          @click="handleSaveTypeConfig"
        >
          <template #icon>
            <IconifyIcon icon="lucide:save" />
          </template>
          保存设计
        </Button>
        <Tooltip
          v-if="repairedFieldUidCount > 0"
          title="缺失或重复的字段 UID 已生成本地稳定标识，保存时仍由服务端校验唯一性"
        >
          <Tag color="warning">
            已修复 {{ repairedFieldUidCount }} 个字段标识
          </Tag>
        </Tooltip>
      </div>
    </div>

    <!-- 字段编辑 -->
    <div v-show="activeTab === 'fields'" class="manage-toolbar">
      <div class="flex items-center gap-2">
        <Button
          :disabled="!editable"
          size="small"
          type="primary"
          @click="addField"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          添加
        </Button>
        <Button
          :disabled="!editable"
          :loading="saving"
          size="small"
          @click="handleSaveFields"
        >
          <template #icon>
            <IconifyIcon icon="lucide:save" />
          </template>
          保存设计
        </Button>
      </div>
      <div class="flex gap-2">
        <Input
          v-model:value="fieldFilter"
          allow-clear
          class="w-40"
          placeholder="中文名称"
          size="small"
        />
      </div>
    </div>

    <div v-show="activeTab === 'fields'" class="manage-fields">
      <p v-if="unconfirmedDecimalCount" class="mb-2 text-amber-600">
        {{ unconfirmedDecimalCount }}
        个定点数字段的总精度或小数位数待确认。旧长度/精度不会自动转换，请在当前设计中明确填写后发布。
      </p>
      <div class="manage-fields__row manage-fields__row--head">
        <span class="w-32">中文名称</span>
        <span class="w-32">字段名称</span>
        <span class="w-28">数据类型</span>
        <span class="w-20">字符长度</span>
        <span class="w-16">总精度</span>
        <span class="w-20">小数位数</span>
        <span class="w-14">主键</span>
        <span class="w-14">可空</span>
        <span class="w-14">排序</span>
        <span class="flex-1">注释</span>
        <span v-if="props.dorisAggregate" class="w-24">聚合类型</span>
        <span v-if="props.showStandard" class="w-28">数据标准</span>
        <span class="w-24">操作</span>
      </div>
      <div
        v-for="field in filteredFields"
        :key="field.fieldUid"
        class="manage-fields__row"
      >
        <Input
          v-model:value="field.cnName"
          :disabled="!editable"
          class="w-32"
          size="small"
        />
        <Input
          v-model:value="field.fieldName"
          :disabled="!editable"
          class="w-32"
          size="small"
        />
        <Select
          v-model:value="field.dataType"
          :disabled="!editable"
          :options="DATA_TYPE_OPTIONS"
          class="w-28"
          size="small"
        />
        <InputNumber
          v-model:value="field.dataLength as any"
          :disabled="!editable || isDecimalField(field)"
          :min="0"
          class="w-20"
          size="small"
        />
        <InputNumber
          v-model:value="field.dataPrecision as any"
          :disabled="!editable"
          :min="0"
          class="w-16"
          size="small"
        />
        <InputNumber
          :value="field.dataScale ?? undefined"
          :disabled="!editable || !isDecimalField(field)"
          :min="0"
          :precision="0"
          class="w-20"
          size="small"
          @update:value="
            field.dataScale = typeof $event === 'number' ? $event : null
          "
        />
        <Checkbox
          v-model:checked="field.pkFlag as boolean"
          :disabled="!editable"
          class="w-14"
        />
        <Checkbox
          v-model:checked="field.nullableFlag as boolean"
          :disabled="!editable"
          class="w-14"
        />
        <InputNumber
          v-model:value="field.sort as any"
          :disabled="!editable"
          :min="0"
          class="w-14"
          size="small"
        />
        <Input
          v-model:value="field.comment"
          :disabled="!editable"
          class="flex-1"
          size="small"
        />
        <Select
          v-if="props.dorisAggregate"
          v-model:value="field.aggType"
          :disabled="!editable"
          :options="DORIS_AGG_TYPE_OPTIONS"
          allow-clear
          class="w-24"
          placeholder="Key 列留空"
          size="small"
        />
        <Tooltip v-if="props.showStandard" :title="standardTooltip(field)">
          <span class="block w-28 truncate text-xs">
            {{ field.standardName || '-' }}
          </span>
        </Tooltip>
        <span class="flex w-24 items-center gap-2">
          <Button
            v-if="props.showStandard"
            :disabled="!editable"
            class="!p-0"
            size="small"
            type="link"
            @click="openStandardPicker(field)"
            ><template #icon><IconifyIcon icon="lucide:link" /></template>
            关联标准
          </Button>
          <IconifyIcon
            v-if="editable"
            class="cursor-pointer text-red-500"
            icon="lucide:trash-2"
            @click="removeField(field)"
          />
        </span>
      </div>
      <div v-if="filteredFields.length === 0" class="manage-fields__empty">
        暂无字段
      </div>
    </div>

    <StandardPicker ref="standardPickerRef" @picked="handleStandardPicked" />
  </div>
</template>

<style scoped>
.manage-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 8px;
}

.manage-type-config {
  max-height: 420px;
  padding: 12px;
  overflow: auto;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.manage-fields {
  max-height: 340px;
  overflow: hidden auto;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.manage-fields__row {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 5px 8px;
  border-bottom: 1px solid hsl(var(--border));
}

.manage-fields__row:last-child {
  border-bottom: none;
}

.manage-fields__row--head {
  position: sticky;
  top: 0;
  z-index: 1;
  font-size: 12px;
  font-weight: 600;
  background: hsl(var(--muted));
}

.manage-fields__empty {
  padding: 24px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  text-align: center;
}
</style>
