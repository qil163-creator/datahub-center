<script lang="ts" setup>
import type { QualityRuleSetApi } from '#/api/governance/quality/ruleset';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Form, FormItem, Input, message, Select, TextArea } from 'antdv-next';

import {
  createRuleSet,
  getRuleSet,
  updateRuleSet,
} from '#/api/governance/quality/ruleset';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();
const editId = ref<number>();

const formData = reactive<QualityRuleSetApi.RuleSet>({});

const rules = {
  setName: [{ required: true, message: '请输入规则集名称' }],
};

async function open(id?: number) {
  editId.value = id;
  clearReactiveObject(formData as Record<string, unknown>);
  formData.status = 0;
  modalApi.open();
  if (id) {
    Object.assign(formData, await getRuleSet(id));
  }
}

async function handleOk() {
  await formRef.value?.validate();
  modalApi.lock();
  try {
    await (editId.value
      ? updateRuleSet({ ...formData, id: editId.value })
      : createRuleSet(formData));
    message.success(editId.value ? '修改成功' : '新增成功');
    await modalApi.close();
    emit('saved');
  } finally {
    modalApi.unlock();
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await handleOk();
  },
});

defineExpose({ open });
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="editId ? '编辑规则集' : '新增规则集'"
  >
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="规则集名称" name="setName">
        <Input
          v-model:value="formData.setName"
          placeholder="请输入规则集名称"
        />
      </FormItem>
      <FormItem label="规则集编码" name="setCode">
        <Input
          v-model:value="formData.setCode"
          placeholder="请输入规则集编码（选填）"
        />
      </FormItem>
      <FormItem label="分类" name="category">
        <Input
          v-model:value="formData.category"
          placeholder="如：数据采集质量"
        />
      </FormItem>
      <FormItem label="评估维度" name="assessDim">
        <Input v-model:value="formData.assessDim" placeholder="如：按机构" />
      </FormItem>
      <FormItem label="评估周期" name="cycle">
        <Select
          v-model:value="formData.cycle"
          :options="[
            { label: '日', value: 'DAY' },
            { label: '周', value: 'WEEK' },
            { label: '月', value: 'MONTH' },
          ]"
          allow-clear
          placeholder="请选择评估周期"
        />
      </FormItem>
      <FormItem label="状态" name="status">
        <Select
          v-model:value="formData.status"
          :options="[
            { label: '启用', value: 0 },
            { label: '停用', value: 1 },
          ]"
        />
      </FormItem>
      <FormItem label="描述" name="description">
        <TextArea
          v-model:value="formData.description"
          :rows="3"
          placeholder="请输入描述"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
