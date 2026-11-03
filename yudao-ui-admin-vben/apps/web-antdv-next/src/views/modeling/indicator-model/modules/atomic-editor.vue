<script setup lang="ts">
import type { BusinessEntityApi } from '#/api/modeling/business-entity';
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions, useAntdDesignTokens } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  ConfigProvider,
  Input,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getBusinessEntityPage } from '#/api/modeling/business-entity';
import {
  createIndicator,
  getIndicator,
  getIndicatorSourceFields,
  updateIndicator,
} from '#/api/modeling/indicator-model';
import { useObjectRequest } from '#/hooks/use-object-request';
import domainIcon from '#/views/modeling/logictable/assets/data-section-default-icon-small.svg';
import { QdIcon } from '#/views/modeling/logictable/icons';

import { indicatorSaveData } from '../data';
import {
  ATOMIC_AGGREGATIONS,
  atomicExpression,
  atomicSourceMatches,
  atomicTimeFields,
  atomicUnitTree,
} from './atomic-state';
import EntitySelector from './entity-selector.vue';
import { useMetricOptions } from './use-metric-options';

import '../atomic.css';

const { tokens } = useAntdDesignTokens();

type AtomicValues = Partial<IndicatorModelApi.Save> & {
  contactNumber?: string;
  entityId?: IndicatorModelApi.Id;
  status?: IndicatorModelApi.Status;
};
export interface AtomicOpenData {
  id?: IndicatorModelApi.Id;
  cloneId?: IndicatorModelApi.Id;
  indicatorType: IndicatorModelApi.Type;
}
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const options = useMetricOptions();
const form = reactive<AtomicValues>({});
const original = ref<IndicatorModelApi.Model>();
const loadedDetail = ref<IndicatorModelApi.Model>();
const entities = ref<BusinessEntityApi.Entity[]>([]);
const fields = ref<IndicatorModelApi.SourceField[]>([]);
const opened = ref(false);
const loading = ref(false);
const saving = ref(false);
const entityLoading = ref(false);
const sourceLoading = ref(false);
const failed = ref('');
const saveError = ref('');
const sourceError = ref('');
const entityError = ref('');
const errors = ref<Record<string, string>>({});
const openData = ref<AtomicOpenData>();
const identity = () =>
  `${opened.value}/${openData.value?.id ?? openData.value?.cloneId ?? 'new'}/${access.accessCodes.join(',')}`;
