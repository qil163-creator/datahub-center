<script setup lang="ts">
import type { QualityTaskApi } from '#/api/governance/quality/task';
import type { StandardElementRuleApi as Api } from '#/api/governance/standardelement/rule';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Input,
  message,
  RadioGroup,
  Select,
  Spin,
  TextArea,
} from 'antdv-next';

import {
  getElementRule,
  getElementRuleContext,
  getElementRuleTemplates,
  sampleElementRule,
  saveElementRule,
} from '#/api/governance/standardelement/rule';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  cleanParameterFields,
  cleanParameterIssues,
} from '../../../../cleanrule/modules/clean-rule-parameters';
import {
  dimensionOptions,
  editableEvaluation,
  newEvaluation,
  ruleIcon,
  validateEvaluation,
} from '../../../../quality/task/data';
import {
  auditReferencedColumns,
  extendedAuditStrategies,
} from '../../../../quality/task/modules/audit-rule-parameters';
import AuditConfig from '../../../../quality/task/modules/rule-config.vue';
import CleanConfig from './clean-config.vue';

import '../../../../quality/task/qdata-quality.css';
import './rules.css';

const props = defineProps<{ elementId: Api.Id; kind: Api.Kind }>();
const emit = defineEmits<{ saved: [] }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canEdit = computed(() =>
  hasAccessByCodes(['governance:standard-element:update']),
);
const context = ref<Api.Context>({ fields: [], referenceObjects: [] });
const templates = ref<Api.Template[]>([]);
const selected = ref<Api.Template>();
const openedRule = ref<Api.Rule>();
const draft = ref<Api.Rule>({
  elementId: props.elementId,
  kind: props.kind,
  name: '',
  templateId: '',
  status: 0,
  config: {},
});
const evaluation = ref<QualityTaskApi.Evaluation>(
  newEvaluation({
    ruleCode: '',
    strategyKey: '',
    ruleName: '',
    qualityDim: '',
    description: '',
    enabled: true,
    sampleSupported: false,
  }),
);
const failed = ref(false);
const loading = ref(false);
const readonly = ref(false);
const saving = ref(false);
const selecting = ref(true);
const visible = ref(false);
const category = ref('');
const error = ref('');
const sample = ref('');
const sampleBusy = ref(false);
const search = ref('');
const sampleResult = ref<{ message: string; valid: boolean }>();
const audit = computed(() => props.kind === 'AUDIT');
const auditCardOrder = [
  '107',
  '306',
  '205',
  '115',
  '118',
  '108',
  '110',
  '111',
  '112',
];
const categoryIcon = '/images/rule-catalog-reference/tree-folder.png';
// 原始素材中已有蓝色的图标保持原色，其余灰色素材按卡片可用状态着色。
const blueCleanIconCodes = new Set([
  '001',
  '002',
  '006',
  '007',
  '008',
  '009',
  '010',
  '011',
  '012',
  '017',
  '019',
  '021',
  '022',
  '024',
  '025',
]);
// 稽查目录的灰色素材与清洗素材使用相同配色，只在卡片可点击时着色。
const grayAuditIconCodes = new Set([
  '109',
  '113',
  '114',
  '116',
  '117',
  '119',
  '203',
  '204',
  '206',
  '207',
  '308',
  '309',
  '310',
  '403',
  '405',
]);
const editAction = computed(() =>
  draft.value.id === undefined ? '新增' : '修改',
);
const title = computed(
  () =>
    `${readonly.value ? '查看' : editAction.value}${audit.value ? '稽查' : '清洗'}规则${!selecting.value && selected.value ? `-${selected.value.name}` : ''}`,
);
const allGroups = computed(() => {
  const names = [
    ...new Set(
      templates.value.map(
        (row) => row.dimensionName || row.categoryName || '其他',
      ),
    ),
  ];
  if (audit.value)
    names.sort(
      (left, right) =>
        dimensionOptions.findIndex((item) => item.label === left) -
        dimensionOptions.findIndex((item) => item.label === right),
    );
  const rank = (code: string) => {
    const index = auditCardOrder.indexOf(code);
    return index === -1 ? auditCardOrder.length : index;
  };
  return names.map((name) => ({
    name,
    rows: templates.value
      .filter(
        (row) => (row.dimensionName || row.categoryName || '其他') === name,
      )
      .toSorted(
        (left, right) =>
          (audit.value
            ? rank(left.code) - rank(right.code)
            : Number(right.enabled) - Number(left.enabled)) ||
          left.code.localeCompare(right.code),
      ),
  }));
});
const groups = computed(() =>
  allGroups.value
    .filter((group) => !category.value || category.value === group.name)
    .map((group) => ({
      ...group,
      rows: group.rows.filter((row) =>
        `${row.name}${row.description ?? ''}`.includes(search.value.trim()),
      ),
    }))
    .filter((group) => group.rows.length),
);
const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  showCancelButton: false,
  onClosed() {
    visible.value = false;
    requests.begin();
    contextRequests.begin();
    saving.value = false;
    sampleBusy.value = false;
  },
});
const requests = useObjectRequest(
  () => JSON.stringify([props.elementId, props.kind, access.accessCodes]),
  () => {
    templates.value = [];
    context.value = { fields: [], referenceObjects: [] };
    if (visible.value) void modalApi.close();
  },
);
const samples = useObjectRequest(
  () =>
    JSON.stringify([
      props.elementId,
      selected.value?.id,
      evaluation.value.config,
      sample.value,
      access.accessCodes,
      visible.value,
    ]),
  () => {
    sampleResult.value = undefined;
    sampleBusy.value = false;
  },
);
const contextRequests = useObjectRequest(
  () =>
    JSON.stringify([props.elementId, selected.value?.id, access.accessCodes]),
  () => {
    context.value = { fields: [], referenceObjects: [] };
  },
);
async function open(row?: Api.Rule, detail = false) {
  openedRule.value = row;
  selecting.value = !row;
  readonly.value = detail;
  search.value = '';
  category.value = '';
  error.value = '';
  failed.value = false;
  sample.value = '';
  sampleResult.value = undefined;
  selected.value = undefined;
  draft.value = {
    elementId: props.elementId,
    kind: props.kind,
    name: '',
    templateId: '',
    status: 0,
    config: {},
    severity: 'HIGH',
  };
  visible.value = true;
  await modalApi.open();
  const current = requests.begin();
  loading.value = true;
  try {
    const [catalog, record] = await Promise.all([
      getElementRuleTemplates(props.kind),
      row?.id === undefined
        ? Promise.resolve(undefined)
        : getElementRule(props.elementId, row.id),
    ]);
    if (!current()) return;
    templates.value = catalog;
    if (record) {
      const template = catalog.find(
        (item) => String(item.id) === String(record.templateId),
      );
      if (!template) throw new Error('规则模板不存在');
      select(template, record);
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function select(template: Api.Template, record?: Api.Rule) {
  if (!record && (!template.enabled || !canEdit.value)) return;
  selected.value = template;
  selecting.value = false;
  error.value = '';
  sample.value = '';
  sampleResult.value = undefined;
  evaluation.value = newEvaluation({
    ruleCode: template.code,
    strategyKey: template.strategyKey,
    ruleName: template.name,
    qualityDim: template.dimensionName ?? '',
    description: template.description ?? '',
    enabled: template.enabled,
    sampleSupported: template.strategyKey === 'CHARACTER_VALIDATION',
  });
  if (template.strategyKey === 'ENUM_VALIDATION')
    evaluation.value.config.useCodeTable = false;
  const config: Record<string, unknown> = audit.value
    ? evaluation.value.config
    : Object.fromEntries(
        (cleanParameterFields(template.strategyKey) ?? [])
          .filter((field) => field.defaultValue !== undefined)
          .map((field) => [field.key, field.defaultValue]),
      );
  if (template.strategyKey === 'DATE_FILL_NULL') {
    config.fillType = 'YESTERDAY';
    delete config.format;
  }
  draft.value = record
    ? JSON.parse(JSON.stringify(record))
    : {
        elementId: props.elementId,
        kind: props.kind,
        templateId: template.id,
        name: '',
        status: 0,
        description: '',
        whereCondition: '',
        severity: 'HIGH',
        errorExample: '',
        repairSuggestion: '',
        config,
      };
  evaluation.value = editableEvaluation({
    ...evaluation.value,
    config: draft.value.config,
  });
  evaluation.value.columnNames = Array.isArray(draft.value.config.columnNames)
    ? [...(draft.value.config.columnNames as string[])]
    : evaluation.value.columnNames;
  evaluation.value.objectKey = String(draft.value.config.objectKey ?? '');
  if (
    [
      'COMPOSITE_UNIQUENESS_VALIDATION',
      'FOREIGN_KEY_VALIDATION',
      'GROUP_FIELD_COMPLETENESS',
      'MASTER_DATA_REF_VALIDATION',
      'MUTUAL_EXCLUSION_VALIDATION',
      'TIME_ORDER_VALIDATION',
    ].includes(template.strategyKey) ||
    extendedAuditStrategies.has(template.strategyKey)
  )
    void loadContext();
}
async function loadContext() {
  const current = contextRequests.begin();
  try {
    const result = await getElementRuleContext(props.elementId);
    if (current()) context.value = result;
  } catch {
    if (current())
      context.value = {
        fields: [],
        referenceObjects: [],
        message: '字段加载失败，请重试',
      };
  }
}
function payload(): Api.Rule {
  const result = JSON.parse(JSON.stringify(draft.value)) as Api.Rule;
  if (audit.value) {
    result.config = JSON.parse(JSON.stringify(evaluation.value.config));
    if (selected.value?.strategyKey === 'RECORD_COMPLETENESS_VALIDATION') {
      result.whereCondition = '';
      result.config.columnNames = [];
    }
    if (
      [
        'COMPOSITE_UNIQUENESS_VALIDATION',
        'CONDITIONAL_REQUIRED_VALIDATION',
        'CROSS_TABLE_CONSISTENCY',
        'FOREIGN_KEY_VALIDATION',
        'GROUP_FIELD_COMPLETENESS',
        'MASTER_DATA_REF_VALIDATION',
        'MUTUAL_EXCLUSION_VALIDATION',
        'NUMERIC_RELATION_VALIDATION',
        'STATE_CONSISTENCY_VALIDATION',
      ].includes(selected.value?.strategyKey ?? '')
    )
      result.config.columnNames = auditReferencedColumns(
        selected.value?.strategyKey ?? '',
        result.config,
        evaluation.value.columnNames,
      );
    if (evaluation.value.objectKey)
      result.config.objectKey = evaluation.value.objectKey;
    if (
      selected.value?.strategyKey === 'ENUM_VALIDATION' &&
      !result.config.useCodeTable
    )
      result.config.validValues = (
        (result.config.valueLabels as { value: string }[]) ?? []
      ).map((row) => row.value);
  }
  return result;
}
async function save() {
  if (!selected.value || !canEdit.value || readonly.value || saving.value)
    return;
  const data = payload();
  error.value = data.name.trim() ? '' : '请输入规则名称';
  if (audit.value)
    error.value ||=
      validateEvaluation(
        { ...evaluation.value, evaluationName: data.name, config: data.config },
        'standard',
      ) ?? '';
  if (!audit.value)
    error.value ||=
      cleanParameterIssues(selected.value.strategyKey, data.config)[0] ?? '';
  if (
    selected.value.strategyKey === 'DATE_FILL_NULL' &&
    data.config.fillType !== 'FIXED' &&
    !data.config.format
  )
    error.value = '请选择日期格式';
  if (error.value) return;
  const current = requests.begin();
  saving.value = true;
  try {
    await saveElementRule(data);
    if (current()) {
      message.success('保存成功');
      emit('saved');
      await modalApi.close();
    }
  } finally {
    if (current()) saving.value = false;
  }
}
async function testSample() {
  if (!selected.value || !sample.value) {
    message.warning('请输入样例数据');
    return;
  }
  const current = samples.begin();
  sampleBusy.value = true;
  try {
    const result = await sampleElementRule({
      elementId: props.elementId,
      templateId: selected.value.id,
      config: evaluation.value.config,
      sample: sample.value,
    });
    if (current()) sampleResult.value = result;
  } finally {
    if (current()) sampleBusy.value = false;
  }
}
defineExpose({ open });
</script>
<template>
  <Modal
    :title="title"
    :fullscreen-button="false"
    class="q-quality-modal standard-rule-modal"
    header-class="q-quality-dialog-header"
    content-class="q-quality-dialog-content"
    footer-class="q-quality-dialog-footer"
  >
    <Spin :spinning="loading">
      <Alert v-if="failed" type="error" message="规则加载失败">
        <template #action>
          <Button @click="open(openedRule, readonly)">
            <template #icon>
              <IconifyIcon icon="lucide:refresh-cw" />
            </template>
            重试
          </Button>
        </template>
      </Alert>
      <div v-else-if="selecting" class="standard-rule-picker">
        <nav>
          <button :class="{ active: !category }" @click="category = ''">
            ▾ {{ audit ? '稽查' : '清洗' }}规则
            <span>({{ templates.length }})</span>
</button><button
            v-for="group in allGroups"
            :key="group.name"
            :class="{ active: category === group.name }"
            @click="category = group.name"
          >
            <img :src="categoryIcon" alt="" />{{ group.name
            }}<span>({{ group.rows.length }})</span>
          </button>
        </nav>
        <div class="standard-rule-cards">
          <Input
            v-model:value="search"
            placeholder="请输入你要搜索的内容"
            allow-clear
            class="standard-rule-search"
          />
          <section v-for="group in groups" :key="group.name">
            <h3>{{ group.name }}</h3>
            <div class="standard-rule-card-grid">
              <button
                v-for="row in group.rows"
                :key="row.id"
                :disabled="!row.enabled || !canEdit"
                :title="row.disabledReason || row.description"
                @click="select(row)"
              >
                <img
                  :class="{
                    'standard-rule-icon-muted': audit
                      ? grayAuditIconCodes.has(row.code)
                      : !blueCleanIconCodes.has(row.code.padStart(3, '0')),
                  }"
                  :src="
                    audit
                      ? ruleIcon(row.strategyKey, row.code)
                      : `/images/standard-element-reference/clean-rule-${row.code.padStart(3, '0')}.png`
                  "
                  alt=""
                />
                <div>
                  <strong>{{ row.name }}</strong>
                  <p>{{ row.description }}</p>
                </div>
              </button>
            </div>
          </section>
          <div v-if="!groups.length" class="standard-empty">暂无匹配规则</div>
        </div>
      </div>
      <div v-else class="q-rule-body">
        <h3 class="q-section">基础信息</h3>
        <div class="q-form-grid q-base-grid">
          <div class="q-field">
            <span class="required">{{ audit ? '稽查' : '清洗' }}名称</span><Input
              v-model:value="draft.name"
              :maxlength="100"
              :disabled="readonly"
              :placeholder="`请输入${audit ? '稽查' : '清洗'}名称`"
            />
          </div>
          <div class="q-field">
            <span>{{ audit ? '稽查' : '清洗' }}规则编号</span><Input :value="selected?.code" readonly />
          </div>
          <div class="q-field">
            <span>{{ audit ? '稽查' : '清洗' }}规则名称</span><Input :value="selected?.name" readonly />
          </div>
          <div v-if="audit" class="q-field">
            <span>告警等级</span><Select
              v-model:value="draft.severity"
              :disabled="readonly"
              :options="[
                { label: '严重', value: 'HIGH' },
                { label: '一般', value: 'MID' },
                { label: '轻微', value: 'LOW' },
              ]"
            />
          </div>
          <div class="q-field">
            <span>状态</span><RadioGroup
              v-model:value="draft.status"
              :disabled="readonly"
              :options="[
                { label: '上线', value: 0 },
                { label: '下线', value: 1 },
              ]"
            />
          </div>
        </div>
        <div class="q-descriptions">
          <div class="q-field">
            <span>规则描述</span><TextArea
              v-model:value="draft.description"
              :disabled="readonly"
              placeholder="请输入规则描述"
              :maxlength="256"
              show-count
            />
          </div>
          <template v-if="audit">
            <div class="q-field">
              <span>错误示例</span><TextArea
                v-model:value="draft.errorExample"
                :disabled="readonly"
                placeholder="请输入错误示例"
                :maxlength="256"
                show-count
              />
            </div>
            <div class="q-field">
              <span>修复建议</span><TextArea
                v-model:value="draft.repairSuggestion"
                :disabled="readonly"
                placeholder="请输入修复建议"
                :maxlength="256"
                show-count
              />
            </div>
          </template>
          <div
            v-if="selected?.strategyKey !== 'RECORD_COMPLETENESS_VALIDATION'"
            class="q-field"
          >
            <span>Where 条件</span><TextArea
              v-model:value="draft.whereCondition"
              :disabled="readonly"
              placeholder="请输入 Where 条件"
              :maxlength="256"
              show-count
            />
          </div>
        </div>
        <h3 class="q-section">规则配置</h3>
        <Alert v-if="context.message" :message="context.message" type="info">
          <template #action>
            <Button size="small" @click="loadContext">
              <template #icon>
                <IconifyIcon icon="lucide:refresh-cw" />
              </template>
              刷新
            </Button>
          </template>
        </Alert>
        <AuditConfig
          v-if="audit"
          v-model:value="evaluation"
          :objects="[]"
          context="standard"
          :readonly="readonly"
          :field-options="context.fields"
          :reference-objects="context.referenceObjects"
        />
        <CleanConfig
          v-else-if="selected"
          v-model:config="draft.config"
          :strategy-key="selected.strategyKey"
          :readonly="readonly"
        />
        <template v-if="selected?.strategyKey === 'CHARACTER_VALIDATION'">
          <h3 class="q-section">样例监测</h3>
          <div class="standard-rule-sample">
            <div class="q-field">
              <span>样例数据</span><Input v-model:value="sample" placeholder="请输入样例数据" />
            </div>
            <Button :loading="sampleBusy" @click="testSample">
              <template #icon>
                <IconifyIcon icon="lucide:scan-search" />
</template>监测
            </Button>
          </div>
          <Alert
            v-if="sampleResult"
            :type="sampleResult.valid ? 'success' : 'warning'"
            :message="sampleResult.message"
          />
        </template>
        <Alert v-if="error" type="error" :message="error" show-icon />
      </div>
    </Spin>
    <template #footer>
      <Button
        v-if="!selecting && !readonly && canEdit"
        type="primary"
        :loading="saving"
        @click="save"
      >
        确定
</Button><Button
        v-if="!selecting && draft.id === undefined && !readonly"
        :disabled="saving"
        @click="selecting = true"
      >
        返回
</Button><Button :disabled="saving" @click="modalApi.close()"> 关闭 </Button>
    </template>
  </Modal>
</template>
