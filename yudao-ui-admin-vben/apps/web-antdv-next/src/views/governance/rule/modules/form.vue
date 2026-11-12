<script lang="ts" setup>
import type { SafetyRuleApi } from '#/api/governance/rule';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { createRule, getRule, updateRule } from '#/api/governance/rule';
import { $t } from '#/locales';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { REPLACE_ITEM_METHODS, useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<SafetyRuleApi.Rule>();
const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['脱敏规则'])
    : $t('ui.actionTitle.create', ['脱敏规则']);
});

const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema: useFormSchema(),
});

/** 按脱敏方式把表单字段组装成 config（后端存入版本表 config_json） */
function buildConfig(
  methodCode: string,
  values: Record<string, any>,
): Record<string, any> {
  switch (methodCode) {
    case 'CONTENT_REPLACE':
    case 'REPLACE': {
      return { matchMode: values.matchMode ?? 'EXACT' };
    }
    case 'ENCRYPT': {
      // 规则中不再保存任何对称密钥或私钥：字段加密走密钥引用，SM2 只需公钥。
      const config: Record<string, any> = { encryptType: values.encryptType };
      if (values.encryptType === 'KEY_REF') {
        config.keyCode = values.keyCode;
      } else if (values.encryptType === 'DIGEST') {
        config.algorithm = values.digestAlgorithm;
      } else if (values.encryptType === 'SM') {
        config.algorithm = values.smAlgorithm;
        if (values.smAlgorithm === 'SM2') {
          config.publicKey = values.publicKey;
        }
      }
      return config;
    }
    case 'HASH': {
      return { algorithm: values.hashAlgorithm };
    }
    case 'MASK': {
      return {
        maskStart: values.maskRange?.start,
        maskEnd: values.maskRange?.end,
        maskChar: values.maskChar || '*',
      };
    }
    case 'RANDOM_OFFSET': {
      return {
        maxOffsetDigits: values.maxOffsetDigits,
        allowNegative: !!values.allowNegative,
      };
    }
    case 'RANDOM_REPLACE': {
      return {
        replaceChar: values.replaceChar || '*',
        replaceCount: values.replaceCount ?? 1,
      };
    }
    case 'REDUCE_PRECISION': {
      return { precision: values.precision };
    }
    case 'REGEX': {
      return {
        pattern: values.pattern,
        replacement: values.replacement ?? '',
      };
    }
    case 'TRUNCATE': {
      return { truncateLength: values.truncateLength };
    }
    default: {
      // REVERSE / NULLIFY 等无参数方式
      return {};
    }
  }
}

/** 把后端返回的 config 拆回表单字段用于回显 */
function configToFormValues(
  methodCode: string | undefined,
  config: Record<string, any> | undefined,
): Record<string, any> {
  if (!methodCode || !config) {
    return {};
  }
  switch (methodCode) {
    case 'ENCRYPT': {
      // 历史规则中的内联密钥不回显：编辑时须改用密钥引用重新保存。
      const legacy =
        config.encryptType === 'AES' ||
        (config.encryptType === 'SM' && config.algorithm === 'SM4');
      const values: Record<string, any> = {
        encryptType: legacy ? 'KEY_REF' : config.encryptType,
      };
      if (config.encryptType === 'KEY_REF') {
        values.keyCode = config.keyCode;
      } else if (config.encryptType === 'DIGEST') {
        values.digestAlgorithm = config.algorithm;
      } else if (config.encryptType === 'SM' && !legacy) {
        values.smAlgorithm = config.algorithm;
        values.publicKey = config.publicKey;
      }
      return values;
    }
    case 'HASH': {
      return { hashAlgorithm: config.algorithm };
    }
    case 'MASK': {
      return {
        maskRange: { start: config.maskStart, end: config.maskEnd },
        maskChar: config.maskChar,
      };
    }
    default: {
      // 其余方式的 config 键与表单字段同名，直接平铺
      return { ...config };
    }
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    const values = await formApi.getValues();
    const methodCode = values.methodCode as string;
    // 掩码范围联合校验：两端必填且截止大于起始
    if (methodCode === 'MASK') {
      const { start, end } = values.maskRange ?? {};
      if (typeof start !== 'number' || typeof end !== 'number') {
        message.error('请输入掩码范围');
        return;
      }
      if (end <= start) {
        message.error('掩码截止数必须大于起始数');
        return;
      }
    }
    // 替换明细校验：至少一行且匹配值必填、不重复
    let replaceItems: SafetyRuleApi.ReplaceItem[] | undefined;
    if (REPLACE_ITEM_METHODS.includes(methodCode)) {
      replaceItems = (values.replaceItems ?? []) as SafetyRuleApi.ReplaceItem[];
      if (replaceItems.length === 0) {
        message.error('匹配内容至少填写一行');
        return;
      }
      if (replaceItems.some((item) => !item.matchValue)) {
        message.error('匹配值不能为空');
        return;
      }
      const matchValues = new Set(replaceItems.map((item) => item.matchValue));
      if (matchValues.size !== replaceItems.length) {
        message.error('匹配值不能重复');
        return;
      }
    }
    modalApi.lock();
    const data: any = {
      id: values.id,
      ruleName: values.ruleName,
      methodCode,
      sourceType: values.sourceType,
      status: values.status,
      sort: values.sort,
      description: values.description,
      config: buildConfig(methodCode, values),
      replaceItems,
    };
    try {
      await (formData.value?.id ? updateRule(data) : createRule(data));
      // 关闭并提示
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      await formApi.resetForm();
      return;
    }
    // 加载数据
    const data = modalApi.getData() as SafetyRuleApi.Rule;
    if (!data || !data.id) {
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getRule(data.id);
      // 回显：config 拆回各方式的表单字段，替换明细直接填入
      await formApi.setValues({
        ...formData.value,
        ...configToFormValues(formData.value.methodCode, formData.value.config),
        replaceItems: formData.value.replaceItems ?? [],
      });
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
