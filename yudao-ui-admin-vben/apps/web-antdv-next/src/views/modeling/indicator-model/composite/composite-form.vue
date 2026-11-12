<script setup lang="ts">
import type { DependencyTreeNode } from './formula';

import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Col,
  Empty,
  Form,
  FormItem,
  Input,
  message,
  Row,
  Select,
  Spin,
  Tag,
  TextArea,
  Tree,
} from 'antdv-next';

import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import {
  createIndicator,
  getActiveIndicatorOptions,
  getIndicator,
  updateIndicator,
  validateIndicatorFormula,
} from '#/api/modeling/indicator-model';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

import { TYPE_LABELS } from '../data';
import {
  checkFormula,
  compositeSaveData,
  dependencyTree,
  FORMULA_MAX_LENGTH,
  insertReference,
} from './formula';

const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = () =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]);

type Option = { label: string; value: string };
interface FormState {
  code: string;
  dataDomainId?: string;
  definition?: string;
  description?: string;
  formula: string;
  name: string;
  ownerUserId?: string;
  subjectDomainId?: string;
  unit?: string;
}

const emptyForm = (): FormState => ({ code: '', formula: '', name: '' });
const form = ref<FormState>(emptyForm());
const original = ref<IndicatorModelApi.Model>();
const ready = ref(false);
const error = ref('');
const domains = ref<Option[]>([]);
const subjects = ref<Option[]>([]);
const owners = ref<Option[]>([]);
const indicators = ref<IndicatorModelApi.Option[]>([]);
const optionsLoading = ref(false);
const insertCode = ref<string>();
const formulaInput = ref<any>();

const validating = ref(false);
const serverCheck = ref<IndicatorModelApi.FormulaValidateResp>();
const checkedFormula = ref('');
const tree = ref<DependencyTreeNode[]>([]);

const loadGuard = useObjectRequest(scope, () => {
  void modalApi.close();
});
const optionGuard = useObjectRequest(scope, () => {
  indicators.value = [];
});
const validateGuard = useObjectRequest(scope, () => {
  serverCheck.value = undefined;
  validating.value = false;
});
let timer: ReturnType<typeof setTimeout> | undefined;
onBeforeUnmount(() => timer && clearTimeout(timer));

const local = computed(() => checkFormula(form.value.formula));
const title = computed(() =>
  original.value ? '编辑复合指标' : '新增复合指标',
);
const referenceOptions = computed(() =>
  indicators.value
    .filter((item) => String(item.id) !== String(original.value?.id ?? ''))
    .map((item) => ({
      value: item.code,
      label: `[${TYPE_LABELS[item.indicatorType] ?? item.indicatorType}] ${item.name} (${item.code})`,
    })),
);
const formulaStatus = computed(() => {
  if (!form.value.formula.trim()) return undefined;
  if (!local.value.ok)
    return { type: 'error' as const, text: local.value.message };
  if (!form.value.dataDomainId)
    return { type: 'warning' as const, text: '请选择数据域后校验引用' };
  if (validating.value || checkedFormula.value !== currentCheckKey())
    return { type: 'info' as const, text: '正在校验引用与依赖…' };
  if (!serverCheck.value) return undefined;
  return serverCheck.value.valid
    ? {
        type: 'success' as const,
        text: '公式有效，引用均为同数据域已启用指标，且无循环引用',
      }
    : { type: 'error' as const, text: serverCheck.value.message };
});
const canSave = computed(
  () =>
    ready.value &&
    local.value.ok &&
    !!form.value.dataDomainId &&
    !!serverCheck.value?.valid &&
    checkedFormula.value === currentCheckKey() &&
    !validating.value,
);

function currentCheckKey() {
  return JSON.stringify([
    form.value.formula.trim(),
    form.value.dataDomainId ?? '',
    form.value.code.trim(),
  ]);
}

/** 防抖 + latest-wins：只有最后一次请求可以写入校验结果。 */
function scheduleValidate() {
  if (timer) clearTimeout(timer);
  serverCheck.value = undefined;
  tree.value = [];
  if (!ready.value || !local.value.ok || !form.value.dataDomainId) {
    validateGuard.begin();
    validating.value = false;
    return;
  }
  timer = setTimeout(() => void validate(), 350);
}

async function validate() {
  const current = validateGuard.begin();
  const key = currentCheckKey();
  validating.value = true;
  try {
    const result = await validateIndicatorFormula({
      id: original.value?.id,
      code: form.value.code.trim() || undefined,
      dataDomainId: form.value.dataDomainId!,
      formula: form.value.formula.trim(),
    });
    if (!current()) return;
    serverCheck.value = result;
    checkedFormula.value = key;
    tree.value = dependencyTree(result.dependencies ?? []);
  } catch {
    if (current()) {
      serverCheck.value = {
        valid: false,
        message: '公式校验请求失败，请重试',
        references: [],
        dependencies: [],
      };
      checkedFormula.value = key;
    }
  } finally {
    if (current()) validating.value = false;
  }
}

