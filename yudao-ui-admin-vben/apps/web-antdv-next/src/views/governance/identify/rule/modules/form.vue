<script lang="ts" setup>
import type { SafetyCategoryApi } from '#/api/governance/category';
import type { SafetyIdentifyApi } from '#/api/governance/identify';
import type { SafetyLevelApi } from '#/api/governance/level';

import { computed, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import {
  Input,
  InputNumber,
  message,
  RadioGroup,
  Select,
  TextArea,
} from 'antdv-next';

import { getCategoryPage } from '#/api/governance/category';
import {
  createIdentifyRule,
  getIdentifyRule,
  updateIdentifyRule,
} from '#/api/governance/identify';
import { getLevelPage } from '#/api/governance/level';
import { $t } from '#/locales';

import { MATCH_TYPE_OPTIONS } from '../data';

defineOptions({ name: 'SafetyIdentifyRuleForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<SafetyIdentifyApi.IdentifyRule>({});
const categories = ref<SafetyCategoryApi.Category[]>([]);
const levels = ref<SafetyLevelApi.Level[]>([]);

const getTitle = computed(() =>
  model.id
    ? $t('ui.actionTitle.edit', ['识别规则'])
    : $t('ui.actionTitle.create', ['识别规则']),
);

const patternPlaceholder = computed(() => {
  switch (model.matchType) {
    case 'COLUMN_COMMENT': {
      return '关键词列表，逗号分隔，如：手机,电话,联系方式';
    }
    case 'DATA_REGEX': {
      return String.raw`数据内容正则，如：^1[3-9]\d{9}$`;
    }
    default: {
      return '字段名正则（忽略大小写），如：(?i)(mobile|phone|tel)';
    }
  }
});

function categoryOptions() {
  return categories.value.map((item) => ({
    label: item.name,
    value: item.id,
  }));
}

function levelOptions() {
  return levels.value.map((item) => ({
    label: item.safetyName ?? String(item.id),
    value: item.id,
  }));
}

function resetModel() {
  Object.assign(model, {
    id: undefined,
    ruleName: undefined,
    matchType: 'COLUMN_NAME',
    pattern: undefined,
    categoryId: undefined,
    categoryName: undefined,
    levelId: undefined,
    levelName: undefined,
    minHitRatio: 80,
    sampleRows: 50,
    builtinFlag: undefined,
    status: CommonStatusEnum.ENABLE,
    sort: undefined,
    description: undefined,
    createTime: undefined,
  });
}

function validate(): null | string {
  if (!model.ruleName?.trim()) {
    return '请填写规则名称';
  }
  if (!model.matchType) {
    return '请选择匹配方式';
  }
  if (!model.pattern?.trim()) {
    return '请填写匹配模式';
  }
  if (!model.categoryId) {
    return '请选择数据分类';
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
    try {
      const payload: SafetyIdentifyApi.IdentifyRule = {
        ...model,
        ruleName: model.ruleName?.trim(),
        pattern: model.pattern?.trim(),
      };
      await (model.id
        ? updateIdentifyRule(payload)
        : createIdentifyRule(payload));
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      resetModel();
      return;
    }
    resetModel();
    modalApi.lock();
    try {
      const [categoryPage, levelPage] = await Promise.all([
        getCategoryPage({ pageNo: 1, pageSize: 100 }),
        getLevelPage({ pageNo: 1, pageSize: 100 }),
      ]);
      categories.value = categoryPage.list ?? [];
      levels.value = levelPage.list ?? [];
      const data = modalApi.getData() as null | SafetyIdentifyApi.IdentifyRule;
      if (!data?.id) {
        return;
      }
      const detail = await getIdentifyRule(data.id);
      Object.assign(model, detail);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <ModalComponent :title="getTitle">
    <div
      class="identify-rule-form governance-modal-form governance-form-item-horizontal"
    >
      <div class="form-item">
        <label class="form-item__label form-item__label--required">规则名称</label>
        <Input
          v-model:value="model.ruleName"
          allow-clear
          placeholder="如：手机号-字段名"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">匹配方式</label>
        <RadioGroup
          v-model:value="model.matchType"
          :options="MATCH_TYPE_OPTIONS"
          option-type="button"
        />
        <div class="form-item__tip">
          数据内容正则会对表数据采样后逐行匹配，命中率达到阈值才判定命中
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">匹配模式</label>
        <Input
          v-model:value="model.pattern"
          allow-clear
          :placeholder="patternPlaceholder"
        />
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            数据分类
          </label>
          <Select
            v-model:value="model.categoryId"
            :options="categoryOptions()"
            allow-clear
            show-search
            option-filter-prop="label"
            class="w-full"
            placeholder="命中后归属的数据分类"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">数据分级</label>
          <Select
            v-model:value="model.levelId"
            :options="levelOptions()"
            allow-clear
            class="w-full"
            placeholder="留空取分类上配置的分级"
          />
        </div>
      </div>
      <div v-if="model.matchType === 'DATA_REGEX'" class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">采样命中率阈值（%）</label>
          <InputNumber
            v-model:value="model.minHitRatio"
            :min="1"
            :max="100"
            class="w-full"
            placeholder="默认 80"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">采样行数</label>
          <InputNumber
            v-model:value="model.sampleRows"
            :min="1"
            :max="100"
            :precision="0"
            class="w-full"
            placeholder="默认 50，上限 100"
          />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">状态</label>
        <RadioGroup
          v-model:value="model.status"
          :options="[
            { label: '启用', value: 0 },
            { label: '停用', value: 1 },
          ]"
          option-type="button"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">描述</label>
        <TextArea
          v-model:value="model.description"
          :rows="2"
          placeholder="规则用途说明（选填）"
        />
      </div>
    </div>
  </ModalComponent>
</template>

<style lang="scss" scoped>
@import '../../../common/custom-modal-form.css';
</style>
