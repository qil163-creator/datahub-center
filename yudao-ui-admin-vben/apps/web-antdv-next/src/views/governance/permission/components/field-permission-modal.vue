<script lang="ts" setup>
import type { SafetyLevelApi } from '#/api/governance/level';
import type { SafetyPermissionApi } from '#/api/governance/permission';

import { computed, reactive, watch } from 'vue';

import { CommonStatusEnum } from '@vben/constants';

import { Form, FormItem, Modal, Radio, RadioGroup, Select } from 'antdv-next';

import { FIELD_PERMISSION_OPTIONS } from '../data';

defineOptions({ name: 'SafetyPermissionFieldModal' });

const props = defineProps<{
  fieldData?: SafetyPermissionApi.FieldPermission;
  maskingRules: { id: number; methodCode?: string; ruleName?: string }[];
  open: boolean;
  securityLevels: SafetyLevelApi.Level[];
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'confirm', config: SafetyPermissionApi.FieldPermission): void;
}>();

const form = reactive({
  permissionType: 1,
  securityLevelId: undefined as number | undefined,
  maskingRuleId: undefined as number | undefined,
});

const enabledLevels = computed(() =>
  props.securityLevels.filter(
    (item) => item.status === CommonStatusEnum.ENABLE,
  ),
);

watch(
  () => [props.open, props.fieldData],
  () => {
    if (!props.open) {
      return;
    }
    form.permissionType = props.fieldData?.permissionType ?? 1;
    form.securityLevelId = props.fieldData?.securityLevelId;
    form.maskingRuleId = props.fieldData?.maskingRuleId ?? undefined;
  },
);

function handleOk() {
  emit('confirm', {
    fieldName: props.fieldData?.fieldName ?? '',
    fieldType: props.fieldData?.fieldType,
    fieldComment: props.fieldData?.fieldComment,
    permissionType: form.permissionType,
    securityLevelId: form.securityLevelId,
    maskingRuleId: form.permissionType === 2 ? form.maskingRuleId : undefined,
  });
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :open="open"
    :title="`编辑字段权限：${fieldData?.fieldName || ''}`"
    @cancel="emit('update:open', false)"
    @ok="handleOk"
  >
    <Form layout="vertical">
      <FormItem label="安全等级">
        <Select
          v-model:value="form.securityLevelId"
          allow-clear
          class="w-full"
          placeholder="选择安全等级"
          :options="
            enabledLevels.map((item) => ({
              label: item.safetyName,
              value: item.id,
            }))
          "
        />
      </FormItem>
      <FormItem label="权限类型">
        <RadioGroup v-model:value="form.permissionType">
          <Radio
            v-for="item in FIELD_PERMISSION_OPTIONS"
            :key="item.value"
            :value="item.value"
          >
            {{ item.label }}
          </Radio>
        </RadioGroup>
      </FormItem>
      <FormItem label="脱敏规则">
        <Select
          v-model:value="form.maskingRuleId"
          allow-clear
          class="w-full"
          :disabled="form.permissionType !== 2"
          placeholder="选择脱敏规则"
          :options="
            maskingRules.map((item) => ({
              label: item.ruleName,
              value: item.id,
            }))
          "
        />
      </FormItem>
    </Form>
  </Modal>
</template>