watch(
  () => currentCheckKey(),
  () => scheduleValidate(),
);

async function loadIndicators() {
  const current = optionGuard.begin();
  indicators.value = [];
  insertCode.value = undefined;
  if (!form.value.dataDomainId) return;
  optionsLoading.value = true;
  try {
    const list = await getActiveIndicatorOptions(form.value.dataDomainId);
    if (current()) indicators.value = list;
  } catch {
    if (current()) error.value = '可引用指标加载失败，请重新选择数据域。';
  } finally {
    if (current()) optionsLoading.value = false;
  }
}

watch(
  () => form.value.dataDomainId,
  (value, previous) => {
    if (ready.value && value !== previous) void loadIndicators();
  },
);

async function insert(code?: string) {
  if (!code) return;
  const element: HTMLTextAreaElement | undefined =
    formulaInput.value?.$el?.querySelector?.('textarea') ??
    formulaInput.value?.resizableTextArea?.textArea;
  const next = insertReference(
    form.value.formula,
    code,
    element?.selectionStart,
    element?.selectionEnd,
  );
  form.value.formula = next.value;
  insertCode.value = undefined;
  await nextTick();
  element?.focus();
  element?.setSelectionRange(next.cursor, next.cursor);
}

function appendOperator(operator: string) {
  const text = form.value.formula;
  form.value.formula = `${text}${text && !/\s$/.test(text) ? ' ' : ''}${operator} `;
}

async function load() {
  const current = loadGuard.begin();
  ready.value = false;
  error.value = '';
  modalApi.setState({ loading: true });
  try {
    const data = modalApi.getData();
    const [detail, domainList, subjectList, ownerList] = await Promise.all([
      data?.id ? getIndicator(data.id) : Promise.resolve(undefined),
      getDataDomainSimpleList(),
      hasAccessByCodes(['modeling:subject-domain:query'])
        ? getSubjectDomainList({ status: 0 })
        : Promise.resolve([]),
      getSimpleUserList(),
    ]);
    if (!current()) return;
    if (detail && detail.indicatorType !== 'COMPOSITE') {
      error.value = '该指标不是复合指标。';
      return;
    }
    if (detail && detail.status !== 'DRAFT') {
      error.value = '启用的指标需先停用为草稿后编辑。';
      return;
    }
    original.value = detail;
    domains.value = domainList
      .filter(
        (item) =>
          item.id !== undefined &&
          (item.status === 0 ||
            String(item.id) === String(detail?.dataDomainId)),
      )
      .map((item) => ({
        value: String(item.id),
        label: item.name || String(item.id),
      }));
    subjects.value = subjectList.map((item) => ({
      value: String(item.id),
      label: item.name || String(item.id),
    }));
    owners.value = ownerList.map((item) => ({
      value: String(item.id),
      label: item.nickname || item.username,
    }));
    form.value = detail
      ? {
          code: detail.code,
          name: detail.name,
          dataDomainId: String(detail.dataDomainId),
          subjectDomainId: detail.subjectDomainId
            ? String(detail.subjectDomainId)
            : undefined,
          ownerUserId: detail.ownerUserId
            ? String(detail.ownerUserId)
            : undefined,
          unit: detail.unit,
          definition: detail.definition,
          description: detail.description,
          formula: detail.formula ?? '',
        }
      : emptyForm();
    ready.value = true;
    await loadIndicators();
    if (current()) scheduleValidate();
  } catch {
    if (current()) error.value = '复合指标或关联选项加载失败，请重试。';
  } finally {
    if (current()) modalApi.setState({ loading: false });
  }
}

