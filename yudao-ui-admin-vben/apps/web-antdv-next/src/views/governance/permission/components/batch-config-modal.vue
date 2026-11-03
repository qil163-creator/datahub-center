<script lang="ts" setup>
import type { SafetyLevelApi } from '#/api/governance/level';
import type { SafetyPermissionApi } from '#/api/governance/permission';

import { computed, reactive, watch } from 'vue';

import { CommonStatusEnum } from '@vben/constants';

import {
  Form,
  FormItem,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Tag,
} from 'antdv-next';

import { FIELD_PERMISSION_OPTIONS } from '../data';

defineOptions({ name: 'SafetyPermissionBatchModal' });

const props = defineProps<{
  maskingRules: { id: number; methodCode?: string; ruleName?: string }[];
  open: boolean;
  securityLevels: SafetyLevelApi.Level[];
  selectedFields: SafetyPermissionApi.FieldPermission[];
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'confirm', config: Partial<SafetyPermissionApi.FieldPermission>): void;
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
  () => props.open,
  (open) => {
    if (!open) {
      return;
    }
    form.permissionType = 1;
    form.securityLevelId = undefined;
    form.maskingRuleId = undefined;
  },
);

function handleOk() {
  emit('confirm', {
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
    title="批量权限配置"
    width="640px"
    @cancel="emit('update:open', false)"
    @ok="handleOk"
  >
    <div class="mb-4">
      <div class="mb-2 text-sm font-medium">
        选中字段（{{ selectedFields.length }} 个）
      </div>
      <div class="flex flex-wrap gap-1">
        <Tag v-for="field in selectedFields" :key="field.fieldName">
          {{ field.fieldName }}
        </Tag>
      </div>
    </div>
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
