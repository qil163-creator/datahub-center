<script setup lang="ts">
import type { QualityTaskApi } from '#/api/governance/quality/task';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Input,
  message,
  RadioGroup,
  Select,
  Spin,
  TabPane,
  Tabs,
  TextArea,
} from 'antdv-next';

import {
  sampleQualityTaskRule,
  spotCheckQualityTaskRule,
} from '#/api/governance/quality/task';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  dimensionOptions,
  displayRuleCatalog,
  editableEvaluation,
  emptyImage,
  newEvaluation,
  ruleIcon,
  validateEvaluation,
} from '../data';
import { auditReferencedColumns } from './audit-rule-parameters';
import RuleConfig from './rule-config.vue';
const props = withDefaults(
  defineProps<{
    allowExecution?: boolean;
    catalog: QualityTaskApi.CatalogRule[];
    objects: QualityTaskApi.Object[];
  }>(),
  { allowExecution: true },
);
const emit = defineEmits<{
  save: [value: QualityTaskApi.Evaluation, index?: number];
}>();
const draft = ref<QualityTaskApi.Evaluation>(
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
const selection = ref(true);
const readonly = ref(false);
const editIndex = ref<number>();
const search = ref('');
const dimension = ref('');
const sample = ref('');
const sampleResult = ref<{ message: string; passed: boolean }>();
const busy = ref(false);
const error = ref('');
const spotResult = ref<QualityTaskApi.SpotCheck>();
const spotLoading = ref(false);
const spotError = ref(false);
const spotTab = ref('problem');
const requests = useObjectRequest(
  () => JSON.stringify([draft.value, props.objects, sample.value]),
  () => {
    sampleResult.value = undefined;
    busy.value = false;
  },
);
const spotRequests = useObjectRequest(
  () => JSON.stringify([draft.value, props.objects]),
  () => {
    spotResult.value = undefined;
    spotLoading.value = false;
  },
);
const rule = computed(() =>
  props.catalog.find((row) => row.ruleCode === draft.value.ruleCode),
);
const displayCatalog = computed(() => displayRuleCatalog(props.catalog));
const groups = computed(() =>
  dimensionOptions
    .map((group) => ({
      ...group,
      rows: displayCatalog.value.filter(
        (row) =>
          row.qualityDim === group.value &&
          (!dimension.value || dimension.value === row.qualityDim) &&
          (row.ruleName + row.description).includes(search.value.trim()),
      ),
    }))
    .filter((group) => group.rows.length),
);
const [Modal, modalApi] = useVbenModal({
  onClosed() {
    requests.begin();
    spotRequests.begin();
    sampleResult.value = undefined;
    busy.value = false;
  },
  showConfirmButton: false,
  showCancelButton: false,
});
const [SpotModal, spotApi] = useVbenModal({
  onClosed() {
    spotRequests.begin();
    spotLoading.value = false;
  },
  showConfirmButton: false,
  cancelText: '关闭',
});
useObjectRequest(
  () => '',
  () => {
    void modalApi.close();
    void spotApi.close();
  },
);
function open(
  value?: QualityTaskApi.Evaluation,
  index?: number,
  detail = false,
) {
  selection.value = !value;
  readonly.value = detail;
  editIndex.value = index;
  search.value = '';
  dimension.value = '';
  sample.value = '';
  sampleResult.value = undefined;
  error.value = '';
  if (value) draft.value = editableEvaluation(value);
  modalApi.open();
}
function select(value: QualityTaskApi.CatalogRule) {
  if (!value.enabled) return;
  const next = newEvaluation(value);
  draft.value = next;
  selection.value = false;
  sampleResult.value = undefined;
  error.value = '';
}
function payload() {
  const value = JSON.parse(
    JSON.stringify(draft.value),
  ) as QualityTaskApi.Evaluation;
  if (value.strategyKey === 'TIME_ORDER_VALIDATION')
    value.columnNames = [
      ...new Set(
        (
          value.config.conditions as { leftField: string; rightField: string }[]
        ).flatMap((row) => [row.leftField, row.rightField]),
      ),
    ];
  value.columnNames = auditReferencedColumns(
    value.strategyKey,
    value.config,
    value.columnNames,
  );
  if (value.strategyKey === 'RECORD_COMPLETENESS_VALIDATION')
    value.whereClause = '';
  return value;
}
async function save() {
  const value = payload();
  error.value = validateEvaluation(value) ?? '';
  if (error.value) return;
  emit('save', value, editIndex.value);
  await modalApi.close();
}
async function testSample() {
  if (props.allowExecution === false) return;
  if (sample.value === '') {
    message.warning('校验未通过，请添加样例数据');
    return;
  }
  const current = requests.begin();
  busy.value = true;
  sampleResult.value = undefined;
  try {
    const result = await sampleQualityTaskRule({
      evaluation: payload(),
      sampleData: sample.value,
    });
    if (current()) sampleResult.value = result;
  } finally {
    if (current()) busy.value = false;
  }
}
async function spotCheck() {
  if (props.allowExecution === false) return;
  const evaluation = payload();
  error.value = validateEvaluation(evaluation) ?? '';
  if (error.value) return;
  const object = props.objects.find(
    (row) => row.clientKey === evaluation.objectKey,
  );
  if (!object) return;
  spotApi.open();
  spotResult.value = undefined;
  spotLoading.value = true;
  spotError.value = false;
  spotTab.value = 'problem';
  const current = spotRequests.begin();
  try {
    const result = await spotCheckQualityTaskRule({
      object,
      objects: props.objects,
      evaluation,
      limit: 100,
    });
    if (current()) spotResult.value = result;
  } catch {
    if (current()) spotError.value = true;
  } finally {
    if (current()) spotLoading.value = false;
  }
}
function close() {
  requests.begin();
  spotRequests.begin();
  void spotApi.close();
  void modalApi.close();
}
defineExpose({ open, close });
</script>
<template>
  <Modal
    :centered="false"
    :fullscreen-button="false"
    :open-auto-focus="false"
    header-class="q-quality-dialog-header"
    content-class="q-quality-dialog-content"
    footer-class="q-quality-dialog-footer"
    class="q-quality-modal !bg-white !text-[#303133]"
    :class="[selection ? 'q-quality-picker' : 'q-quality-rule-modal']"
    :title="
      selection
        ? '新增评测规则'
        : `${readonly ? '评测规则详情' : editIndex === undefined ? '新增评测规则' : '修改评测规则'}-${rule?.ruleName ?? draft.ruleCode}`
    "
  >
    <div v-if="selection" class="q-rule-picker">
      <aside>
        <button type="button" class="q-rule-tree-root" @click="dimension = ''">
          ⌄ 稽查规则 <span>({{ displayCatalog.length }})</span>
</button><button
          v-for="group in dimensionOptions"
          :key="group.value"
          type="button"
          :class="{ active: dimension === group.value }"
          @click="dimension = group.value"
        >
          ▤ {{ group.label }}
          <span>({{
              displayCatalog.filter((row) => row.qualityDim === group.value)
                .length
            }})</span>
        </button>
      </aside>
      <main>
        <Input
          v-model:value="search"
          placeholder="请输入你要搜索的内容"
          allow-clear
          class="q-rule-search"
        />
        <section v-for="group in groups" :key="group.value">
          <h3>{{ group.label }}</h3>
          <div class="q-rule-cards">
            <button
              v-for="item in group.rows"
              :key="item.ruleCode"
              type="button"
              class="q-rule-card"
              :disabled="!item.enabled"
              @click="select(item)"
            >
              <img
                class="q-rule-icon"
                :src="ruleIcon(item.strategyKey, item.ruleCode)"
                alt=""
              />
              <span><strong>{{ item.ruleName }}</strong><small>{{ item.description }}</small></span>
            </button>
          </div>
        </section>
        <div v-if="!groups.length" class="q-empty">暂无记录</div>
      </main>
    </div>
    <div v-else class="q-rule-body">
      <h3 class="q-section">基础信息</h3>
      <div class="q-form-grid q-base-grid">
        <label class="q-field"><span class="required">评测名称</span><Input
            v-model:value="draft.evaluationName"
            :disabled="readonly"
            :maxlength="128"
            placeholder="请输入评测名称"
        /></label>
        <label class="q-field"><span>稽查规则编号</span><Input :value="draft.ruleCode" readonly /></label>
        <label class="q-field"><span>稽查规则名称</span><Input :value="rule?.ruleName ?? draft.ruleCode" readonly /></label>
        <label class="q-field"><span>告警等级</span><Select
            v-model:value="draft.severity"
            :disabled="readonly"
            :options="[
              { label: '告警', value: 'LOW' },
              { label: '一般', value: 'MID' },
              { label: '严重', value: 'CRITICAL' },
            ]"
        /></label>
        <label class="q-field"><span>状态</span><RadioGroup
            v-model:value="draft.status"
            :disabled="readonly"
            :options="[
              { label: '上线', value: 0 },
              { label: '下线', value: 1 },
            ]"
        /></label>
      </div>
      <div class="q-descriptions">
        <label
          v-for="entry in [
            { key: 'description' as const, label: '规则描述' },
            { key: 'errorExample' as const, label: '错误示例' },
            { key: 'repairAdvice' as const, label: '修复建议' },
            ...(draft.strategyKey === 'RECORD_COMPLETENESS_VALIDATION'
              ? []
              : [{ key: 'whereClause' as const, label: 'Where 条件' }]),
          ]"
          :key="entry.key"
          class="q-field"
          ><span>{{ entry.label }}</span><TextArea
            v-model:value="draft[entry.key]"
            :disabled="readonly"
            :rows="3"
            :maxlength="256"
            show-count
            :placeholder="
              entry.key === 'whereClause'
                ? '仅支持检查字段的比较与逻辑条件，不支持函数或子查询'
                : `请输入${entry.label}`
            "
        /></label>
      </div>
      <h3 class="q-section">规则配置</h3>
      <RuleConfig
        :key="`${draft.ruleCode}/${editIndex}`"
        :value="draft"
        :objects="objects"
        :readonly="readonly"
        @update:value="Object.assign(draft, $event)"
      />
      <template v-if="rule?.sampleSupported && allowExecution !== false">
        <h3 class="q-section">样例监测</h3>
        <div class="q-sample">
          <label class="q-field"><span>样例数据</span>
            <div>
              <Input v-model:value="sample" placeholder="请输入样例数据" /><span
                v-if="sampleResult"
                class="q-sample-result"
                :class="{ 'q-error': !sampleResult.passed }"
                >{{ sampleResult.message }}</span>
            </div></label><Button class="q-query" :loading="busy" @click="testSample">
            <template #icon><IconifyIcon icon="lucide:scan-search" /></template>
            监测
          </Button>
        </div>
      </template>
      <Alert v-if="error" type="error" :message="error" show-icon />
    </div>
    <template #footer>
      <Button
        v-if="!selection && allowExecution !== false"
        class="q-warning"
        @click="spotCheck"
      >
        <template #icon><IconifyIcon icon="lucide:search" /></template>
        抽查
