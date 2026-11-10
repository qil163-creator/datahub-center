<script setup lang="ts">
import type { BusinessConditionApi } from '#/api/modeling/business-condition';
import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import {
  computed,
  defineAsyncComponent,
  onActivated,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions, useAntdDesignTokens } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  ConfigProvider,
  Form,
  FormItem,
  Input,
  message,
  Radio,
  RadioGroup,
  Select,
  Spin,
  Table,
  TextArea,
} from 'antdv-next';

import {
  createBusinessCondition,
  getBusinessCondition,
  getBusinessConditionSourceFields,
  updateBusinessCondition,
} from '#/api/modeling/business-condition';
import { getBusinessEntityPage } from '#/api/modeling/business-entity';
import { useObjectRequest } from '#/hooks/use-object-request';

import EntitySelector from '../indicator-model/modules/entity-selector.vue';
import { useMetricOptions } from '../indicator-model/modules/use-metric-options';
import formatIcon from '../indicator-model/reference-assets/condition-format.png';
import insertIcon from '../indicator-model/reference-assets/condition-insert.png';
import validateIcon from '../indicator-model/reference-assets/condition-validate.png';
import { QdIcon } from '../logictable/icons';
import { loadLogicOptionPages } from '../logictable/options';
import {
  CONDITION_LIST_PATH,
  conditionCloneData,
  conditionRouteId,
  conditionSaveData,
  conditionSourceMatches,
  conditionValidation,
} from './data';

defineOptions({ name: 'ModelingBusinessConditionForm' });

const { tokens } = useAntdDesignTokens();

