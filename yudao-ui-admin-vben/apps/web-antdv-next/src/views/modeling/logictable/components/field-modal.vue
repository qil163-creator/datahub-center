<script setup lang="ts">
import type { SafetyCategoryApi } from '#/api/governance/category';
import type { SafetyLevelApi } from '#/api/governance/level';
import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';

import {
  Input,
  InputNumber,
  Modal,
  Radio,
  RadioGroup,
  Select,
  TextArea,
} from 'antdv-next';

import { getCategoryPage } from '#/api/governance/category';
import { getLevelPage } from '#/api/governance/level';
import { getStandardCodeSetPage } from '#/api/governance/standardcodeset';
import { getStandardElementPage } from '#/api/governance/standardelement';
import { getTableModelPage } from '#/api/modeling/tablemodel';

import {
  detailText,
  FIELD_CATEGORY_OPTIONS,
  fieldCategory,
  fieldConstraints,
} from '../detail-state';
import { loadLogicOptionPages } from '../options';

const props = withDefaults(defineProps<{ saving?: boolean }>(), {
  saving: false,
});
const emit = defineEmits<{ submit: [field: ModelingModelApi.ModelField] }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const open = ref(false);
const readonly = ref(false);
const editing = ref(false);
const primaryLocked = ref(false);
const errors = ref(false);
const optionError = ref(false);
const optionsLoading = ref(false);
const draft = reactive<ModelingModelApi.ModelField>({});
const categories = ref<SafetyCategoryApi.Category[]>([]);
const levels = ref<SafetyLevelApi.Level[]>([]);
const elements = ref<GovernanceStandardElementApi.StandardElement[]>([]);
const codeTables = ref<GovernanceStandardCodeSetApi.StandardCodeSet[]>([]);
const dimensions = ref<TableModelApi.Model[]>([]);
let sequence = 0;
const title = computed(() => {
  if (readonly.value) return '字段详情';
  return editing.value ? '修改字段' : '新增字段';
});
const decimalTypes = new Set([
  'DECIMAL',
  'DOUBLE',
  'FLOAT',
  'NUMBER',
  'NUMERIC',
]);
const scaleDisabled = computed(() => !decimalTypes.has(draft.dataType || ''));
const dataTypes = [
  'VARCHAR',
  'CHAR',
  'TEXT',
  'INTEGER',
  'BIGINT',
  'TINYINT',
  'DECIMAL',
  'NUMERIC',
  'NUMBER',
  'FLOAT',
  'DOUBLE',
  'DATE',
  'DATETIME',
  'TIMESTAMP',
  'BOOLEAN',
].map((value) => ({ label: value, value }));
const constraints = computed({
  get: () =>
    [
      draft.nullableFlag === false ? 'NOT_NULL' : '',
      draft.uniqueFlag || draft.pkFlag ? 'UNIQUE' : '',
    ].filter(Boolean),
  set: (value: string[]) => {
    draft.nullableFlag = !value.includes('NOT_NULL');
    draft.uniqueFlag = value.includes('UNIQUE');
  },
});
const categoryOptions = computed(() =>
  categories.value.map((item) => ({
    label: item.name,
    value: String(item.id),
  })),
);
const elementOptions = computed(() =>
  elements.value.map((item) => ({ label: item.name, value: String(item.id) })),
);
const codeOptions = computed(() =>
  codeTables.value.map((item) => ({
    label: item.name,
    value: String(item.id),
  })),
);
const dimensionOptions = computed(() =>
  dimensions.value.map((item) => ({
    label: item.name || item.code || String(item.id),
    value: String(item.id),
  })),
);
const dimensionValue = computed(() =>
  draft.dimensionId === undefined ? undefined : String(draft.dimensionId),
);
const categoryValue = computed(() =>
  draft.dataClassificationId === undefined
    ? undefined
    : String(draft.dataClassificationId),
);
const standardValue = computed(() =>
  draft.standardId === undefined || draft.standardId === null
    ? undefined
    : String(draft.standardId),
);
const codeValue = computed(() =>
  draft.codeTableId === undefined ? undefined : String(draft.codeTableId),
);
const detailItems = computed(() => [
  ['字段英文名称', draft.fieldName],
  ['字段中文名称', draft.cnName],
  ['数据类型', draft.dataType],
  ['属性长度', draft.dataLength ?? draft.dataPrecision],
  ['小数位数', draft.dataScale],
  ['默认值', draft.defaultValue],
  ['字段类别', fieldCategory(draft)],
  ...(draft.fieldCategory === 'DIMENSION'
    ? [['关联维度', draft.dimensionName]]
    : []),
  ['数据分类', draft.dataClassificationName],
  ['数据分级', draft.dataGrade],
  ['字段约束', fieldConstraints(draft).join(' / ') || '无'],
  ['关联数据元', draft.standardName],
  ['关联代码表', draft.codeTableName],
  ['是否主键', draft.pkFlag ? '是' : '否'],
]);