const request = useObjectRequest(identity);
const entityRequest = useObjectRequest(identity);
const sourceRequest = useObjectRequest(identity);
const canSave = computed(() =>
  hasAccessByCodes([
    original.value
      ? 'modeling:indicator-model:update'
      : 'modeling:indicator-model:create',
  ]),
);
const readonly = computed(() => original.value?.status === 'ACTIVE');
const disabled = computed(
  () => loading.value || saving.value || readonly.value || !canSave.value,
);
const ready = computed(
  () =>
    opened.value &&
    !loading.value &&
    !saving.value &&
    !sourceLoading.value &&
    !entityLoading.value &&
    !failed.value &&
    !sourceError.value &&
    !entityError.value &&
    !options.error.value &&
    !options.loading.value &&
    !readonly.value &&
    canSave.value,
);
const domainOptions = computed(() =>
  options.domains.value
    .filter(
      (item) =>
        item.status === 0 || String(item.id) === String(form.dataDomainId),
    )
    .map((item) => ({
      value: String(item.id),
      label: item.name || String(item.id),
    })),
);
const subjectOptions = computed(() =>
  options.subjects.value
    .filter(
      (item) =>
        String(item.dataDomainId) === String(form.dataDomainId) &&
        (item.status === 0 || String(item.id) === String(form.subjectDomainId)),
    )
    .map((item) => ({
      value: String(item.id),
      label: item.code
        ? `${item.name} / ${item.code}`
        : item.name || String(item.id),
    })),
);
const createdEntityIds = computed(() =>
  options.models.value
    .filter(
      (item) =>
        item.enabled === 1 &&
        item.status !== 'DISCARDED' &&
        ['DETAIL', 'DIMENSION'].includes(item.tableType || '') &&
        String(item.dataDomainId) === String(form.dataDomainId) &&
        String(item.subjectDomainId) === String(form.subjectDomainId),
    )
    .flatMap((item) => (item.entityId === undefined ? [] : [item.entityId])),
);
const ownerOptions = computed(() => {
  const result = options.users.value.map((item) => ({
    value: String(item.id),
    label: item.nickname || item.username,
  }));
  if (
    form.ownerUserId !== undefined &&
    !result.some((item) => item.value === String(form.ownerUserId))
  )
    result.push({
      value: String(form.ownerUserId),
      label: loadedDetail.value?.ownerUserName || `用户 ${form.ownerUserId}`,
    });
  return result;
});
const sourceOptions = computed(() => {
  const result = options.models.value
    .filter((item) => atomicSourceMatches(item, form))
    .map((item) => ({
      value: String(item.id),
      label:
        item.tableNameNotation || item.code || item.name || String(item.id),
      disabled: false,
    }));
  if (
    form.sourceModelId !== undefined &&
    !result.some((item) => item.value === String(form.sourceModelId))
  )
    result.unshift({
      value: String(form.sourceModelId),
      label:
        loadedDetail.value?.sourceModelCode ||
        loadedDetail.value?.sourceModelName ||
        `来源 ${form.sourceModelId}`,
      disabled: true,
    });
  return result;
});
const fieldOptions = computed(() =>
  fields.value.map((item) => ({
    value: item.fieldUid,
    label: item.cnName ? `${item.fieldName}（${item.cnName}）` : item.fieldName,
  })),
);
const timeOptions = computed(() =>
  atomicTimeFields(fields.value).map((item) => ({
    value: item.fieldUid,
    label: item.cnName ? `${item.fieldName}（${item.cnName}）` : item.fieldName,
  })),
);
const expression = computed(() =>
  atomicExpression(
    form.aggregation,
    fields.value.find((item) => item.fieldUid === form.valueFieldUid),
  ),
);
const timelinessOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string'),
);
const resultOptions = [
  'TINYINT',
  'INTEGER',
  'BIGINT',
  'DECIMAL',
  'NUMERIC',
  'FLOAT',
  'DOUBLE',
  'NUMBER',
  'SMALLINT',
  'REAL',
  'STRING',
  'DATE',
  'DATETIME',
].map((value) => ({ label: value, value }));
const unitTree = computed(() =>
  atomicUnitTree(
    getDictOptions(DICT_TYPE.MODELING_INDICATOR_UNIT, 'string'),
    form.unit,
  ),
);
function popupContainer(trigger?: HTMLElement) {
  return trigger?.closest<HTMLElement>('.atomic-modal') || document.body;
}
for (const key of [
  'code',
  'name',
  'dataDomainId',
  'subjectDomainId',
  'entityId',
  'ownerUserId',
  'dataFreshness',
  'sourceModelId',
  'aggregation',
  'valueFieldUid',
  'timeFieldUid',
  'resultDataType',
  'definition',
] as const)
  watch(
    () => form[key],
    () => Reflect.deleteProperty(errors.value, key),
  );