const [Modal, modalApi] = useVbenModal<{ id?: IndicatorModelApi.Id }>({
  async onConfirm() {
    if (!canSave.value) return;
    if (
      !/^[a-z]\w{0,99}$/i.test(form.value.code.trim()) ||
      !form.value.name.trim()
    ) {
      message.warning('请填写字母开头的英文编码与指标名称');
      return;
    }
    const current = loadGuard.begin();
    modalApi.lock();
    try {
      const data = compositeSaveData(
        {
          ...form.value,
          dataDomainId: form.value.dataDomainId!,
        },
        original.value,
      );
      await (original.value ? updateIndicator(data) : createIndicator(data));
      if (!current()) return;
      emit('success');
      message.success('复合指标已保存');
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(open) {
    if (open) {
      await load();
      return;
    }
    if (timer) clearTimeout(timer);
    loadGuard.begin();
    optionGuard.begin();
    validateGuard.begin();
    ready.value = false;
    original.value = undefined;
    form.value = emptyForm();
    serverCheck.value = undefined;
    checkedFormula.value = '';
    tree.value = [];
    indicators.value = [];
    validating.value = false;
    optionsLoading.value = false;
    error.value = '';
    modalApi.setState({ loading: false });
    modalApi.unlock();
  },
});
</script>

<template>
  <Modal :title="title" class="w-[960px]" :confirm-disabled="!canSave">
    <Alert
      class="mb-4"
      type="info"
      show-icon
      message="复合指标由公式引用同数据域的已启用指标（可引用其他复合指标），结果固定为小数，在服务端按维度精确计算；分母为 0 时结果为空。"
    />
    <Alert v-if="error" class="mb-4" type="error" :message="error">
      <template #action>
        <Button size="small" @click="load">重试</Button>
      </template>
    </Alert>
    <Form v-if="ready" layout="vertical">
      <Row :gutter="16">
        <Col :xs="24" :md="12">
          <FormItem label="英文编码" required>
            <Input
              v-model:value="form.code"
              :maxlength="100"
              :disabled="!!original"
              placeholder="字母开头，如 avg_order_amount"
            />
          </FormItem>
        </Col>
        <Col :xs="24" :md="12">
          <FormItem label="指标名称" required>
            <Input
              v-model:value="form.name"
              :maxlength="100"
              placeholder="如 客单价"
            />
          </FormItem>
        </Col>
        <Col :xs="24" :md="8">
          <FormItem label="数据域" required>
            <Select
              v-model:value="form.dataDomainId"
              :options="domains"
              show-search
              option-filter-prop="label"
              placeholder="请选择数据域"
            />
          </FormItem>
        </Col>
        <Col :xs="24" :md="8">
          <FormItem label="主题域">
            <Select
              v-model:value="form.subjectDomainId"
              :options="subjects"
              allow-clear
              show-search
              option-filter-prop="label"
              placeholder="可选"
            />
          </FormItem>
        </Col>
        <Col :xs="24" :md="8">
          <FormItem label="负责人">
            <Select
              v-model:value="form.ownerUserId"
              :options="owners"
              allow-clear
              show-search
              option-filter-prop="label"
              placeholder="可选"
            />
          </FormItem>
        </Col>
      </Row>
      <FormItem label="计算公式" required>
        <div class="mb-2 flex flex-wrap items-center gap-2">
          <Select
            v-model:value="insertCode"
            class="min-w-[280px] flex-1"
            :options="referenceOptions"
            :loading="optionsLoading"
            :disabled="!form.dataDomainId"
            show-search
            option-filter-prop="label"
            :placeholder="
              form.dataDomainId ? '选择指标插入到光标处' : '请先选择数据域'
            "
            @change="(value: any) => insert(value as string)"
          />
          <Button
            v-for="op in ['+', '-', '*', '/', '(', ')']"
            :key="op"
            @click="appendOperator(op)"
          >
            {{ op }}
          </Button>
        </div>
        <TextArea
          ref="formulaInput"
          v-model:value="form.formula"
          :maxlength="FORMULA_MAX_LENGTH"
          :rows="3"
          class="font-mono"
          placeholder="例如：{gmv} / {order_cnt} * 100"
        />
        <Alert
          v-if="formulaStatus"
          class="mt-2"
          :type="formulaStatus.type"
          show-icon
          :message="formulaStatus.text"
        />
        <div
          v-if="local.references.length > 0"
          class="mt-2 flex flex-wrap gap-1"
        >
          <Tag v-for="code in local.references" :key="code" color="blue">
            {{ code }}
          </Tag>
        </div>
      </FormItem>
      <FormItem label="依赖树">
        <Spin :spinning="validating">
          <Tree
            v-if="tree.length > 0"
            :tree-data="tree"
            default-expand-all
            :selectable="false"
          />
          <Empty v-else description="公式校验通过后显示依赖指标" />
        </Spin>
      </FormItem>
      <Row :gutter="16">
        <Col :xs="24" :md="8">
          <FormItem label="单位">
            <Input
              v-model:value="form.unit"
              :maxlength="50"
              placeholder="如 元、%"
            />
          </FormItem>
        </Col>
        <Col :xs="24" :md="16">
          <FormItem label="指标口径">
            <Input v-model:value="form.definition" :maxlength="2000" />
          </FormItem>
        </Col>
      </Row>
      <FormItem label="说明">
        <TextArea
          v-model:value="form.description"
          :maxlength="2000"
          :rows="2"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
