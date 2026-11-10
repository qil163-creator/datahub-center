<script setup lang="ts">
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

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
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
} from 'antdv-next';

import { getDataDomain } from '#/api/modeling/datadomain';
import {
  createTableModel,
  getTableModel,
  getTableModelDesign,
  modelSaveData,
  previewSummaryTableFields,
  saveTableModelDraft,
} from '#/api/modeling/tablemodel';
import { useObjectRequest } from '#/hooks/use-object-request';

import { assertCurrentDesign } from '../../modelcommon/current-design-state';
import domainIcon from '../assets/data-section-default-icon-small.svg';
import emptyRecords from '../assets/empty-records.png';
import { QdIcon } from '../icons';
import { useLogicOptions } from '../options';

import '../modal.css';

const emit = defineEmits<{ saved: [id: number | string] }>();

const { tokens } = useAntdDesignTokens();

const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const visible = ref(false);
const loading = ref(false);
const saving = ref(false);
const previewing = ref(false);
const failed = ref(false);
const previewFailed = ref(false);
const saveFailed = ref(false);
const errors = ref<Record<string, string>>({});
const form = reactive<Partial<TableModelApi.Model>>({});
const fields = ref<ModelingModelApi.ModelField[]>([]);
const typeConfig = ref<ModelingModelApi.ModelTypeConfig | null>(null);
const options = useLogicOptions();
const { dataDomains, domains, entities, users, documents, layers } = options;
const guard = useObjectRequest(
  () => `${visible.value}/${form.id ?? 'new'}/${access.accessCodes.join(',')}`,
);
const previewGuard = useObjectRequest(
  () => `${visible.value}/${form.id ?? 'new'}/${access.accessCodes.join(',')}`,
);
const domainGuard = useObjectRequest(
  () => `${visible.value}/${form.dataDomainId ?? ''}`,
);
const readonly = computed(() => form.status === 'DISCARDED');
const disabled = computed(
  () => loading.value || saving.value || readonly.value || failed.value,
);
const canSave = computed(() => hasAccessByCodes(['modeling:model:save']));
const domain = computed(() =>
  dataDomains.value.find(
    (item) => String(item.id) === String(form.dataDomainId),
  ),
);
const domainOptions = computed(() =>
  dataDomains.value
    .filter((item) => item.status !== 1)
    .map((item) => ({
      label: item.code ? `${item.name} / ${item.code}` : item.name,
      value: String(item.id),
    })),
);
const subject = computed(() =>
  domains.value.find(
    (item) => String(item.id) === String(form.subjectDomainId),
  ),
);
const subjectOptions = computed(() =>
  domains.value
    .filter(
      (item) =>
        String(item.dataDomainId) === String(form.dataDomainId) &&
        item.status !== 1,
    )
    .map((item) => ({
      label: item.code ? `${item.name} / ${item.code}` : item.name,
      value: String(item.id),
    })),
);
const grainOptions = computed(() =>
  entities.value
    .filter(
      (item) =>
        String(item.subjectDomainId) === String(form.subjectDomainId) &&
        item.entityType === 'OBJECT' &&
        item.entitySubtype === 'NORMAL' &&
        item.status === 0,
    )
    .map((item) => ({ label: item.name, value: String(item.id) })),
);
const userOptions = computed(() =>
  users.value.map((item) => ({
    label: item.nickname || item.username,
    value: String(item.id),
  })),
);
const documentOptions = computed(() =>
  documents.value
    .filter((item) => !form.documentType || item.stdType === form.documentType)
    .map((item) => ({ label: item.nameCn, value: String(item.id) })),
);
const standardTypes = computed(() =>
  getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
);
const timelinessOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string'),
);
const groups = computed(() =>
  [
    {
      key: 'TIME',
      title: '时间字段',
      note: '系统根据统计时效自动生成，用于标识每条汇总数据所属的统计时间。',
      headers: [
        '字段英文名',
        '字段中文名',
        '描述',
        '数据类型',
        '字段角色',
        '时间粒度',
      ],
      rows: fields.value.filter((field) => field.fieldCategory === 'TIME'),
    },
    {
      key: 'GRAIN',
      title: '粒度字段',
      note: '根据所选统计粒度自动生成，用于标识汇总对象，并共同决定一条汇总结果的唯一性。',
      headers: [
        '字段英文名',
        '字段中文名',
        '描述',
        '数据类型',
        '字段角色',
        '对应业务对象',
        '是否主键',
      ],
      rows: fields.value.filter((field) => field.fieldCategory === 'GRAIN'),
    },
    {
      key: 'INDICATOR',
      title: '派生指标字段',
      note: '系统根据当前统计粒度和统计时效，自动匹配并回填符合条件的派生指标，无需手动选择。',
      headers: [
        '指标英文名',
        '指标中文名',
        '描述',
        '指标口径',
        '原子指标',
        '业务限定',
        '统计周期',
        '数据类型',
        '度量单位',
        '来源逻辑表',
      ],
      rows: fields.value.filter((field) => field.fieldCategory === 'INDICATOR'),
    },
  ].filter((group) => group.key !== 'INDICATOR' || form.grainEntityIds?.length),
);
function timeLabel() {
  return (
    timelinessOptions.value.find((item) => item.value === form.dataTimeliness)
      ?.label ||
    form.dataTimeliness ||
    '-'
  );
}
const namingRule = computed(() =>
  domain.value?.namingRules?.find((item) => item.tableType === 'DWS'),
);
const prefix = computed(() => changeCase(namingRule.value?.prefix || 'dws_'));
function changeCase(value: string) {
  return form.tableCase === 1 ? value.toUpperCase() : value.toLowerCase();
}
function composeName() {
  const grainCodes = (form.grainEntityIds || [])
    .map(
      (id) =>
        entities.value.find((item) => String(item.id) === String(id))?.code ||
        '',
    )
    .filter(Boolean);
  const stem =
    grainCodes.length > 0 ? grainCodes.join('_') : subject.value?.code || 'dws';
  const rule = namingRule.value;
  let suffix = rule?.daySuffix || '';
  if (form.dataTimeliness === '2') suffix = rule?.hourSuffix || '_hh';
  if (form.dataTimeliness === '3') suffix = rule?.minuteSuffix || '_mm';
  form.code = changeCase(`${stem}${suffix}`);
  form.name = `汇总逻辑表${subject.value?.name ? `_${subject.value.name}` : ''}${form.dataTimeliness ? `_${timeLabel()}` : ''}`;
}
async function loadNamingRule() {
  const current = domainGuard.begin();
  if (form.dataDomainId === undefined) return false;
  const detail = await getDataDomain(form.dataDomainId);
  if (!current()) return false;
  const index = dataDomains.value.findIndex(
    (item) => String(item.id) === String(detail.id),
  );
  if (index !== -1) dataDomains.value[index] = detail;
  return true;
}
async function onDomainChange() {
  form.subjectDomainId = undefined;
  form.grainEntityIds = [];
  fields.value = [];
  previewGuard.begin();
  previewing.value = false;
  const current = guard.begin();
  loading.value = true;
  failed.value = false;
  try {
    if (await loadNamingRule()) composeName();
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function onSubjectChange() {
  form.grainEntityIds = [];
  composeName();
  void preview();
}
function onGrainChange() {
  composeName();
  void preview();
}
function onTimeChange() {
  composeName();
  void preview();
}
function onOwnerChange() {
  form.contactNumber =
    users.value.find((item) => String(item.id) === String(form.contactUserId))
      ?.mobile || '';
}
function switchCase() {
  form.tableCase = form.tableCase === 1 ? 2 : 1;
  form.code = changeCase(form.code || '');
}
async function preview() {
  const current = previewGuard.begin();
  fields.value = [];
  previewFailed.value = false;
  previewing.value = false;
  if (
    form.dataDomainId === undefined ||
    form.subjectDomainId === undefined ||
    !form.dataTimeliness
  )
    return;
  previewing.value = true;
  try {
    const rows = await previewSummaryTableFields({
      dataDomainId: form.dataDomainId,
      subjectDomainId: form.subjectDomainId,
      dataTimeliness: form.dataTimeliness,
      grainEntityIds: form.grainEntityIds || [],
    });
    if (current()) fields.value = rows;
  } catch {
    if (current()) previewFailed.value = true;
  } finally {
    if (current()) previewing.value = false;
  }
}
async function open(id?: number | string) {
  Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
  Object.assign(form, {
    id,
    tableType: 'SUMMARY',
    createMode: 'FORWARD',
    tableCase: 2,
    enabled: 0,
    code: 'dws',
    name: '汇总逻辑表',
    grainEntityIds: [],
  });
  visible.value = true;
  errors.value = {};
  saving.value = false;
  fields.value = [];
  failed.value = false;
  previewFailed.value = false;
  saveFailed.value = false;
  typeConfig.value = null;
  const current = guard.begin();
  loading.value = true;
  try {
    await options.load();
    if (!current()) return;
    if (id === undefined) {
      form.layerId = layers.value.find(
        (item) => item.defaultTableType === 'SUMMARY' && item.status !== 1,
      )?.id;
    } else {
      const [model, design] = await Promise.all([
        getTableModel(id),
        getTableModelDesign(id),
      ]);
      if (!current()) return;
      assertCurrentDesign(model, design);
      if (model.tableType !== 'SUMMARY')
        throw new Error('逻辑表类型与编辑页面不匹配');
      Object.assign(form, model, {
        grainEntityIds: (model.grainEntityIds || []).map(String),
      });
      for (const key of [
        'dataDomainId',
        'subjectDomainId',
        'contactUserId',
        'documentId',
      ] as const) {
        if (form[key] !== undefined && form[key] !== null)
          form[key] = String(form[key]);
      }
      typeConfig.value = design.typeConfig || null;
      fields.value = design.fields;
      await loadNamingRule();
      if (!current()) return;
      if (form.code?.toLowerCase().startsWith(prefix.value.toLowerCase()))
        form.code = form.code.slice(prefix.value.length);
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function close() {
  if (saving.value) return;
  visible.value = false;
  guard.begin();
  previewGuard.begin();
  previewing.value = false;
}
async function save() {
  if (disabled.value || !canSave.value || previewing.value) return;
  errors.value = {};
  if (form.dataDomainId === undefined)
    errors.value.dataDomainId = '请选择数据板块';
  if (form.subjectDomainId === undefined)
    errors.value.subjectDomainId = '请选择所属主题域';
  if (!form.dataTimeliness) errors.value.dataTimeliness = '请选择统计时效';
  if (!form.grainEntityIds?.length)
    errors.value.grainEntityIds = '请选择统计粒度';
  if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(form.code || ''))
    errors.value.code =
      '表名须以字母开头，仅包含字母、数字和下划线，最多64个字符';
  if (!form.name?.trim() || form.name.trim().length > 100)
    errors.value.name = '请输入100个字符以内的表中文名';
  if (form.layerId === undefined)
    errors.value.layerId = '请先配置汇总逻辑表目标分层';
  if (Object.keys(errors.value).length > 0 || previewFailed.value) return;
  const current = guard.begin();
  saving.value = true;
  saveFailed.value = false;
  try {
    form.tableNameNotation = `${prefix.value}${changeCase(form.code || '')}`;
    const data = {
      ...modelSaveData(form),
      fields: fields.value,
      typeConfig: typeConfig.value,
    };
    let id = form.id;
    if (id === undefined) id = await createTableModel(data);
    else {
      const saved = await saveTableModelDraft({
        ...data,
        modelId: id,
        expectedRevision: form.revision!,
        fields: fields.value,
        typeConfig: typeConfig.value,
      });
      if (saved === false) return;
    }
    if (current()) {
      saving.value = false;
      visible.value = false;
      emit('saved', id);
    }
  } catch {
    if (current()) saveFailed.value = true;
  } finally {
    if (current()) saving.value = false;
  }
}
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    JSON.stringify(access.accessCodes),
  ],
  () => {
    guard.begin();
    previewGuard.begin();
    visible.value = false;
    saving.value = false;
    loading.value = false;
    previewing.value = false;
    fields.value = [];
  },
);
defineExpose({ open, close });
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Modal
      v-model:open="visible"
      :title="form.id === undefined ? '新增汇总逻辑表' : '修改汇总逻辑表'"
      class="qd-logic-modal qd-summary-modal"
      :width="1092"
      :footer="null"
      :style="{ top: '68px' }"
      :mask-closable="!saving"
      :closable="!saving"
      destroy-on-hidden
      @cancel="close"
    >
      <Spin :spinning="loading">
        <div class="qd-modal-scroll">
          <Alert
            v-if="readonly"
            type="info"
            message="当前模型已废弃，不能修改。"
            show-icon
          />
          <Alert
            v-if="saveFailed"
            type="error"
            message="保存失败，修改内容已保留。请核对最新详情后重试。"
            show-icon
          />
          <div class="qd-summary-notice">
            <QdIcon
              name="info"
            />汇总逻辑表用于组织相同统计粒度和统计时效下的派生指标。选择统计粒度和统计时效后，系统将自动生成时间字段、粒度字段，并匹配符合条件的派生指标字段。
          </div>
          <h2 class="qd-modal-section">基本信息</h2>
          <form class="qd-modal-form" @submit.prevent="save">
            <div class="qd-modal-item">
              <label class="required">数据板块</label>
              <div>
                <Select
                  v-model:value="form.dataDomainId"
                  :options="domainOptions"
                  :disabled="disabled || form.id !== undefined"
                  placeholder="请选择数据板块"
                  @change="onDomainChange"
                >
                  <template #optionRender="{ option }">
                    <img
                      :src="domainIcon"
                      class="qd-modal-domain-icon"
                      alt=""
                    />{{ option.label }}
                  </template>
</Select><small>{{ errors.dataDomainId }}</small>
              </div>
            </div>
            <div class="qd-modal-item">
              <label class="required">所属主题域</label>
              <div>
                <Select
                  v-model:value="form.subjectDomainId"
                  :options="subjectOptions"
                  :disabled="
                    disabled || form.id !== undefined || !form.dataDomainId
                  "
                  placeholder="请先选择数据板块"
                  @change="onSubjectChange"
                /><small>{{ errors.subjectDomainId }}</small>
              </div>
            </div>
            <div class="qd-modal-item">
              <label class="required">统计时效</label>
              <div>
                <Select
                  v-model:value="form.dataTimeliness"
                  :options="timelinessOptions"
                  :disabled="disabled || form.id !== undefined"
                  placeholder="请选择统计时效"
                  @change="onTimeChange"
                />
                <p class="qd-modal-help">
                  定义汇总数据的产出或更新时效，用于明确汇总结果什么时候可用。
                </p>
                <small>{{ errors.dataTimeliness }}</small>
              </div>
            </div>
            <div class="qd-modal-item">
              <label class="required">统计粒度</label>
              <div>
                <Select
                  v-model:value="form.grainEntityIds"
                  mode="multiple"
                  :options="grainOptions"
                  :disabled="
                    disabled || form.id !== undefined || !form.subjectDomainId
                  "
                  placeholder="请选择统计粒度"
                  @change="onGrainChange"
                />
                <p class="qd-modal-help">
                  定义一条汇总数据代表的业务范围，粒度字段共同决定每条汇总结果的唯一性。
                </p>
                <small>{{ errors.grainEntityIds }}</small>
              </div>
            </div>
            <div class="qd-modal-item">
              <label class="required">表英文名</label>
              <div>
                <div class="qd-modal-name">
                  <span>{{ prefix }}</span><Input
                    v-model:value="form.code"
                    :disabled="disabled"
                    placeholder="请输入英文表名"
                  /><Button
                    type="primary"
                    :disabled="disabled"
                    @click="switchCase"
                  >
                    {{ form.tableCase === 1 ? '大写' : '小写' }}
                  </Button>
                </div>
                <small>{{ errors.code }}</small>
              </div>
            </div>
            <div class="qd-modal-item">
              <label class="required">表中文名</label>
              <div>
                <Input
                  v-model:value="form.name"
                  :disabled="disabled"
                  placeholder="请输入中文表名"
                /><small>{{ errors.name }}</small>
              </div>
            </div>
            <div class="qd-modal-item">
              <label>标准类型</label><Select
                v-model:value="form.documentType"
                :options="standardTypes"
                :disabled="disabled"
                allow-clear
                placeholder="请选择标准类型"
                @change="form.documentId = undefined"
              />
            </div>
            <div class="qd-modal-item">
              <label>标准登记</label><Select
                v-model:value="form.documentId"
                :options="documentOptions"
                :disabled="disabled || !form.documentType"
                allow-clear
                placeholder="请选择标准登记"
              />
            </div>
            <div class="qd-modal-item">
              <label>负责人</label><Select
                v-model:value="form.contactUserId"
                :options="userOptions"
                :disabled="disabled"
                placeholder="请选择负责人"
                @change="onOwnerChange"
              />
            </div>
            <div class="qd-modal-item">
              <label>联系方式</label><Input
                :value="form.contactNumber"
                disabled
                placeholder="请输入联系方式"
              />
            </div>
            <div class="qd-modal-item">
              <label class="required">状态</label><RadioGroup v-model:value="form.enabled" :disabled="disabled">
                <Radio :value="0">禁用</Radio><Radio :value="1">启用</Radio>
              </RadioGroup>
            </div>
            <div class="qd-modal-item qd-modal-item--full">
              <label>描述</label><TextArea
                v-model:value="form.description"
                :disabled="disabled"
                :maxlength="256"
                :rows="4"
                show-count
                placeholder="请输入汇总逻辑表的描述"
              />
            </div>
          </form>
          <small v-if="errors.layerId" class="qd-modal-error">{{
            errors.layerId
          }}</small>
          <h2 class="qd-modal-section">字段预览</h2>
          <Alert
            v-if="previewFailed"
            type="error"
            message="字段预览失败，请重试后保存。"
          >
            <template #action>
              <Button @click="preview">重试</Button>
            </template>
          </Alert>
          <Spin :spinning="previewing">
            <section
              v-for="group in groups"
              :key="group.key"
              class="qd-summary-group"
            >
              <p>{{ group.title }} ({{ group.rows.length }}){{ group.note }}</p>
              <div class="qd-summary-table-wrap">
                <table class="qd-summary-table">
                  <thead>
                    <tr>
                      <th v-for="header in group.headers" :key="header">
                        {{ header }}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="field in group.rows"
                      :key="field.fieldUid || field.fieldName"
                    >
                      <td>{{ field.fieldName }}</td>
                      <td>{{ field.cnName }}</td>
                      <template v-if="group.key === 'INDICATOR'">
                        <td>{{ field.remark || '-' }}</td>
                        <td>{{ field.comment || '-' }}</td>
                        <td>{{ field.atomicIndicatorName || '-' }}</td>
                        <td>{{ field.businessCondition || '-' }}</td>
                        <td>{{ field.statisticsPeriod || '-' }}</td>
                        <td>{{ field.dataType }}</td>
                        <td>{{ field.measureUnit || '-' }}</td>
                        <td>{{ field.sourceModelName || '-' }}</td>
                      </template>
                      <template v-else>
                        <td>{{ field.comment || '-' }}</td>
                        <td>{{ field.dataType }}</td>
                        <td>
                          <span class="qd-summary-tag">{{ group.title }}</span>
                        </td>
                        <td v-if="group.key === 'TIME'">
                          <span class="qd-summary-tag qd-summary-tag--green">{{
                            timeLabel()
                          }}</span>
                        </td>
                        <td v-else>{{ field.dimensionName || '-' }}</td>
                        <td v-if="group.key === 'GRAIN'">
                          <span class="qd-summary-tag">{{
                            field.pkFlag ? '是' : '否'
                          }}</span>
                        </td>
                      </template>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="!group.rows.length" class="qd-summary-empty">
                <img :src="emptyRecords" alt="" />
                <p>暂无记录</p>
              </div>
            </section>
          </Spin>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button :disabled="saving" @click="close">取消</Button><Button
          v-if="canSave && !readonly"
          type="primary"
          :loading="saving"
          :disabled="loading || previewing || failed || previewFailed"
          @click="save"
        >
          保存
        </Button>
      </footer>
    </Modal>
  </ConfigProvider>
</template>