function resetSource() {
  sourceRequest.begin();
  sourceLoading.value = false;
  sourceError.value = '';
  fields.value = [];
  form.sourceModelId = undefined;
  form.valueFieldUid = undefined;
  form.timeFieldUid = undefined;
}
async function loadEntities() {
  const current = entityRequest.begin();
  entities.value = [];
  entityError.value = '';
  entityLoading.value = false;
  if (form.dataDomainId === undefined || form.subjectDomainId === undefined)
    return;
  entityLoading.value = true;
  try {
    const result: BusinessEntityApi.Entity[] = [];
    for (let pageNo = 1; ; pageNo++) {
      const page = await getBusinessEntityPage({
        pageNo,
        pageSize: 100,
        dataDomainId: form.dataDomainId,
        subjectDomainId: form.subjectDomainId,
        status: 0,
      });
      if (!current()) return;
      result.push(...page.list);
      if (result.length >= page.total) break;
      if (page.list.length === 0) throw new Error('业务实体分页不完整');
    }
    if (current()) entities.value = result;
  } catch {
    if (current()) entityError.value = '业务实体加载失败，请重试。';
  } finally {
    if (current()) entityLoading.value = false;
  }
}
async function retryOptions() {
  const current = request.begin();
  if (options.error.value) await options.reload();
  if (current() && entityError.value) await loadEntities();
}
function changeDomain() {
  form.subjectDomainId = undefined;
  form.entityId = undefined;
  resetSource();
  void loadEntities();
}
function changeSubject() {
  form.entityId = undefined;
  resetSource();
  void loadEntities();
}
function changeOwner() {
  form.contactNumber = options.userPhone(form.ownerUserId);
}
async function loadSource(keepFields = false) {
  const current = sourceRequest.begin();
  sourceError.value = '';
  sourceLoading.value = false;
  fields.value = [];
  if (!keepFields) {
    form.valueFieldUid = undefined;
    form.timeFieldUid = undefined;
  }
  if (form.sourceModelId === undefined) return;
  sourceLoading.value = true;
  try {
    const result = await getIndicatorSourceFields(form.sourceModelId);
    if (!current()) return;
    fields.value = result;
    if (result.length === 0)
      sourceError.value = '当前来源没有可引用的稳定字段，请检查来源模型。';
  } catch {
    if (current()) sourceError.value = '来源字段加载失败，请重试。';
  } finally {
    if (current()) sourceLoading.value = false;
  }
}
function validate() {
  const next: Record<string, string> = {};
  if (!form.code?.trim()) next.code = '指标英文名不能为空';
  else if (
    !/^[A-Za-z][A-Za-z0-9_]*$/.test(form.code.trim()) ||
    form.code.trim().length > 100
  )
    next.code = '须以字母开头，仅包含字母、数字和下划线，最多100字符';
  if (!form.name?.trim()) next.name = '指标中文名不能为空';
  if (!form.dataDomainId) next.dataDomainId = '请选择数据板块';
  if (!form.subjectDomainId) next.subjectDomainId = '请选择主题域';
  if (!form.entityId) next.entityId = '请选择业务实体';
  if (!form.ownerUserId) next.ownerUserId = '请选择负责人';
  if (!form.dataFreshness) next.dataFreshness = '请选择数据时效';
  if (!form.sourceModelId) next.sourceModelId = '请选择已发布的来源逻辑表';
  else if (
    !options.models.value.some(
      (item) =>
        String(item.id) === String(form.sourceModelId) &&
        atomicSourceMatches(item, form),
    )
  )
    next.sourceModelId =
      '来源逻辑表须为当前归属、实体和时效下的启用维度或事实表';
  if (!form.aggregation) next.aggregation = '请选择聚合方式';
  if (!fields.value.some((item) => item.fieldUid === form.valueFieldUid))
    next.valueFieldUid = '请选择计算字段';
  if (
    !atomicTimeFields(fields.value).some(
      (item) => item.fieldUid === form.timeFieldUid,
    )
  )
    next.timeFieldUid = '请选择日期或时间类型的统计时间字段';
  if (!form.resultDataType) next.resultDataType = '请选择数据类型';
  if (!form.definition?.trim()) next.definition = '请输入指标口径';
  errors.value = next;
  return Object.keys(next).length === 0;
}
async function open(data: AtomicOpenData) {
  reset();
  openData.value = data;
  opened.value = true;
  const current = request.begin();
  loading.value = true;
  try {
    const id = data.id ?? data.cloneId;
    const detail = id === undefined ? undefined : await getIndicator(id);
    if (!current()) return;
    if (detail && detail.indicatorType !== 'ATOMIC') {
      failed.value = '指标类型与当前页面不匹配。';
      return;
    }
    loadedDetail.value = detail;
    original.value = data.cloneId === undefined ? detail : undefined;
    Object.assign(form, detail || {}, {
      indicatorType: 'ATOMIC',
      status: detail?.status || 'DRAFT',
    });
    if (data.cloneId !== undefined)
      Object.assign(form, {
        id: undefined,
        expectedRevision: undefined,
        code: '',
        status: 'DRAFT',
      });
    for (const key of [
      'dataDomainId',
      'subjectDomainId',
      'ownerUserId',
      'entityId',
      'sourceModelId',
    ] as const)
      if (form[key] !== undefined) form[key] = String(form[key]);
    await options.reload();
    if (!current()) return;
    if (form.ownerUserId !== undefined)
      form.contactNumber =
        options.userPhone(form.ownerUserId) || detail?.ownerUserPhone;
    await loadEntities();
    if (!current()) return;
    if (form.sourceModelId !== undefined) await loadSource(true);
  } catch {
    if (current()) failed.value = '原子指标或关联选项加载失败，请重试。';
  } finally {
    if (current()) loading.value = false;
  }
}
function reset() {
  opened.value = false;
  request.begin();
  entityRequest.begin();
  sourceRequest.begin();
  original.value = undefined;
  loadedDetail.value = undefined;
  fields.value = [];
  entities.value = [];
  loading.value = false;
  saving.value = false;
  sourceLoading.value = false;
  entityLoading.value = false;
  failed.value = '';
  saveError.value = '';
  sourceError.value = '';
  entityError.value = '';
  errors.value = {};
  Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
}
async function submit() {
  if (saving.value || !ready.value || !validate()) return false;
  const current = request.begin();
  saving.value = true;
  saveError.value = '';
  try {
    const payload = indicatorSaveData(
      form as IndicatorModelApi.Save,
      original.value,
    );
    await (original.value
      ? updateIndicator(payload)
      : createIndicator(payload));
    return current();
  } catch {
    if (current())
      saveError.value = '保存失败，请核对来源、权限或版本状态后重试。';
    return false;
  } finally {
    if (current()) saving.value = false;
  }
}
defineExpose({ open, reset, submit, ready });
</script>