async function loadOptions() {
  const request = ++sequence;
  optionsLoading.value = true;
  optionError.value = false;
  const can = (code: string) => hasAccessByCodes([code]);
  const results = await Promise.allSettled([
    can('governance:category:query')
      ? loadLogicOptionPages(getCategoryPage).then((list) => ({ list }))
      : Promise.resolve({ list: [] }),
    can('governance:level:query')
      ? loadLogicOptionPages(getLevelPage)
      : Promise.resolve([]),
    can('governance:standard-element:query')
      ? loadLogicOptionPages(getStandardElementPage).then((list) => ({ list }))
      : Promise.resolve({ list: [] }),
    can('governance:standard-code-set:query')
      ? loadLogicOptionPages(getStandardCodeSetPage).then((list) => ({ list }))
      : Promise.resolve({ list: [] }),
    can('modeling:model:query')
      ? loadLogicOptionPages((page) =>
          getTableModelPage({ ...page, tableType: 'DIMENSION' }),
        )
      : Promise.resolve([]),
  ]);
  if (request !== sequence || !open.value) return;
  const [category, level, element, code, dimension] = results;
  categories.value = category.status === 'fulfilled' ? category.value.list : [];
  levels.value = level.status === 'fulfilled' ? level.value : [];
  elements.value = element.status === 'fulfilled' ? element.value.list : [];
  codeTables.value = code.status === 'fulfilled' ? code.value.list : [];
  dimensions.value = dimension.status === 'fulfilled' ? dimension.value : [];
  optionError.value = results.some((item) => item.status === 'rejected');
  optionsLoading.value = false;
}

function show(
  field?: ModelingModelApi.ModelField,
  mode: 'detail' | 'edit' = 'edit',
) {
  sequence++;
  Object.keys(draft).forEach((key) => Reflect.deleteProperty(draft, key));
  Object.assign(
    draft,
    field
      ? { ...field }
      : {
          fieldUid: globalThis.crypto.randomUUID(),
          nullableFlag: true,
          pkFlag: false,
        },
  );
  primaryLocked.value = !!field?.pkFlag;
  readonly.value = mode === 'detail';
  editing.value = !!field;
  errors.value = false;
  open.value = true;
  if (!readonly.value) void loadOptions();
}

