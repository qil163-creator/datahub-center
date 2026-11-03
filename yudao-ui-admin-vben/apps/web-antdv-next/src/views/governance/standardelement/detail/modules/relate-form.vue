<script lang="ts" setup>
import type { VNode } from 'vue';

import type { GovernanceStandardCodeSetApi as CodeSetApi } from '#/api/governance/standardcodeset';

import { computed, h, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, Button, message, Pagination } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  getStandardCodeSet,
  getStandardCodeSetPage,
} from '#/api/governance/standardcodeset';
import { bindStandardElementCodeSet } from '#/api/governance/standardelement';
import { useObjectRequest } from '#/hooks/use-object-request';
import { $t } from '#/locales';

const emit = defineEmits(['success']);
const elementId = ref<CodeSetApi.Id>();
const opened = ref(false);
const page = ref(1);
const pageSize = 20;
const keyword = ref('');
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const boundFailed = ref(false);
const rows = ref<CodeSetApi.StandardCodeSet[]>([]);
const selected = ref<CodeSetApi.StandardCodeSet>();
const options = computed(() => {
  const values = new Map(rows.value.map((row) => [String(row.id), row]));
  if (selected.value) values.set(String(selected.value.id), selected.value);
  return [...values.values()].map((row) => ({
    label: row.name || String(row.id),
    value: row.id,
  }));
});
const optionRequests = useObjectRequest(
  () => elementId.value,
  () => {
    rows.value = [];
    total.value = 0;
    loading.value = false;
  },
);
const boundRequests = useObjectRequest(
  () => elementId.value,
  () => {
    selected.value = undefined;
  },
);
const requests = useObjectRequest(
  () => elementId.value,
  () => modalApi.unlock(),
);

async function loadOptions() {
  if (!opened.value) return;
  const current = optionRequests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getStandardCodeSetPage({
      pageNo: page.value,
      pageSize,
      name: keyword.value.trim() || undefined,
    });
    if (!current()) return;
    rows.value = result.list;
    total.value = result.total;
  } catch {
    if (current()) {
      rows.value = [];
      total.value = 0;
      failed.value = true;
    }
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadBound(id: CodeSetApi.Id) {
  if (!opened.value) return;
  const current = boundRequests.begin();
  try {
    const row = await getStandardCodeSet(id);
    if (current() && String(row.id) === String(id)) selected.value = row;
  } catch {
    if (current()) boundFailed.value = true;
  }
}
function search(value: string) {
  keyword.value = value;
  page.value = 1;
  void loadOptions();
}
function select(value: CodeSetApi.Id) {
  boundRequests.begin();
  selected.value =
    rows.value.find((row) => String(row.id) === String(value)) ??
    selected.value;
  boundFailed.value = false;
}
function popupRender(menu: VNode) {
  return h('div', [
    menu,
    h(
      'div',
      {
        class: 'px-2 py-2',
        onMousedown: (event: MouseEvent) => event.preventDefault(),
      },
      [
        h(Pagination, {
          current: page.value,
          pageSize,
          total: total.value,
          size: 'small',
          showSizeChanger: false,
          onChange: (value: number) => {
            page.value = value;
            void loadOptions();
          },
        }),
      ],
    ),
  ]);
}

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    formItemClass: 'col-span-2',
    labelWidth: 130,
  },
  layout: 'horizontal',
  schema: [
    {
      fieldName: 'codeSetId',
      label: '标准码表',
      rules: 'required',
      component: 'Select',
      componentProps: () => ({
        allowClear: false,
        options: options.value,
        loading: loading.value,
        filterOption: false,
        onSearch: search,
        onChange: select,
        popupRender,
        placeholder: '请选择标准码表',
        showSearch: true,
      }),
    },
  ],
  showDefaultActions: false,
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const current = requests.begin();
    const { valid } = await formApi.validate();
    if (!current() || !valid || !elementId.value) {
      return;
    }
    modalApi.lock();
    const values = await formApi.getValues();
    if (!current()) return;
    try {
      // 使用专用绑定接口，避免全量更新覆盖他人并发修改
      await bindStandardElementCodeSet(elementId.value, values.codeSetId);
      if (!current()) return;
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    opened.value = isOpen;
    optionRequests.begin();
    boundRequests.begin();
    rows.value = [];
    selected.value = undefined;
    keyword.value = '';
    page.value = 1;
    total.value = 0;
    loading.value = false;
    failed.value = false;
    boundFailed.value = false;
    if (!isOpen) {
      requests.begin();
      elementId.value = undefined;
      await formApi.resetForm();
      return;
    }
    const data = modalApi.getData() as {
      codeSetId?: number | string;
      id: CodeSetApi.Id;
    };
    elementId.value = data?.id;
    const current = requests.begin();
    await formApi.setValues({ codeSetId: data?.codeSetId });
    if (!current() || !opened.value) return;
    await Promise.all([
      loadOptions(),
      data?.codeSetId === undefined
        ? Promise.resolve()
        : loadBound(data.codeSetId),
    ]);
  },
});
</script>

<template>
  <Modal
    class="standard-modal qdata-standard"
    title="关联标准码表"
    :fullscreen-button="false"
    header-class="standard-modal-header"
    content-class="standard-modal-content"
    footer-class="standard-modal-footer"
  >
    <Alert
      v-if="failed"
      type="error"
      message="标准码表加载失败，请重试"
      class="mb-3"
    >
      <template #action><Button @click="loadOptions">重试</Button></template>
    </Alert>
    <Alert
      v-if="boundFailed"
      type="warning"
      message="当前关联码表读取失败，原关联未清除；可重新选择码表。"
      class="mb-3"
    />
    <Form class="mx-4" />
  </Modal>
</template>