<template>
  <ConfigProvider
    :get-popup-container="popupContainer"
    :theme="{ token: { borderRadius: tokens.borderRadius } }"
  >
    <div class="atomic-editor">
      <Spin :spinning="loading">
        <Alert v-if="failed" type="error" :message="failed">
          <template #action>
            <Button @click="openData && open(openData)">重试</Button>
          </template>
        </Alert>
        <Alert
          v-if="options.error.value || entityError"
          type="error"
          :message="entityError || '关联选项加载失败，请重试。'"
        >
          <template #action>
            <Button @click="retryOptions">重试</Button>
          </template>
        </Alert>
        <Alert
          v-if="readonly"
          type="warning"
          message="启用的指标需先停用为草稿后编辑。"
        />
        <Alert v-if="saveError" type="error" :message="saveError" />
        <div class="atomic-notice">
          <QdIcon name="info" />
          <div>
            原子指标用于定义某一业务过程中的基础统计口径<br />由来源逻辑表、计算字段、聚合方式和统计时间字段共同确定，可作为派生指标、汇总模型等后续构建的基础。
          </div>
        </div>
        <h2 class="atomic-section">基本信息</h2>
        <form class="atomic-form" @submit.prevent>
          <div class="atomic-item">
            <label for="atomic-code" class="required">指标英文名</label>
            <div>
              <Input
                id="atomic-code"
                v-model:value="form.code"
                :disabled="disabled"
                :maxlength="100"
                :status="errors.code ? 'error' : undefined"
                placeholder="请输入指标英文名称，如：pay_amount"
              /><small>{{ errors.code }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-name" class="required">指标中文名</label>
            <div>
              <Input
                id="atomic-name"
                v-model:value="form.name"
                :disabled="disabled"
                :maxlength="100"
                :status="errors.name ? 'error' : undefined"
                placeholder="请输入指标中文名称，如：支付金额"
              /><small>{{ errors.name }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-domain" class="required">数据板块</label>
            <div>
              <Select
                id="atomic-domain"
                v-model:value="form.dataDomainId"
                :disabled="disabled"
                :options="domainOptions"
                placeholder="请选择数据板块"
                @change="changeDomain"
              >
                <template #optionRender="{ option }">
                  <img :src="domainIcon" class="atomic-domain-icon" alt="" />{{
                    option.label
                  }}
                </template>
</Select><small>{{ errors.dataDomainId }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-subject" class="required">主题域</label>
            <div>
              <Select
                id="atomic-subject"
                v-model:value="form.subjectDomainId"
                :disabled="disabled || !form.dataDomainId"
                :options="subjectOptions"
                placeholder="请选择主题域"
                @change="changeSubject"
              /><small>{{ errors.subjectDomainId }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-entity" class="required">业务实体</label>
            <div>
              <EntitySelector
                id="atomic-entity"
                v-model="form.entityId"
                :entities="entities"
                :created-entity-ids="createdEntityIds"
                :current-label="loadedDetail?.entityName"
                :disabled="disabled || !form.subjectDomainId"
                :loading="entityLoading"
                placeholder="请选择业务实体"
                type="atomic"
                @change="resetSource"
              />
              <p class="atomic-help">请先选择业务实体，以筛选来源表。</p>
              <small>{{ errors.entityId }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-owner" class="required">负责人</label>
            <div>
              <Select
                id="atomic-owner"
                v-model:value="form.ownerUserId"
                :disabled="disabled"
                :options="ownerOptions"
                placeholder="请选择负责人"
                @change="changeOwner"
              /><small>{{ errors.ownerUserId }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label>联系方式</label><Input
              :value="form.contactNumber"
              disabled
              placeholder="请输入联系方式"
            />
          </div>
          <div class="atomic-item">
            <label>状态</label><RadioGroup v-model:value="form.status" :disabled="disabled">
              <Radio value="DRAFT">禁用</Radio><Radio value="ACTIVE">启用</Radio>
            </RadioGroup>
          </div>
          <div class="atomic-item atomic-full">
            <label for="atomic-description">描述</label><TextArea
              id="atomic-description"
              v-model:value="form.description"
              :disabled="disabled"
              :maxlength="256"
              :rows="4"
              show-count
              placeholder="请输入原子指标的描述"
            />
          </div>
          <h2 class="atomic-section atomic-full">来源与指标定义</h2>
          <div class="atomic-item">
            <label for="atomic-freshness" class="required">数据时效</label>
            <div>
              <Select
                id="atomic-freshness"
                v-model:value="form.dataFreshness"
                :disabled="disabled"
                :options="timelinessOptions"
                placeholder="请选择数据时效"
                @change="resetSource"
              /><small>{{ errors.dataFreshness }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-source" class="required">来源逻辑表</label>
            <div>
              <Select
                id="atomic-source"
                v-model:value="form.sourceModelId"
                :disabled="disabled || !form.entityId || !form.dataFreshness"
                :options="sourceOptions"
                :loading="sourceLoading"
                placeholder="请选择来源逻辑表"
                @change="loadSource(false)"
              />
              <p class="atomic-help">
                仅展示当前业务实体关联的启用维度或事实逻辑表
              </p>
              <small>{{ errors.sourceModelId }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-aggregation" class="required">聚合方式</label>
            <div>
              <Select
                id="atomic-aggregation"
                v-model:value="form.aggregation"
                :disabled="disabled"
                :options="ATOMIC_AGGREGATIONS"
                placeholder="请选择聚合方式"
              />
              <p class="atomic-help">定义指标的聚合计算方式。</p>
              <small>{{ errors.aggregation }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-value" class="required">计算字段</label>
            <div>
              <Select
                id="atomic-value"
                v-model:value="form.valueFieldUid"
                :disabled="disabled || !form.sourceModelId || sourceLoading"
                :options="fieldOptions"
                placeholder="请选择计算字段"
                allow-clear
              />
              <p class="atomic-help">选择来源表中参与指标计算的字段。</p>
              <small>{{ errors.valueFieldUid }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label class="required">指标表达式</label><Input
              :value="expression"
              readonly
              placeholder="根据聚合方式和计算字段自动生成"
            />
          </div>
          <div class="atomic-item">
            <label for="atomic-time" class="required">统计时间字段</label>
            <div>
              <Select
                id="atomic-time"
                v-model:value="form.timeFieldUid"
                :disabled="disabled || !form.sourceModelId || sourceLoading"
                :options="timeOptions"
                placeholder="请选择统计时间字段"
                allow-clear
              />
              <p class="atomic-help">指定指标使用哪个业务时间字段进行统计。</p>
              <small>{{ errors.timeFieldUid }}</small>
            </div>
          </div>
          <Alert
            v-if="sourceError"
            class="atomic-full"
            type="error"
            :message="sourceError"
          >
            <template #action>
              <Button @click="loadSource(true)">重试</Button>
            </template>
          </Alert>
          <h2 class="atomic-section atomic-full">结果属性与口径</h2>
          <div class="atomic-item">
            <label for="atomic-type" class="required">数据类型</label>
            <div>
              <Select
                id="atomic-type"
                v-model:value="form.resultDataType"
                :disabled="disabled"
                :options="resultOptions"
                placeholder="请选择数据类型"
                allow-clear
              /><small>{{ errors.resultDataType }}</small>
            </div>
          </div>
          <div class="atomic-item">
            <label for="atomic-unit">度量单位</label><TreeSelect
              id="atomic-unit"
              v-model:value="form.unit"
              :disabled="disabled"
              :tree-data="unitTree"
              placeholder="请选择度量单位"
              allow-clear
            />
          </div>
          <div class="atomic-item atomic-full">
            <label for="atomic-definition" class="required">指标口径</label>
            <div>
              <TextArea
                id="atomic-definition"
                v-model:value="form.definition"
                :disabled="disabled"
                :maxlength="256"
                :rows="4"
                show-count
                placeholder="说明指标统计什么、如何计算、业务含义和统计范围"
              /><small>{{ errors.definition }}</small>
            </div>
          </div>
        </form>
      </Spin>
    </div>
  </ConfigProvider>
</template>
