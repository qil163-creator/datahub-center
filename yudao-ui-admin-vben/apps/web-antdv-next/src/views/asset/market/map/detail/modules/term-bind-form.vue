<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetTermApi } from '#/api/asset/term';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import { Form, FormItem, message, Select } from 'antdv-next';

import { bindAssetTermColumn, getSimpleAssetTermList } from '#/api/asset/term';
import { useObjectRequest } from '#/hooks/use-object-request';

defineOptions({ name: 'AssetMarketMapTermBindForm' });

const emit = defineEmits<{ (e: 'success'): void }>();
const access = useAccessStore();
const identity = ref('closed');
const assetId = ref<DataAssetApi.Id>();
const column = ref<DataAssetApi.MetadataColumn>();
const termId = ref<string>();
const terms = ref<AssetTermApi.Term[]>([]);
const selectedTerm = ref<AssetTermApi.Term>();
const loading = ref(false);
const loadFailed = ref(false);
const saving = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;

function reset() {
  clearTimeout(timer);
  assetId.value = undefined;
  column.value = undefined;
  termId.value = undefined;
  terms.value = [];
  selectedTerm.value = undefined;
  loading.value = false;
  loadFailed.value = false;
  saving.value = false;
}
const optionsRequest = useObjectRequest(() => identity.value, reset);
const saveRequest = useObjectRequest(() => identity.value);

const selectionUnavailable = computed(
  () =>
    selectedTerm.value &&
    !terms.value.some((term) => String(term.id) === termId.value),
);
const termOptions = computed(() => {
  const choices = terms.value.map((term) => ({
    label: term.status === 1 ? `${term.name}（已停用）` : term.name,
    value: String(term.id),
    disabled: term.status === 1,
  }));
  if (selectionUnavailable.value && selectedTerm.value) {
    choices.unshift({
      label: selectedTerm.value.name,
      value: String(selectedTerm.value.id),
      disabled: true,
    });
  }
  return choices;
});

async function loadTerms(keyword?: string) {
  const current = optionsRequest.begin();
  loading.value = true;
  loadFailed.value = false;
  try {
    const result = await getSimpleAssetTermList(keyword);
    if (!current()) return;
    terms.value = result;
    const selected = result.find((term) => String(term.id) === termId.value);
    if (selected) selectedTerm.value = selected;
  } catch {
    if (current()) {
      terms.value = [];
      loadFailed.value = true;
    }
  } finally {
    if (current()) loading.value = false;
  }
}

function onSearch(keyword: string) {
  clearTimeout(timer);
  optionsRequest.begin();
  loading.value = true;
  timer = setTimeout(() => loadTerms(keyword), 300);
}

function onTermChange(value?: string) {
  termId.value = value;
  selectedTerm.value = terms.value.find((term) => String(term.id) === value);
}

const [Modal, modalApi] = useVbenModal({
  class: 'w-[600px]',
  async onConfirm() {
    if (!column.value?.id || assetId.value === undefined || saving.value)
      return;
    const previous = column.value.termId;
    const previousId =
      previous === undefined || previous === null
        ? undefined
        : String(previous);
    if (termId.value === previousId) {
      await modalApi.close();
      return;
    }
    const current = saveRequest.begin();
    const data = {
      assetId: assetId.value,
      metaColumnId: column.value.id,
      termId: termId.value,
    };
    saving.value = true;
    modalApi.lock();
    try {
      await bindAssetTermColumn(data);
      if (!current()) return;
      message.success(data.termId ? '术语绑定成功' : '已解除术语绑定');
      emit('success');
      await modalApi.close();
    } finally {
      if (current()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
  async onOpenChange(isOpen: boolean) {
    identity.value = 'closed';
    optionsRequest.begin();
    saveRequest.begin();
    reset();
    modalApi.unlock();
    if (!isOpen) return;
    const data = modalApi.getData() as {
      assetId: DataAssetApi.Id;
      column: DataAssetApi.MetadataColumn;
    };
    identity.value = `${data.assetId}/${data.column.id}`;
    assetId.value = data.assetId;
    column.value = data.column;
    if (data.column.termId !== undefined && data.column.termId !== null) {
      termId.value = String(data.column.termId);
      selectedTerm.value = {
        id: data.column.termId,
        name: data.column.termName || `术语 #${data.column.termId}`,
      };
    }
    await loadTerms();
  },
});

watch(
  () => [access.accessToken, access.refreshToken],
  () => modalApi.close(),
);
onBeforeUnmount(() => clearTimeout(timer));
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    title="绑定术语"
    class="!top-[60px] !w-[600px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
  >
    <Form
      :label-col="{ style: { width: '70px' } }"
      :wrapper-col="{ style: { flex: '1 1 0' } }"
      class="mx-2"
    >
      <FormItem label="术语">
        <Select
          :value="termId"
          :filter-option="false"
          :loading="loading"
          :options="termOptions"
          allow-clear
          class="w-full"
          placeholder="请选择数据治理中的业务术语"
          show-search
          @change="onTermChange"
          @search="onSearch"
        />
        <div class="text-muted-foreground mt-1 text-xs">
          术语由「数据治理 →
          业务术语」统一维护。为当前字段选择业务定义；清空后确定即解除绑定。
        </div>
        <template v-if="loadFailed"></template>
        <div
          v-else-if="selectionUnavailable && !loading"
          class="text-muted-foreground mt-1 text-xs"
        >
          当前绑定术语不在候选列表中，可能已停用、删除或不匹配搜索条件；可更换或清空绑定。
        </div>
      </FormItem>
      <FormItem v-if="selectedTerm?.description" label="业务定义">
        <div class="rounded bg-[hsl(var(--accent))] px-3 py-2 text-sm">
          {{ selectedTerm.description }}
        </div>
      </FormItem>
    </Form>
  </Modal>
</template>
