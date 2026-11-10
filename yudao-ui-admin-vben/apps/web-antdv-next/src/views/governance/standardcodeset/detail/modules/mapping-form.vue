<script setup lang="ts">
import type { FormInstance } from 'antdv-next';

import type { GovernanceStandardCodeItemApi as ItemApi } from '#/api/governance/standardcodeitem';
import type {
  MappingSave,
  StandardCodeMapping,
} from '#/api/governance/standardcodeset/mapping';

import { computed, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Form as AntForm,
  FormItem,
  Input,
  message,
  Select,
} from 'antdv-next';

import { getStandardCodeItemPage } from '#/api/governance/standardcodeitem';
import {
  createCodeMapping,
  getCodeMapping,
  updateCodeMapping,
} from '#/api/governance/standardcodeset/mapping';
import { useObjectRequest } from '#/hooks/use-object-request';
type Id = ItemApi.Id;
interface Context {
  id?: Id;
  codeSetId: Id;
  readonly?: boolean;
}
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const formRef = ref<FormInstance>();
const context = ref<Context>();
const record = ref<StandardCodeMapping>();
const items = ref<ItemApi.StandardCodeItem[]>([]);
const ready = ref(false);
const loadError = ref('');
const identity = ref('closed');
const fields = reactive<{ codeItemId?: Id; originalValue: string }>({
  originalValue: '',
  codeItemId: undefined,
});
const readonly = computed(() => Boolean(context.value?.readonly));
const title = computed(() => {
  if (readonly.value) return '数据元代码映射详情';
  return context.value?.id ? '修改数据元代码映射' : '新增数据元代码映射';
});
const selected = computed(() =>
  items.value.find((item) => String(item.id) === String(fields.codeItemId)),
);
const codeValue = computed(
  () => selected.value?.value ?? record.value?.codeValue ?? '',
);
const request = useObjectRequest(
  () => `${identity.value}/${access.accessCodes.join(',')}`,
  () => {
    ready.value = false;
    items.value = [];
    record.value = undefined;
    fields.originalValue = '';
    fields.codeItemId = undefined;
  },
);
async function loadCodeItems(codeSetId: Id) {
  const result: ItemApi.StandardCodeItem[] = [];
  const pageSize = 100;
  for (let pageNo = 1; ; pageNo += 1) {
    const page = await getStandardCodeItemPage({ codeSetId, pageNo, pageSize });
    result.push(...page.list);
    if (page.list.length === 0 || result.length >= page.total) return result;
  }
}
const [Modal, modalApi] = useVbenModal({
  async onOpenChange(open) {
    identity.value = open
      ? `${(modalApi.getData() as Context)?.codeSetId}/${(modalApi.getData() as Context)?.id ?? 'new'}`
      : 'closed';
    const current = request.begin();
    ready.value = false;
    loadError.value = '';
    items.value = [];
    record.value = undefined;
    fields.originalValue = '';
    fields.codeItemId = undefined;
    modalApi.setState({ confirmDisabled: true });
    if (!open) {
      context.value = undefined;
      modalApi.unlock();
      return;
    }
    context.value = modalApi.getData() as Context;
    const target = context.value;
    modalApi.setState({
      showConfirmButton: !readonly.value,
      cancelText: readonly.value ? '关闭' : '取消',
    });
    modalApi.lock();
    try {
      const [detail, codeItems] = await Promise.all([
        target.id
          ? getCodeMapping(target.codeSetId, target.id)
          : Promise.resolve(undefined),
        readonly.value
          ? Promise.resolve([])
          : loadCodeItems(target.codeSetId),
      ]);
      if (!current()) return;
      if (detail && String(detail.codeSetId) !== String(target.codeSetId))
        throw new Error('代码映射所属代码表不匹配');
      record.value = detail;
      items.value = codeItems.filter((item) => item.id !== undefined);
      fields.originalValue = detail?.originalValue ?? '';
      fields.codeItemId = detail?.codeItemId;
      ready.value = true;
      modalApi.setState({ confirmDisabled: false });
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
    if (!current() || fields.codeItemId === undefined) return;
    const payload: MappingSave = {
      codeSetId: context.value.codeSetId,
      originalValue: fields.originalValue,
      codeItemId: fields.codeItemId,
      id: record.value?.id,
      revision: record.value?.revision,
    };
    modalApi.lock();
    try {
      await (record.value
        ? updateCodeMapping(payload)
        : createCodeMapping(payload));
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
    <Alert v-if="loadError && (!/(?:加载|读取|获取).*失败/.test(loadError) || /保存|冲突|未确认/.test(loadError))" type="error" :message="loadError" class="mb-4" />
    <AntForm
      ref="formRef"
      :model="fields"
      :disabled="readonly || !ready"
      :label-col="{ style: { width: '110px' } }"
    >
      <FormItem
        label="原始值"
        name="originalValue"
        :rules="[
          { required: true, whitespace: true, message: '原始值不能为空' },
        ]"
      >
        <Input
          v-model:value="fields.originalValue"
          placeholder="请输入原始值"
        />
      </FormItem>
      <FormItem
        label="代码名"
        name="codeItemId"
        :rules="[{ required: true, message: '代码名不能为空' }]"
      >
        <Input v-if="readonly" :value="record?.codeName || '-'" />
        <Select
          v-else
          v-model:value="fields.codeItemId"
          :options="
            items.map((item) => ({ label: item.label, value: item.id }))
          "
          placeholder="请选择代码名"
          show-search
          option-filter-prop="label"
        />
      </FormItem>
      <FormItem label="代码值">
        <Input :value="codeValue" disabled placeholder="代码值" />
      </FormItem>
      <template v-if="readonly">
        <FormItem label="创建人">
          <Input :value="record?.creatorName || '-'" />
</FormItem><FormItem label="创建时间">
          <Input
            :value="
              record?.createTime
                ? formatDate(record.createTime, 'YYYY-MM-DD HH:mm')
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