function pickCategory(value?: string) {
  const selected = categories.value.find((item) => String(item.id) === value);
  draft.dataClassificationId = selected?.id;
  draft.dataClassificationName = selected?.name;
  const level = levels.value.find(
    (item) => String(item.id) === String(selected?.dataLevelId),
  );
  draft.dataGrade = level?.safetyName || level?.safetyLevel;
}
function pickStandard(value?: string) {
  const selected = elements.value.find((item) => String(item.id) === value);
  draft.standardId = selected?.id;
  draft.standardName = selected?.name;
  draft.standardCode = selected?.code;
}
function pickCode(value?: string) {
  const selected = codeTables.value.find((item) => String(item.id) === value);
  draft.codeTableId = selected?.id;
  draft.codeTableName = selected?.name;
}
function pickDimension(value?: string) {
  const selected = dimensions.value.find((item) => String(item.id) === value);
  draft.dimensionId = selected?.id;
  draft.dimensionName = selected?.name;
}
function changeCategory(value: string) {
  if (value !== 'DIMENSION') {
    draft.dimensionId = undefined;
    draft.dimensionName = undefined;
  }
}
function submit() {
  if (props.saving) return;
  errors.value = true;
  if (
    !/^[A-Z_a-z][\w]*$/.test(draft.fieldName || '') ||
    !draft.cnName?.trim() ||
    !draft.dataType ||
    !draft.fieldCategory ||
    (draft.fieldCategory === 'DIMENSION' && draft.dimensionId === undefined)
  )
    return;
  emit('submit', {
    ...draft,
    fieldName: draft.fieldName?.trim(),
    cnName: draft.cnName.trim(),
    dataScale: scaleDisabled.value ? undefined : draft.dataScale,
  });
}
watch(
  () => draft.pkFlag,
  (value) => {
    if (value) {
      draft.fieldCategory = 'PRIMARY_KEY';
      draft.nullableFlag = false;
      draft.uniqueFlag = true;
    } else if (draft.fieldCategory === 'PRIMARY_KEY')
      draft.fieldCategory = 'ATTRIBUTE';
  },
);
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    JSON.stringify(access.accessCodes),
  ],
  () => {
    sequence++;
    open.value = false;
  },
);
watch(open, (value) => {
  if (!value) {
    sequence++;
    optionsLoading.value = false;
  }
});
onBeforeUnmount(() => sequence++);
defineExpose({
  open: show,
  close: () => {
    open.value = false;
  },
});
</script>