</Button><Button v-if="!selection && !readonly" type="primary" @click="save">
        确定
</Button><Button
        v-if="!selection && !readonly && editIndex === undefined"
        @click="selection = true"
      >
        返回
</Button><Button @click="modalApi.close()">关闭</Button>
    </template>
  </Modal>
  <SpotModal
    :centered="false"
    class="q-quality-modal q-quality-spot-modal !bg-white !text-[#303133]"
    title="查看抽查结果"
    :fullscreen-button="false"
    header-class="q-quality-dialog-header"
    footer-class="q-quality-dialog-footer"
  >
    <Spin :spinning="spotLoading">
      <Alert v-if="spotError" type="error" message="抽查失败，请重试">
        <template #action>
          <Button @click="spotCheck">
            <template #icon>
              <IconifyIcon icon="lucide:refresh-cw" />
            </template>
            重试
          </Button>
        </template>
</Alert><Tabs v-model:active-key="spotTab">
        <TabPane key="problem" tab="问题数据" /><TabPane
          key="normal"
          tab="正常数据"
        />
      </Tabs>
      <div class="q-table-scroll">
        <table class="q-table">
          <thead>
            <tr>
              <th
                v-for="column in spotResult?.columns?.length
                  ? spotResult.columns
                  : ['结果']"
                :key="column"
              >
                {{ column }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(row, index) in spotTab === 'problem'
                ? spotResult?.problemRows
                : spotResult?.normalRows"
              :key="index"
            >
              <td v-for="column in spotResult?.columns" :key="column">
                {{ row[column] ?? '-' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div
        v-if="
          !(
            spotTab === 'problem'
              ? spotResult?.problemRows
              : spotResult?.normalRows
          )?.length &&
          !spotLoading &&
          !spotError
        "
        class="q-empty"
      >
        <img :src="emptyImage" alt="" />暂无记录
      </div>
    </Spin>
  </SpotModal>
</template>