const SqlEditor = defineAsyncComponent(() =>
  import('#/components/monaco/sql-editor.vue').then((module) => module.default),
);
const sqlEditor = ref<{
  format: () => void;
  insertText: (text: string) => void;
}>();
const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    route.query.id,
    route.query.cloneId,
    route.query.dataDomainId,
    route.query.subjectDomainId,
  ]),
);
const editingId = computed(() => conditionRouteId(route.query.id));
const cloneId = computed(() => conditionRouteId(route.query.cloneId));
const canSave = computed(
  () =>
    hasAccessByCodes(['modeling:business-condition:query']) &&
    hasAccessByCodes(['modeling:model:query']) &&
    hasAccessByCodes([
      editingId.value
        ? 'modeling:business-condition:update'
        : 'modeling:business-condition:create',
    ]),
);
const {
  domains,
  subjects,
  models,
  users,
  loading: optionsLoading,
} = useMetricOptions();
const blank = (): BusinessConditionApi.Save => ({
  id: undefined,
  expectedRevision: undefined,
  code: '',
  name: '',
  dataDomainId: '',
  entityId: undefined,
  status: 'DRAFT',
  sourceModelId: '',
  configType: 'BASIC',
  logicMode: 'UNIFIED',
  expression: '',
  subjectDomainId: undefined,
  ownerUserId: undefined,
  description: undefined,
  businessEntity: undefined,
  dataFreshness: undefined,
});
const values = reactive<BusinessConditionApi.Save>(blank());
const original = ref<BusinessConditionApi.Model>();
const step = ref(0);
const loading = ref(false);
const saving = ref(false);
const ready = ref(false);
const error = ref('');
const validation = ref('');
const sourceError = ref('');
const fields = ref<BusinessConditionApi.SourceField[]>([]);
const fieldLoading = ref(false);
const fieldKeyword = ref('');
const entities = ref<BusinessEntityApi.Entity[]>([]);
const entityLoading = ref(false);
const entityError = ref('');
let active = true;
function clearValues() {
  Object.assign(values, blank());
  original.value = undefined;
  ready.value = false;
  loading.value = false;
  saving.value = false;
  fields.value = [];
  fieldLoading.value = false;
  step.value = 0;
  error.value = '';
  sourceError.value = '';
  validation.value = '';
  entities.value = [];
  entityError.value = '';
}
const requests = useObjectRequest(() => scope.value, clearValues);
const fieldRequests = useObjectRequest(
  () => scope.value,
  () => {
    fields.value = [];
    fieldLoading.value = false;
  },
);
const mutations = useObjectRequest(
  () => scope.value,
  () => {
    saving.value = false;
  },
);
const entityRequests = useObjectRequest(
  () => scope.value,
  () => {
    entities.value = [];
    entityLoading.value = false;
  },
);
function withCurrent(
  options: { label: string; value: string }[],
  value: BusinessConditionApi.Id | undefined,
  label: string,
  disabled = false,
) {
  if (
    value === undefined ||
    value === '' ||
    options.some((item) => item.value === String(value))
  )
    return options;
  return [...options, { value: String(value), label, disabled }];
}
const domainOptions = computed(() =>
  withCurrent(
    domains.value
      .filter((item) => item.id !== undefined)
      .map((item) => ({
        value: String(item.id),
        label: item.name || String(item.id),
      })),
    values.dataDomainId,
    `数据域 ${values.dataDomainId}`,
  ),
);
const subjectOptions = computed(() => {
  return withCurrent(
    subjects.value
      .filter(
        (item) =>
          item.id !== undefined &&
          item.status !== 1 &&
          String(item.dataDomainId) === String(values.dataDomainId),
      )
      .map((item) => ({
        value: String(item.id),
        label: item.name || String(item.id),
      })),
    values.subjectDomainId,
    `主题域 ${values.subjectDomainId}`,
  );
});
const ownerOptions = computed(() =>
  withCurrent(
    users.value.map((item) => ({
      value: String(item.id),
      label: item.nickname || item.username,
    })),
    values.ownerUserId,
    original.value?.ownerUserName || `用户 ${values.ownerUserId}`,
  ),
);
const modelOptions = computed(() =>
  withCurrent(
    models.value
      .filter((item) => conditionSourceMatches(item, values))
      .map((item) => ({
        value: String(item.id),
        label: `${item.tableNameNotation || item.code || '-'} / ${item.name || '-'}`,
      })),
    values.sourceModelId,
    original.value?.sourceModelName || `逻辑模型 ${values.sourceModelId}`,
    true,
  ),
);
const createdEntityIds = computed(() =>
  models.value
    .filter(
      (model) =>
        model.enabled === 1 &&
        model.status !== 'DISCARDED' &&
        ['DETAIL', 'DIMENSION'].includes(model.tableType || '') &&
        String(model.dataDomainId) === String(values.dataDomainId) &&
        String(model.subjectDomainId) === String(values.subjectDomainId) &&
        model.entityId !== undefined,
    )
    .map((model) => model.entityId!),
);
const ownerPhone = computed(
  () =>
    users.value.find((item) => String(item.id) === String(values.ownerUserId))
      ?.mobile ||
    (String(values.ownerUserId) === String(original.value?.ownerUserId)
      ? original.value?.ownerUserPhone
      : '') ||
    '',
);
const timeOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string'),
);
const visibleFields = computed(() =>
  fields.value.filter((field) =>
    `${field.fieldName} ${field.cnName || ''}`
      .toLowerCase()
      .includes(fieldKeyword.value.trim().toLowerCase()),
  ),
);
const fieldColumns = [
  { title: '字段名称', dataIndex: 'fieldName', key: 'fieldName', width: '29%' },
  { title: '中文名称', dataIndex: 'cnName', key: 'cnName', width: '29%' },
  { title: '类型', dataIndex: 'dataType', key: 'dataType' },
  { title: '操作', key: 'action', width: 65, fixed: 'right' as const },
];

