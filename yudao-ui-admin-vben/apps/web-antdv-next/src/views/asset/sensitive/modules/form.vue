<script setup lang="ts">
import type { SensitiveListApi } from '#/api/asset/sensitive';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  message,
  Radio,
  RadioGroup,
  Select,
  TreeSelect,
} from 'antdv-next';

import {
  createSensitiveList,
  getSensitiveAssetOptions,
  getSensitiveColumnOptions,
  getSensitiveList,
  getSensitiveListCategories,
  updateSensitiveList,
} from '#/api/asset/sensitive';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import { categoryKey, categoryTree } from '../data';
import { useSensitiveAccess } from '../use-sensitive-access';

const emit = defineEmits<{ success: [] }>();
const { owner, can } = useSensitiveAccess();
const target = ref<SensitiveListApi.Record>();
const intent = ref<'create' | 'update'>('create');
const model = reactive<{
  assetId?: SensitiveListApi.Id;
  categoryKey?: string;
  columnId?: SensitiveListApi.Id;
  description: string;
  status: 0 | 1;
}>({ description: '', status: 1 });
const assets = ref<SensitiveListApi.AssetOption[]>([]);
const categories = ref<SensitiveListApi.Category[]>([]);
const columns = ref<SensitiveListApi.ColumnOption[]>([]);
const loading = ref(false);
const assetsLoading = ref(false);
const columnsLoading = ref(false);
const saving = ref(false);
const ready = ref(false);
const error = ref('');
const assetPage = ref(1);
const assetTotal = ref(0);
const assetKeyword = ref('');
const selectedAsset = ref<SensitiveListApi.AssetOption>();
const reads = createLatestWins();
const assetReads = createLatestWins();
const columnReads = createLatestWins();
const saves = createLatestWins();
let opened = false;
let searchTimer: ReturnType<typeof setTimeout> | undefined;
const isEdit = computed(() => intent.value === 'update');
const allowed = computed(() => can(intent.value));
const tree = computed(() => categoryTree(categories.value, true));
const assetOptions = computed(() => {
  const values = new Map(
    assets.value.map((asset) => [String(asset.id), asset]),
  );
  if (selectedAsset.value)
    values.set(String(selectedAsset.value.id), selectedAsset.value);
  return [...values.values()].map((asset) => ({
    value: String(asset.id),
    label: asset.assetName || asset.tableName || String(asset.id),
  }));
});
const columnOptions = computed(() =>
  columns.value.map((column) => ({
    value: String(column.id),
    label: `${column.columnName}${column.columnComment ? `（${column.columnComment}）` : ''}`,
  })),
);
function reset() {
  reads.invalidate();
  assetReads.invalidate();
  columnReads.invalidate();
  saves.invalidate();
  clearTimeout(searchTimer);
  target.value = undefined;
  intent.value = 'create';
  assets.value = [];
  categories.value = [];
  columns.value = [];
  selectedAsset.value = undefined;
  Object.assign(model, {
    assetId: undefined,
    columnId: undefined,
    categoryKey: undefined,
    description: '',
    status: 1,
  });
  loading.value = false;
  assetsLoading.value = false;
  columnsLoading.value = false;
  saving.value = false;
  ready.value = false;
  error.value = '';
  assetPage.value = 1;
  assetTotal.value = 0;
  assetKeyword.value = '';
  modalApi.unlock();
}
async function loadAssets(append = false) {
  if (!opened || !allowed.value) return;
  const ticket = assetReads.next();
  assetsLoading.value = true;
  try {
    const data = await getSensitiveAssetOptions({
      pageNo: assetPage.value,
      pageSize: 50,
      keyword: assetKeyword.value.trim() || undefined,
    });
    if (!ticket.isCurrent() || !opened) return;
    assets.value = append ? [...assets.value, ...data.list] : data.list;
    assetTotal.value = data.total;
  } catch {
    if (ticket.isCurrent() && opened) {
      if (append) assetPage.value = Math.max(1, assetPage.value - 1);
      error.value = '资产候选读取失败，可重新搜索。';
    }
  } finally {
    if (ticket.isCurrent()) assetsLoading.value = false;
  }
}
function searchAssets(keyword: string) {
  clearTimeout(searchTimer);
  assetReads.invalidate();
  assetKeyword.value = keyword;
  assetPage.value = 1;
  searchTimer = setTimeout(() => {
    void loadAssets();
  }, 250);
}
function loadMore(event: { target?: EventTarget | null }) {
  const element = event.target as HTMLElement | null;
  if (
    !element ||
    assetsLoading.value ||
    assets.value.length >= assetTotal.value ||
    element.scrollHeight - element.scrollTop - element.clientHeight > 20
  )
    return;
  assetPage.value += 1;
  void loadAssets(true);
}
async function loadColumns(assetId: SensitiveListApi.Id) {
  const ticket = columnReads.next();
  columns.value = [];
  columnsLoading.value = true;
  try {
    const result = await getSensitiveColumnOptions(assetId);
    if (ticket.isCurrent() && opened) columns.value = result;
  } catch {
    if (ticket.isCurrent() && opened)
      error.value = '字段读取失败，请重新选择资产。';
  } finally {
    if (ticket.isCurrent()) columnsLoading.value = false;
  }
}
function changeAsset(value: unknown) {
  columnReads.invalidate();
  columns.value = [];
  model.columnId = undefined;
  const id = parseResourceId(value);
  model.assetId = id;
  selectedAsset.value = assets.value.find((asset) => String(asset.id) === id);
  if (selectedAsset.value && !model.description)
    model.description = selectedAsset.value.description || '';
  if (id) void loadColumns(id);
}
const [Modal, modalApi] = useVbenModal({
  fullscreenButton: false,
  closeOnClickModal: false,
  async onOpenChange(value) {
    opened = value;
    reset();
    if (!value) return;
    const data = modalApi.getData() as { id?: SensitiveListApi.Id };
    intent.value = data?.id === undefined ? 'create' : 'update';
    if (!allowed.value) return;
    const ticket = reads.next();
    loading.value = true;
    try {
      const results = await Promise.allSettled([
        getSensitiveListCategories(),
        data?.id === undefined
          ? Promise.resolve(undefined)
          : getSensitiveList(data.id),
        loadAssets(),
      ]);
      if (!ticket.isCurrent() || !opened) return;
      const [categoryResult, detailResult, assetResult] = results;
      if (
        categoryResult.status !== 'fulfilled' ||
        detailResult.status !== 'fulfilled'
      ) {
        error.value = '表单读取失败，请关闭后重试。';
        return;
      }
      categories.value = categoryResult.value;
      if (assetResult.status === 'rejected')
        error.value = '资产候选读取失败，可重新搜索。';
      const detail = detailResult.value;
      if (detail) {
        if (detail.status === 0) {
          message.warning('请先禁用记录再修改');
          await modalApi.close();
          return;
        }
        target.value = detail;
        selectedAsset.value = {
          id: detail.assetId,
          assetName: detail.assetName,
          tableName: detail.tableName,
        };
        Object.assign(model, {
          assetId: String(detail.assetId),
          columnId: String(detail.columnId),
          categoryKey: categoryKey(detail.categoryId),
          description: detail.description || '',
          status: detail.status,
        });
        await loadColumns(detail.assetId);
        if (!ticket.isCurrent() || !opened) return;
      }
      ready.value = true;
    } catch {
      if (ticket.isCurrent()) error.value = '字段读取失败，请关闭后重新加载。';
    } finally {
      if (ticket.isCurrent()) loading.value = false;
    }
  },
  async onConfirm() {
    if (!opened || !ready.value || !allowed.value || saving.value) return;
    const assetId = parseResourceId(model.assetId);
    const column = columns.value.find(
      (item) => String(item.id) === String(model.columnId),
    );
    const category = categories.value.find(
      (item) => item.key === model.categoryKey,
    );
    if (!assetId || !column) {
      message.warning('请选择资产名称和字段');
      return;
    }
    if (
      !category?.categoryId ||
      category.type !== 'CATEGORY' ||
      !category.selectable ||
      category.status !== 0
    ) {
      message.warning('请选择有效的数据分类');
      return;
    }
    const payload: SensitiveListApi.SaveRequest = {
      id: target.value?.id,
      revision: target.value?.revision,
      assetId,
      columnId: column.id,
      categoryId: category.categoryId,
      description: model.description || undefined,
      status: model.status,
    };
    const ticket = saves.next();
    saving.value = true;
    modalApi.lock();
    try {
      await (payload.id === undefined
        ? createSensitiveList(payload)
        : updateSensitiveList(payload));
      if (!ticket.isCurrent() || !opened) return;
      message.success('操作成功');
      emit('success');
      await modalApi.close();
    } catch {
      if (ticket.isCurrent()) {
        ready.value = false;
        error.value = '保存失败或记录已变更，请关闭后重新打开核对。';
      }
    } finally {
      if (ticket.isCurrent()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
});
watch(
  owner,
  () => {
    opened = false;
    reset();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  reset();
});
</script>

<template>
  <Modal
    :centered="false"
    :title="isEdit ? '修改脱敏清单' : '新增脱敏清单'"
    confirm-text="确定"
    cancel-text="取消"
    :loading="loading"
    :confirm-disabled="!ready || !allowed || saving || columnsLoading"
    class="sensitive-form-modal !top-[60px] !w-[800px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
    content-class="!p-0"
  >
    <Alert v-if="error" :message="error" type="warning" class="mb-3" />
    <Form
      class="sensitive-form"
      :colon="false"
      :label-col="{ style: { width: '110px' } }"
      :wrapper-col="{ style: { flex: '1', minWidth: '0' } }"
    >
      <FormItem label="资产名称" required>
        <Select
          :value="model.assetId"
          :options="assetOptions"
          :filter-option="false"
          :loading="assetsLoading"
          placeholder="请输入资产名称"
          show-search
          allow-clear
          @change="changeAsset"
          @search="searchAssets"
          @popup-scroll="loadMore"
        />
      </FormItem>
      <FormItem label="字段名" required>
        <Select
          v-model:value="model.columnId"
          :options="columnOptions"
          :disabled="!model.assetId || columnsLoading"
          :loading="columnsLoading"
          placeholder="请选择字段"
          option-filter-prop="label"
          show-search
        />
      </FormItem>
      <FormItem label="数据分类" required>
        <TreeSelect
          v-model:value="model.categoryKey"
          :tree-data="tree"
          placeholder="请选择数据分类"
          tree-node-filter-prop="title"
          show-search
          allow-clear
        />
      </FormItem>
      <FormItem label="状态" required>
        <RadioGroup v-model:value="model.status">
          <Radio :value="1">禁用</Radio><Radio :value="0">启用</Radio>
        </RadioGroup>
      </FormItem>
      <FormItem label="描述">
        <Input.TextArea
          v-model:value="model.description"
          :maxlength="256"
          :rows="4"
          placeholder="请输入描述"
          show-count
        />
      </FormItem>
    </Form>
    <template #footer>
      <Button :disabled="saving" @click="modalApi.close()">取消</Button>
      <Button
        type="primary"
        :loading="saving"
        :disabled="!ready || !allowed || columnsLoading"
        @click="modalApi.onConfirm()"
      >
        确定
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.sensitive-form {
  padding: 20px 40px 36px;
}

.sensitive-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.sensitive-form :deep(.ant-form-item:last-child) {
  margin-bottom: 0;
}

.sensitive-form :deep(.ant-select),
.sensitive-form :deep(.ant-tree-select) {
  width: 100%;
}

@media (width <= 600px) {
  .sensitive-form {
    padding: 16px;
  }

  .sensitive-form :deep(.ant-form-item-row) {
    display: block;
  }

  .sensitive-form :deep(.ant-form-item-label) {
    width: 100% !important;
    text-align: left;
  }

  .sensitive-form :deep(.ant-form-item-control) {
    flex: none !important;
    width: 100%;
  }
}
</style>