<template>
  <Modal
    v-model:open="open"
    :title="title"
    class="qd-logic-field-modal"
    :width="1092"
    :footer="null"
    :mask-closable="!saving"
    :closable="!saving"
    destroy-on-hidden
    :style="{ top: '68px' }"
  >
    <div v-if="readonly" class="qd-logic-field-body qd-logic-field-readonly">
      <div
        v-for="(item, index) in detailItems"
        :key="index"
        class="qd-logic-field-item"
      >
        <label>{{ item[0] }}</label>
        <div class="qd-logic-field-value">{{ detailText(item[1]) }}</div>
      </div>
      <div class="qd-logic-field-item qd-logic-field-full">
        <label>描述</label>
        <div class="qd-logic-field-value qd-logic-field-description">
          {{ detailText(draft.comment) }}
        </div>
      </div>
    </div>
    <form v-else class="qd-logic-field-body" @submit.prevent="submit">
      <div class="qd-logic-field-item">
        <label><i>*</i>字段英文名称</label>
        <div>
          <Input
            v-model:value="draft.fieldName"
            placeholder="请输入字段英文名称"
            :status="
              errors && !/^[A-Z_a-z][\w]*$/.test(draft.fieldName || '')
                ? 'error'
                : undefined
            "
          /><small
            v-if="errors && !/^[A-Z_a-z][\w]*$/.test(draft.fieldName || '')"
            >请输入以字母或下划线开头的英文名称</small>
        </div>
      </div>
      <div class="qd-logic-field-item">
        <label><i>*</i>字段中文名称</label>
        <div>
          <Input
            v-model:value="draft.cnName"
            placeholder="请输入字段中文名称"
            :status="errors && !draft.cnName?.trim() ? 'error' : undefined"
          />
        </div>
      </div>
      <div class="qd-logic-field-item">
        <label><i>*</i>数据类型</label><Select
          v-model:value="draft.dataType"
          :disabled="primaryLocked"
          :options="dataTypes"
          placeholder="请选择字段数据类型"
          :status="errors && !draft.dataType ? 'error' : undefined"
        />
      </div>
      <div class="qd-logic-field-item">
        <label><i>*</i>字段类别</label><Input v-if="draft.pkFlag" value="主键" disabled /><Select
          v-else
          v-model:value="draft.fieldCategory"
          :options="FIELD_CATEGORY_OPTIONS.map((item) => ({ ...item }))"
          placeholder="请选择字段类别"
          @change="changeCategory"
        />
      </div>
      <div
        v-if="draft.fieldCategory === 'DIMENSION'"
        class="qd-logic-field-item"
      >
        <label><i>*</i>关联维度</label><Select
          :value="dimensionValue"
          :options="dimensionOptions"
          :loading="optionsLoading"
          :disabled="saving"
          :status="
            errors && draft.dimensionId === undefined ? 'error' : undefined
          "
          placeholder="请选择关联维度"
          allow-clear
          show-search
          option-filter-prop="label"
          @change="pickDimension"
        />
      </div>
      <div v-if="draft.fieldCategory === 'DIMENSION'"></div>
      <div class="qd-logic-field-item">
        <label>属性长度</label><InputNumber
          v-model:value="draft.dataLength"
          :min="1"
          :precision="0"
          placeholder="请输入长度"
        />
      </div>
      <div class="qd-logic-field-item">
        <label>小数位数</label><InputNumber
          v-model:value="draft.dataScale"
          :min="0"
          :precision="0"
          :disabled="scaleDisabled"
          placeholder="请输入小数位数"
        />
      </div>
      <div class="qd-logic-field-item">
        <label>默认值</label><Input v-model:value="draft.defaultValue" placeholder="请输入默认值" />
      </div>
      <div class="qd-logic-field-item">
        <label>数据分类</label><Select
          :value="categoryValue"
          :options="categoryOptions"
          :loading="optionsLoading"
          :disabled="saving"
          placeholder="请选择数据分类"
          allow-clear
          show-search
          option-filter-prop="label"
          @change="pickCategory"
        />
      </div>
      <div class="qd-logic-field-item">
        <label>数据分级</label><Input
          :value="draft.dataGrade"
          disabled
          placeholder="请选择数据分级"
        />
      </div>
      <div class="qd-logic-field-item">
        <label>字段约束</label><Select
          v-model:value="constraints"
          mode="multiple"
          :disabled="!!draft.pkFlag"
          :options="[
            { label: '非空', value: 'NOT_NULL' },
            { label: '唯一性', value: 'UNIQUE' },
          ]"
          placeholder="请选择字段约束"
        />
      </div>
      <div class="qd-logic-field-item">
        <label>关联数据元</label><Select
          :value="standardValue"
          :options="elementOptions"
          :loading="optionsLoading"
          allow-clear
          show-search
          option-filter-prop="label"
          placeholder="请选择关联数据元"
          @change="pickStandard"
        />
      </div>
      <div class="qd-logic-field-item">
        <label>关联代码表</label><Select
          :value="codeValue"
          :options="codeOptions"
          :loading="optionsLoading"
          allow-clear
          show-search
          option-filter-prop="label"
          placeholder="请选择关联代码表"
          @change="pickCode"
        />
      </div>
      <div class="qd-logic-field-item">
        <label>是否主键</label><RadioGroup v-model:value="draft.pkFlag" :disabled="primaryLocked">
          <Radio :value="true">是</Radio><Radio :value="false">否</Radio>
        </RadioGroup>
      </div>
      <div></div>
      <div class="qd-logic-field-item qd-logic-field-full">
        <label>描述</label><TextArea
          v-model:value="draft.comment"
          :maxlength="256"
          :rows="4"
          show-count
          placeholder="请输入字段的业务含义、使用范围或补充说明"
        />
      </div>
    </form>
    <div class="qd-logic-field-footer">
      <button
        type="button"
        class="qd-logic-detail-btn"
        :disabled="saving"
        @click="open = false"
      >
        {{ readonly ? '关闭' : '取消' }}
</button><button
        v-if="!readonly"
        type="button"
        class="qd-logic-detail-btn primary"
        :disabled="saving"
        @click="submit"
      >
        {{ saving ? '保存中…' : '确定' }}
      </button>
    </div>
  </Modal>
</template>