async function load() {
  if (!active) return;
  const current = requests.begin();
  clearValues();
  loading.value = true;
  try {
    if (!canSave.value) {
      error.value = '维护业务限定需要查询、对应写入及逻辑模型查询权限。';
      return;
    }
    if (
      (route.query.id !== undefined && !editingId.value) ||
      (route.query.cloneId !== undefined && !cloneId.value) ||
      (editingId.value && cloneId.value)
    ) {
      error.value = '业务限定编号无效';
      return;
    }
    const id = editingId.value || cloneId.value;
    if (id) {
      const detail = await getBusinessCondition(id);
      if (!current()) return;
      if (editingId.value && detail.status !== 'DRAFT') {
        error.value = '启用的业务限定需先禁用后修改。';
        return;
      }
      const data = cloneId.value
        ? conditionCloneData(detail)
        : conditionSaveData(detail, detail);
      original.value = cloneId.value ? undefined : detail;
      Object.assign(values, data);
      for (const key of [
        'dataDomainId',
        'subjectDomainId',
        'ownerUserId',
        'sourceModelId',
        'entityId',
      ] as const)
        if (values[key] !== undefined)
          Object.assign(values, { [key]: String(values[key]) });
      ready.value = true;
      await modelChanged(true);
      if (!current()) return;
    } else {
      values.dataDomainId = conditionRouteId(route.query.dataDomainId) || '';
      values.subjectDomainId = conditionRouteId(route.query.subjectDomainId);
    }
    if (current()) ready.value = true;
  } catch {
    if (current()) {
      if (ready.value)
        sourceError.value = '来源字段加载失败，可重新选择来源或重试。';
      else error.value = '业务限定加载失败，请重试。';
    }
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadEntities() {
  const current = entityRequests.begin();
  entities.value = [];
  entityLoading.value = false;
  entityError.value = '';
  if (!values.subjectDomainId) {
    entityLoading.value = false;
    return;
  }
  if (!hasAccessByCodes(['modeling:business-entity:query'])) {
    entityError.value = '暂无业务实体查询权限';
    return;
  }
  entityLoading.value = true;
  try {
    const result = await loadLogicOptionPages((page) =>
      getBusinessEntityPage({
        ...page,
        dataDomainId: values.dataDomainId,
        subjectDomainId: values.subjectDomainId,
        status: 0,
      }),
    );
    if (current()) entities.value = result;
  } catch {
    if (current()) entityError.value = '业务实体加载失败，请重试';
  } finally {
    if (current()) entityLoading.value = false;
  }
}
function clearSource() {
  fieldRequests.begin();
  fields.value = [];
  fieldLoading.value = false;
  values.sourceModelId = '';
  values.expression = '';
  sourceError.value = '';
}
function domainChanged() {
  values.subjectDomainId = undefined;
  values.entityId = undefined;
  clearSource();
}
function subjectChanged() {
  values.entityId = undefined;
  clearSource();
}
async function modelChanged(preserveDefinition: unknown = false) {
  const current = fieldRequests.begin();
  fields.value = [];
  if (preserveDefinition !== true) values.expression = '';
  sourceError.value = '';
  if (!values.sourceModelId) {
    fieldLoading.value = false;
    return;
  }
  fieldLoading.value = true;
  try {
    const result = await getBusinessConditionSourceFields(values.sourceModelId);
    if (current()) fields.value = result;
  } catch {
    if (current())
      sourceError.value = '来源字段加载失败，请重新选择来源逻辑表。';
  } finally {
    if (current()) fieldLoading.value = false;
  }
}
function next() {
  validation.value = conditionValidation(values) || '';
  if (!validation.value && !entityLoading.value) step.value = 1;
}
function validateDefinition() {
  validation.value = conditionValidation(values, true) || '';
  if (!validation.value) message.success('必填项与长度检查通过，未执行 SQL');
}
function insert(field: BusinessConditionApi.SourceField) {
  if (sqlEditor.value) {
    sqlEditor.value.insertText(field.fieldName);
    return;
  }
  const text = `${values.expression}${values.expression && !/\s$/.test(values.expression) ? ' ' : ''}${field.fieldName}`;
  if (text.length > 4000) {
    message.warning('计算逻辑不能超过4000个字符');
    return;
  }
  values.expression = text;
}
async function copyDefinition() {
  try {
    await navigator.clipboard.writeText(values.expression);
    message.success('已复制计算逻辑');
  } catch {
    message.warning('复制失败，请选中计算逻辑后手动复制');
  }
}
async function save() {
  if (
    !ready.value ||
    !canSave.value ||
    saving.value ||
    fieldLoading.value ||
    sourceError.value
  )
    return;
  validation.value = conditionValidation(values, true) || '';
  if (validation.value) return;
  const source = models.value.find(
    (item) => String(item.id) === String(values.sourceModelId),
  );
  if (!source || !conditionSourceMatches(source, values)) {
    validation.value = '当前来源不符合归属、实体或时效，请重新选择来源逻辑表';
    return;
  }
  const current = mutations.begin();
  saving.value = true;
  try {
    const data = conditionSaveData(values, original.value);
    await (original.value
      ? updateBusinessCondition(data)
      : createBusinessCondition(data));
    if (!current() || !active) return;
    message.success('业务限定已保存');
    await router.push(CONDITION_LIST_PATH);
  } catch {
    /* 请求层统一提示保存错误，保留当前定义供用户修改后重试。 */
  } finally {
    if (current()) saving.value = false;
  }
}
watch(
  () => values.subjectDomainId,
  () => {
    void loadEntities();
  },
);
watch(
  () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
  () => {
    void load();
  },
  { immediate: true },
);
onDeactivated(() => {
  active = false;
  requests.begin();
  fieldRequests.begin();
  mutations.begin();
  entityRequests.begin();
  clearValues();
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Page auto-content-height content-class="condition-form-page">
      <div class="condition-steps" aria-label="业务限定配置步骤">
        <div :class="{ current: step === 0, completed: step > 0 }">
          <span class="condition-step-number">1</span>基础信息
        </div>
        <div :class="{ current: step === 1 }">
          <span class="condition-step-number">2</span>限定定义
        </div>
      </div>
      <Spin :spinning="loading" class="condition-form-spin">
        <div class="condition-form-card">
          <Alert v-if="error && (!/(?:加载|读取|获取).*失败/.test(error) || /保存|冲突|未确认/.test(error))" type="error" :message="error" show-icon>
            <template #action>
              <Button size="small" @click="load">
                <template #icon>
                  <IconifyIcon icon="lucide:refresh-cw" />
                </template>
                重试
              </Button>
            </template>
          </Alert>
          <Alert
            v-if="validation"
            type="error"
            :message="validation"
            show-icon
          />
          <Alert
            v-if="sourceError && (!/(?:加载|读取|获取).*失败/.test(sourceError) || /保存|冲突|未确认/.test(sourceError))"
            type="error"
            :message="sourceError"
            show-icon
          >
            <template #action>
              <Button size="small" @click="modelChanged(true)">
                重试来源
              </Button>
            </template>
          </Alert>
          <Alert v-if="entityError && (!/(?:加载|读取|获取).*失败/.test(entityError) || /保存|冲突|未确认/.test(entityError))" type="warning" :message="entityError">
            <template #action>
              <Button size="small" @click="loadEntities"> 重试 </Button>
            </template>
          </Alert>
          <div v-if="ready" class="condition-form-body">
            <template v-if="step === 0">
              <h3>基础信息</h3>
              <Form
                layout="horizontal"
                :colon="false"
                :label-col="{ style: { width: '122px' } }"
                :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
                class="condition-base-form"
              >
                <FormItem label="业务限定中文名" required>
                  <Input
                    v-model:value="values.name"
                    :maxlength="100"
                    placeholder="请输入业务限定中文名"
                    :disabled="saving"
                  />
                </FormItem>
                <FormItem label="业务限定英文名" required>
                  <Input
                    v-model:value="values.code"
                    :maxlength="100"
                    placeholder="请输入业务限定英文名"
                    :disabled="saving"
                  />
                </FormItem>
                <FormItem label="数据板块" required>
                  <Select
                    v-model:value="values.dataDomainId"
                    :options="domainOptions"
                    :loading="optionsLoading"
                    placeholder="请选择数据板块"
                    :disabled="saving || !!original"
                    show-search
                    option-filter-prop="label"
                    @change="domainChanged"
                  />
                </FormItem>
                <FormItem label="主题域" required>
                  <Select
                    v-model:value="values.subjectDomainId"
                    :options="subjectOptions"
                    :loading="optionsLoading"
                    :placeholder="
                      values.dataDomainId ? '请选择主题域' : '请先选择数据板块'
                    "
                    :disabled="
                      saving ||
                      !!original ||
                      !values.dataDomainId ||
                      !hasAccessByCodes(['modeling:subject-domain:query'])
                    "
                    allow-clear
                    show-search
                    option-filter-prop="label"
                    @change="subjectChanged"
                  />
                </FormItem>
                <FormItem
                  label="业务实体"
                  required
                  class="condition-entity-field"
                  extra="先确定目前已经描述的业务对象或业务行为，再筛选可用来源。"
                >
                  <EntitySelector
                    v-model="values.entityId"
                    :entities="entities"
                    :created-entity-ids="createdEntityIds"
                    :loading="entityLoading"
                    :disabled="saving || !!original || !values.subjectDomainId"
                    :current-label="
                      original?.entityName || original?.businessEntity
                    "
                    :placeholder="
                      values.subjectDomainId
                        ? '请选择业务实体'
                        : '请先选择主题域'
                    "
                    @change="clearSource"
                  />
                </FormItem>
                <FormItem label="负责人" required>
                  <Select
                    v-model:value="values.ownerUserId"
                    :options="ownerOptions"
                    :loading="optionsLoading"
                    placeholder="请选择负责人"
                    :disabled="saving"
                    allow-clear
                    show-search
                    option-filter-prop="label"
                  />
                </FormItem>
                <FormItem label="联系方式">
                  <Input
                    :value="ownerPhone"
                    placeholder="请输入联系方式"
                    disabled
                  />
                </FormItem>
                <FormItem label="状态">
                  <RadioGroup v-model:value="values.status" :disabled="saving">
                    <Radio value="DRAFT">禁用</Radio><Radio value="ACTIVE">启用</Radio>
                  </RadioGroup>
                </FormItem>
                <FormItem label="描述">
                  <div class="condition-description">
                    <TextArea
                      v-model:value="values.description"
                      :maxlength="2000"
                      placeholder="请输入描述"
                      :disabled="saving"
                    />
                    <span class="condition-description-count">
                      {{ values.description?.length || 0 }} / 2000个字符
                    </span>
                  </div>
                </FormItem>
              </Form>
            </template>
            <template v-else>
              <Form
                layout="horizontal"
                :colon="false"
                :label-col="{ style: { width: '122px' } }"
                :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
                class="condition-definition-form"
                @submit.prevent
              >
                <h3>限定定义</h3>
                <div class="condition-source-row">
                  <FormItem label="数据时效" required>
                    <Select
                      v-model:value="values.dataFreshness"
                      :options="timeOptions"
                      :disabled="saving"
                      placeholder="请选择数据时效"
                      allow-clear
                      @change="clearSource"
                    />
                  </FormItem>
                  <FormItem label="来源逻辑表" required>
                    <Select
                      v-model:value="values.sourceModelId"
                      :options="modelOptions"
                      :loading="optionsLoading"
                      :disabled="
                        saving || !values.entityId || !values.dataFreshness
                      "
                      placeholder="请选择来源逻辑表"
                      allow-clear
                      show-search
                      option-filter-prop="label"
                      @change="modelChanged"
                    />
                    <div class="condition-source-meta">
                      <span>请先在基本信息中选择业务对象/活动。</span>
                    </div>
                  </FormItem>
                </div>
                <div class="condition-config">
                  <FormItem label="配置方式" required>
                    <RadioGroup
                      v-model:value="values.configType"
                      :disabled="saving"
                    >
                      <Radio value="BASIC">基础限定</Radio><Radio value="COMPOSITE" disabled> 组合限定 </Radio>
                    </RadioGroup>
                  </FormItem>
                  <FormItem label="计算逻辑方式" required>
                    <RadioGroup
                      v-model:value="values.logicMode"
                      :disabled="saving"
                    >
                      <Radio value="UNIFIED">全时段统一</Radio><Radio value="SEGMENTED" disabled> 分时段配置 </Radio>
                    </RadioGroup>
                  </FormItem>
                </div>
                <div class="condition-definition">
                  <label class="condition-expression-label"><span>*</span> 计算逻辑</label>
                  <div class="condition-definition-main">
                    <div class="condition-editor">
                      <div class="condition-editor-toolbar">
                        <Button
                          type="link"
                          :disabled="saving"
                          @click="sqlEditor?.format()"
                        >
                          <img :src="formatIcon" alt="" />格式化
</Button><Button
                          type="link"
                          :disabled="saving"
                          @click="validateDefinition"
                          title="检查必填项和来源字段"
                        >
                          <img :src="validateIcon" alt="" />规范性校验
                        </Button>
                      </div>
                      <SqlEditor
                        v-model="values.expression"
                        ref="sqlEditor"
                        height="310px"
                        :font-size="12"
                        :folding="false"
                        :line-numbers-min-chars="2"
                        :line-decorations-width="14"
                        render-validation-decorations="off"
                        :readonly="saving"
                        :minimap="false"
                      />
                      <Button
                        class="condition-editor-copy"
                        aria-label="复制计算逻辑"
                        title="复制计算逻辑"
                        :disabled="saving || !values.expression"
                        @click="copyDefinition"
                      >
                        <QdIcon name="documentCopy" />
                      </Button>
                    </div>
                    <div class="condition-field-panel">
                      <Input
                        v-model:value="fieldKeyword"
                        placeholder="请输入字段中文/英文名称"
                        allow-clear
                      >
                        <template #prefix>
                          <QdIcon name="search" />
                        </template>
</Input><Table
                        :columns="fieldColumns"
                        :data-source="visibleFields"
                        row-key="fieldUid"
                        :loading="fieldLoading"
                        :pagination="false"
                        size="small"
                        :scroll="{ y: 275 }"
                      >
                        <template #bodyCell="{ column, record }">
                          <Button
                            v-if="column.key === 'action'"
                            type="link"
                            size="small"
                            :disabled="saving"
                            @click="insert(record)"
                          >
                            <template #icon>
                              <img :src="insertIcon" alt="" />
                            </template>
                            插入
                          </Button>
                        </template>
                      </Table>
                    </div>
                  </div>
                </div>
              </Form>
            </template>
          </div>
          <footer class="condition-form-footer">
            <Button
              :disabled="saving"
              @click="router.push(CONDITION_LIST_PATH)"
            >
              返回列表
</Button><Button v-if="step === 1" :disabled="saving" @click="step = 0">
              上一步
</Button><Button
              v-if="step === 0"
              type="primary"
              :disabled="!ready || !canSave || entityLoading"
              @click="next"
            >
              下一步
</Button><Button
              v-else
              type="primary"
              :loading="saving"
              :disabled="!ready || !canSave || !!sourceError"
              @click="save"
            >
              保存并退出
            </Button>
          </footer>
        </div>
      </Spin>
    </Page>
  </ConfigProvider>
</template>

<style scoped>
:deep(.condition-form-page) {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  padding: 14px !important;
}

.condition-steps {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  min-height: 87px;
  background: #fff;
  border-radius: var(--radius);
}

.condition-steps > div {
  position: relative;
  display: flex;
  gap: 7px;
  align-items: center;
  justify-content: center;
  width: 40.6%;
  height: 34px;
  font-size: 14px;
  color: #a8abb2;
  background: #f7f8fa;
  transform: translate(8px, 2px);
}

.condition-steps > div:first-child {
  z-index: 1;
}

.condition-steps > div:first-child::after {
  position: absolute;
  top: 0;
  right: -17px;
  width: 0;
  height: 0;
  content: '';
  border-top: 17px solid transparent;
  border-bottom: 17px solid transparent;
  border-left: 17px solid #f7f8fa;
  filter: drop-shadow(15px 0 #fff);
}

.condition-steps > div.current {
  color: #fff;
  background: #4266f4;
}

.condition-steps > div.current::after {
  border-left-color: #4266f4;
}

.condition-steps > div.completed {
  color: #4266f4;
  background: #f4f6ff;
}

.condition-steps > div.completed::after {
  border-left-color: #f4f6ff;
}

.condition-step-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 17px;
  height: 17px;
  line-height: 17px;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

.completed .condition-step-number {
  color: #fff;
  background: #4266f4;
  border-color: #4266f4;
}

.condition-form-spin {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.condition-form-spin :deep(.ant-spin-container) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.condition-form-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 14px;
  background: #fff;
  border-radius: var(--radius);
}

.condition-form-body {
  flex: 1;
  min-height: 0;
  padding: 8px 20px 20px;
  overflow: auto;
}

.condition-form-body h3 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 18px;
  font-size: 16px;
  font-weight: 400;
  line-height: 20px;
  color: #303133;
}

.condition-form-body h3::before {
  width: 4px;
  height: 14px;
  content: '';
  background: #4266f4;
  border-radius: 2px;
}

.condition-base-form,
.condition-definition-form {
  width: 100%;
  margin: 0;
}

.condition-form-body :deep(.ant-form-item) {
  margin-bottom: 14px;
}

.condition-form-body :deep(.ant-form-item-label) {
  flex-shrink: 0;
  padding-right: 10px;
}

.condition-form-body :deep(.ant-form-item-label > label) {
  height: 28px;
  font-size: 14px;
  color: #303133;
}

.condition-form-body :deep(.ant-form-item-label > label::after) {
  display: none;
}

.condition-form-body :deep(.ant-form-item-control-input) {
  min-height: 28px;
}

.condition-form-body :deep(.ant-form-item-extra) {
  min-height: 8px;
  margin-top: 2px;
  font-size: 12px;
  line-height: 14px;
  color: #909399;
}

.condition-form-body :deep(.condition-entity-field.ant-form-item) {
  margin-bottom: 6px;
}

.condition-form-body :deep(.ant-select),
.condition-form-body :deep(.ant-input) {
  width: 100%;
}

.condition-form-body :deep(.ant-select-selector),
.condition-form-body :deep(.ant-input),
.condition-form-body :deep(.ant-input-affix-wrapper) {
  border-radius: calc(var(--radius) - 2px);
  box-shadow: none;
}

.condition-form-body :deep(.metric-entity-trigger) {
  height: 28px;
  min-height: 28px;
  padding: 0 10px;
  font-size: 14px;
  border-color: #dcdfe6;
  border-radius: calc(var(--radius) - 2px);
}

.condition-form-body :deep(.metric-entity-trigger:disabled) {
  background: #f5f7fa;
}

.condition-form-body :deep(.ant-radio-wrapper) {
  margin-inline-end: 22px;
  font-size: 14px;
}

.condition-form-body :deep(.ant-radio-wrapper-checked) {
  color: #4266f4;
}

.condition-form-body :deep(.ant-radio-inner) {
  width: 13px;
  height: 13px;
}

.condition-description {
  position: relative;
}

.condition-description :deep(textarea.ant-input) {
  height: 84px;
  min-height: 84px;
  padding: 5px 10px 20px;
  resize: vertical;
}

.condition-description-count {
  position: absolute;
  right: 10px;
  bottom: 4px;
  font-size: 12px;
  line-height: 14px;
  color: #909399;
  pointer-events: none;
}

.condition-source-row,
.condition-config {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 16px;
}

.condition-source-meta {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  min-height: 14px;
  font-size: 12px;
  line-height: 14px;
  color: #909399;
}

.condition-definition {
  display: flex;
  max-width: 100%;
}

.condition-config :deep(.ant-form-item) {
  margin-bottom: 8px;
}

.condition-expression-label {
  flex-shrink: 0;
  width: 122px;
  padding-top: 5px;
  padding-right: 10px;
  font-size: 14px;
  color: #303133;
  text-align: right;
}

.condition-expression-label span {
  color: #f56c6c;
}

.condition-definition-main {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 2.45fr) minmax(0, 1fr);
  gap: 9px;
  min-width: 0;
}

.condition-field-panel {
  min-width: 0;
}

.condition-field-panel :deep(.ant-input-prefix) {
  color: #a8abb2;
}

.condition-field-panel :deep(.ant-table) {
  margin-top: 10px;
}

.condition-field-panel :deep(.ant-table-tbody > tr > td) {
  overflow-wrap: anywhere;
}

.condition-field-panel img {
  width: 12px;
  height: 12px;
  object-fit: contain;
}

.condition-editor {
  position: relative;
  align-self: start;
  min-width: 0;
  overflow: hidden;
  background: #0e0f1f;
}

.condition-editor-toolbar {
  display: flex;
  gap: 18px;
  align-items: center;
  height: 35px;
  padding: 0 16px;
  background: #f1f1f5;
}

.condition-editor-toolbar :deep(.ant-btn) {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 28px;
  padding: 0;
  font-size: 12px;
  color: #303133;
}

.condition-editor-toolbar img {
  width: 12px;
  height: 12px;
  object-fit: contain;
}

/* 配色仅作用于当前工作台，保留其他页面共享的 Monaco 亮色主题。 */
.condition-editor :deep(.monaco-editor),
.condition-editor :deep(.monaco-editor-background),
.condition-editor :deep(.monaco-editor .margin) {
  background: #0e0f1f;
}

.condition-editor :deep(.monaco-editor .mtk1) {
  color: #d4d4d4;
}

.condition-editor :deep(.monaco-editor .mtk15) {
  color: #d4d4d4;
}

.condition-editor :deep(.monaco-editor .mtk20) {
  color: #b2a7ff;
}

.condition-editor :deep(.monaco-editor .mtk29) {
  color: #87b65e;
}

.condition-editor :deep(.monaco-editor .line-numbers) {
  color: #7f8492;
}

.condition-editor :deep(.monaco-editor .cursors-layer .cursor) {
  color: #d4d4d4;
  background: #d4d4d4;
  border-color: #d4d4d4;
}

.condition-editor :deep(.monaco-editor .current-line) {
  border-color: transparent;
}

.condition-editor :deep(.monaco-editor .selected-text) {
  background: #264f78;
}

.condition-editor-copy.ant-btn {
  position: absolute;
  right: 14px;
  bottom: 8px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 2px;
  color: #606266;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: calc(var(--radius) - 2px);
}

.condition-form-footer {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
  justify-content: flex-end;
  padding: 12px 28px 0;
  border-top: 1px solid #f1f2f5;
}

.condition-form-footer :deep(.ant-btn) {
  height: 28px;
  padding: 0 13px;
  border-radius: calc(var(--radius) - 2px);
}

@media (max-width: 1100px) {
  .condition-definition-main {
    grid-template-columns: minmax(0, 1fr);
  }

  .condition-source-row,
  .condition-config {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 767px) {
  .condition-form-body {
    padding: 8px 0 16px;
  }

  .condition-definition {
    flex-direction: column;
    gap: 8px;
  }

  .condition-expression-label {
    width: auto;
    padding: 0;
    text-align: left;
  }

  .condition-form-body :deep(.ant-form-item-row) {
    display: block;
  }

  .condition-form-body :deep(.ant-form-item-label) {
    width: 100% !important;
    padding: 0 0 4px;
    text-align: left;
  }

  .condition-source-meta {
    flex-wrap: wrap;
    padding-top: 4px;
  }

  .condition-form-footer {
    flex-wrap: wrap;
    padding-right: 0;
    padding-left: 0;
  }
}
</style>
