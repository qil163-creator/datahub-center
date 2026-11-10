<script setup lang="ts">
import type { FormInstance } from 'antdv-next';

import type { GovernanceStandardCodeItemApi as Api } from '#/api/governance/standardcodeitem';

import { computed, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Form as AntForm,
  Collapse,
  FormItem,
  Input,
  InputNumber,
  message,
  RadioGroup,
} from 'antdv-next';

import {
  createStandardCodeItem,
  getStandardCodeItem,
  updateStandardCodeItem,
} from '#/api/governance/standardcodeitem';
import { useObjectRequest } from '#/hooks/use-object-request';

interface Context {
  codeSetId: Api.Id;
  dictType?: string;
  id?: Api.Id;
  readonly?: boolean;
}
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const context = ref<Context>();
const ready = ref(false);
const loadError = ref('');
const identity = ref('closed');
const formRef = ref<FormInstance>();
const defaults = () => ({
  codeSetId: undefined,
  dictType: '',
  label: '',
  value: '',
  sort: 0,
  status: 0,
  remark: undefined,
  id: undefined,
});
const formData = reactive<Api.StandardCodeItem>(defaults());
const readonly = computed(() => Boolean(context.value?.readonly));
const title = computed(() => {
  if (readonly.value) return '数据元代码详情';
  return context.value?.id ? '修改数据元代码' : '新增数据元代码';
});
const request = useObjectRequest(
  () => `${identity.value}/${access.accessCodes.join(',')}`,
  () => {
    ready.value = false;
    Object.assign(formData, defaults());
  },
);
const [Modal, modalApi] = useVbenModal({
  async onOpenChange(open) {
    identity.value = open
      ? `${(modalApi.getData() as Context)?.codeSetId}/${(modalApi.getData() as Context)?.id ?? 'new'}`
      : 'closed';
    const current = request.begin();
    ready.value = false;
    loadError.value = '';
    Object.assign(formData, defaults());
    modalApi.setState({ confirmDisabled: true });
    if (!open) {
      context.value = undefined;
      modalApi.unlock();
      return;
    }
    context.value = modalApi.getData() as Context;
    modalApi.setState({
      showConfirmButton: !readonly.value,
      cancelText: readonly.value ? '关闭' : '取消',
    });
    const target = context.value;
    modalApi.lock();
    try {
      const item = target.id
        ? await getStandardCodeItem(target.id)
        : {
            codeSetId: target.codeSetId,
            dictType: target.dictType ?? '',
          };
      if (!current()) return;
      if (
        item.codeSetId !== undefined &&
        String(item.codeSetId) !== String(target.codeSetId)
      )
        throw new Error('码值所属代码表不匹配');
      Object.assign(formData, item);
      if (current()) {
        ready.value = true;
        modalApi.setState({ confirmDisabled: false });
      }
    } catch (error) {
      if (current())
        loadError.value =
          error instanceof Error ? error.message : '加载失败，请关闭后重试';
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onConfirm() {
    if (!ready.value || readonly.value || !context.value) return;
    const current = request.begin();
    await formRef.value?.validate();
    if (!current()) return;
    modalApi.lock();
    try {
      const payload = {
        id: formData.id,
        label: formData.label,
        value: formData.value,
        sort: formData.sort,
        status: formData.status,
        remark: formData.remark,
        revision: formData.revision,
        codeSetId: context.value.codeSetId,
        dictType: context.value.dictType ?? formData.dictType,
      };
      await (context.value.id
        ? updateStandardCodeItem(payload)
        : createStandardCodeItem(payload));
      if (!current()) return;
      emit('success');
      message.success('保存成功');
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes.join(',')],
  () => modalApi.close(),
);
</script>
<template>
  <Modal
    :title="title"
    :fullscreen-button="false"
    class="standard-modal standard-code-modal qdata-standard"
    header-class="standard-modal-header"
    content-class="standard-modal-content"
    footer-class="standard-modal-footer"
  >
    <Alert v-if="loadError && (!/(?:加载|读取|获取).*失败/.test(loadError) || /保存|冲突|未确认/.test(loadError))" :message="loadError" type="error" class="mb-4" />
    <AntForm
      ref="formRef"
      :model="formData"
      :disabled="readonly || !ready"
      :label-col="{ style: { width: '110px' } }"
    >
      <FormItem
        label="代码值"
        name="value"
        :rules="[
          { required: true, whitespace: true, message: '代码值不能为空' },
        ]"
      >
        <Input v-model:value="formData.value" placeholder="请输入代码值" />
      </FormItem>
      <FormItem
        label="代码名称"
        name="label"
        :rules="[
          { required: true, whitespace: true, message: '代码名称不能为空' },
        ]"
      >
        <Input v-model:value="formData.label" placeholder="请输入代码名称" />
      </FormItem>
      <Collapse
        v-if="!readonly"
        :items="[{ key: 'advanced', label: '其他信息' }]"
      >
        <template #contentRender>
          <FormItem label="排序">
            <InputNumber v-model:value="formData.sort" :min="0" />
          </FormItem>
          <FormItem label="状态">
            <RadioGroup
              v-model:value="formData.status"
              :options="[
                { label: '启用', value: 0 },
                { label: '停用', value: 1 },
              ]"
            />
          </FormItem>
          <FormItem label="备注">
            <Input.TextArea v-model:value="formData.remark" :rows="3" />
          </FormItem>
        </template>
      </Collapse>
      <template v-if="readonly">
        <FormItem label="创建人">
          <Input :value="formData.creatorName || '-'" />
</FormItem><FormItem label="创建时间">
          <Input
            :value="
              formData.createTime
                ? String(formatDateTime(formData.createTime))
                : '-'
            "
          />
        </FormItem>
      </template>
    </AntForm>
  </Modal>
</template>
<style scoped>
.standard-code-modal {
  width: 800px !important;
}
</style>
