<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { MarketApi } from '#/api/asset/market';
import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  reactive,
  ref,
  useTemplateRef,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  Button,
  CheckboxGroup,
  Form,
  FormItem,
  Input,
  message,
  Select,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { ASSET_TYPE_OPTIONS } from '#/api/asset/dataasset';
import { getMarketMartListing, publishMarketMart } from '#/api/asset/market';
import { getMarketCategorySimpleList } from '#/api/asset/marketcategory';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';

import { DELIVERY_TYPE_OPTIONS } from '../data';
import { TABLE_KIND_META } from '../map/data';

defineOptions({ name: 'AssetMarketPublishForm' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canPublish = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:data-asset:publish']),
);
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const guard = createLatestWins();
const form = useTemplateRef<{
  clearValidate: () => void;
  validate: () => Promise<unknown>;
}>('form');
const asset = ref<DataAssetApi.DataAsset>();
const publishIcons = {
  tableType: '/assets/qdata-market/publish-table-type.svg',
  doris: '/assets/qdata-market/publish-doris.svg',
};
const model = reactive<MarketApi.MartListing>({ assetId: '' });
const categories = ref<AssetMarketCategoryApi.MarketCategory[]>([]);
const categoryTree = computed(() =>
  handleTree(
    categories.value.map((category) => ({
      ...category,
      id: String(category.id),
      parentId: String(category.parentId ?? 0),
    })),
  ),
);
const assetTypeLabel = computed(
  () =>
    ASSET_TYPE_OPTIONS.find((option) => option.value === asset.value?.assetType)
      ?.label ?? '--',
);
const tableTypeLabel = computed(
  () =>
    TABLE_KIND_META[(asset.value?.layer ?? '').toUpperCase()]?.label ??
    asset.value?.tableType ??
    '-',
);
const englishName = computed(() => {
  const name = asset.value?.tableName || '-';
  const match = /^(ods_|dim_|dwd_|dws_|dwm_|ads_|app_)(.+)$/i.exec(name);
  let letterCase = '混合';
  if (name === name.toLowerCase()) letterCase = '小写';
  else if (name === name.toUpperCase()) letterCase = '大写';
  return {
    prefix: match?.[1],
    value: match?.[2] ?? name,
    letterCase,
  };
});
const loading = ref(false);
const saving = ref(false);
const loadError = ref('');
let opened = false;
const frequencies = [
  '实时(T+0)',
  '每小时(T+H)',
  '每天(T+1)',
  '每周 (Weekly)',
  '每月(Monthly)',
  '静态不更新',
];
const frequencyOptions = computed(() => {
  const values = [...frequencies];
  if (model.frequency && !values.includes(model.frequency))
    values.push(model.frequency);
  return values.map((value) => ({ label: value, value }));
});
const rules = {
  marketCategoryId: [{ required: true, message: '请选择挂载集市类目' }],
  name: [
    { required: true, whitespace: true, message: '请输入集市展示名称' },
    { max: 256, message: '集市展示名称最多256个字符' },
  ],
  frequency: [
    { required: true, message: '请选择更新频率' },
    { max: 64, message: '更新频率最多64个字符' },
  ],
  deliveryTypes: [
    {
      required: true,
      type: 'array' as const,
      min: 1,
      message: '请至少选择一种交付状态',
    },
  ],
  description: [{ max: 256, message: '描述最多256个字符' }],
};
const [Modal, modalApi] = useVbenModal({
  title: '发布至集市',
  confirmText: '确定',
  fullscreenButton: false,
  closeOnClickModal: false,
  onOpenChange(visible: boolean) {
    opened = visible;
    if (!visible) {
      invalidate();
      return;
    }
    asset.value = (
      modalApi.getData() as undefined | { asset?: DataAssetApi.DataAsset }
    )?.asset;
    void load();
  },
  async onConfirm() {
    if (
      !opened ||
      !canPublish.value ||
      loading.value ||
      saving.value ||
      loadError.value
    )
      return;
    const ticket = guard.next();
    try {
      await form.value?.validate();
    } catch {
      return;
    }
    if (
      !opened ||
      !ticket.isCurrent() ||
      !form.value ||
      model.expectedRevision === undefined ||
      model.expectedRevision === null ||
      model.revision === undefined ||
      model.revision === null ||
      !parseResourceId(model.assetId) ||
      !categories.value.some((category) =>
        sameResourceId(category.id, model.marketCategoryId),
      )
    )
      return;
    saving.value = true;
    modalApi.lock();
    try {
      await publishMarketMart({
        assetId: model.assetId,
        marketCategoryId: model.marketCategoryId!,
        name: model.name!.trim(),
        description: model.description?.trim(),
        frequency: model.frequency!,
        deliveryTypes: [...model.deliveryTypes!],
        expectedRevision: model.expectedRevision,
        expectedListingRevision: model.revision,
      });
      if (!ticket.isCurrent() || !opened) return;
      message.success('已发布审核版本至集市');
      emit('success');
      await modalApi.close();
    } finally {
      if (ticket.isCurrent()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
});
function invalidate() {
  guard.invalidate();
  loading.value = false;
  saving.value = false;
  loadError.value = '';
  categories.value = [];
  for (const key of Object.keys(model)) Reflect.deleteProperty(model, key);
  model.assetId = '';
  asset.value = undefined;
  modalApi.unlock();
}
async function load() {
  const id = parseResourceId(asset.value?.id);
  if (!opened || !canPublish.value || !id) {
    loadError.value = '资产编号或发布权限无效，请关闭后重试。';
    return;
  }
  const ticket = guard.next();
  loading.value = true;
  loadError.value = '';
  try {
    const [listing, values] = await Promise.all([
      getMarketMartListing(id),
      getMarketCategorySimpleList(),
    ]);
    if (!ticket.isCurrent()) return;
    if (
      !sameResourceId(listing.assetId, id) ||
      listing.expectedRevision === undefined ||
      listing.expectedRevision === null ||
      listing.revision === undefined ||
      listing.revision === null
    )
      throw new Error('LISTING_IDENTITY');
    Object.assign(model, listing, {
      marketCategoryId:
        listing.marketCategoryId === undefined ||
        listing.marketCategoryId === null
          ? undefined
          : String(listing.marketCategoryId),
    });
    categories.value = values;
    await nextTick();
    if (!ticket.isCurrent()) return;
    form.value?.clearValidate();
  } catch {
    if (ticket.isCurrent())
      loadError.value = '发布配置读取失败或资产修订已变化，请关闭后重新打开。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
watch(
  owner,
  () => {
    opened = false;
    invalidate();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  invalidate();
});
</script>

<template>
  <Modal
    class="mart-publish-modal !top-[60px] !w-[1200px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
    content-class="!p-0"
    :loading="loading"
    :confirm-disabled="loading || saving || !!loadError || !canPublish"
  >
    <Alert
      v-if="loadError"
      :message="loadError"
      type="error"
      show-icon
      class="mb-4"
    />
    <Form
      ref="form"
      :model="model"
      :rules="rules"
      :validate-trigger="[]"
      layout="horizontal"
      :colon="false"
      :label-col="{ style: { width: '110px' } }"
      :wrapper-col="{ style: { flex: '1 1 0', minWidth: '0' } }"
      class="mart-publish-form"
    >
      <div class="mart-publish-form__metadata">
        <FormItem label="资产类型" class="mart-publish-form__strong">
          <Input :value="assetTypeLabel" disabled />
        </FormItem>
        <FormItem label="表类型" class="mart-publish-form__strong">
          <Input :value="tableTypeLabel" disabled>
            <template #prefix>
              <img
                :src="publishIcons.tableType"
                alt=""
                aria-hidden="true"
                class="mart-publish-form__icon"
              />
            </template>
          </Input>
        </FormItem>
        <FormItem label="资产中文名称">
          <Input :value="asset?.assetName || '-'" disabled />
        </FormItem>
        <FormItem label="资产英文名称">
          <div class="mart-publish-form__english-name">
            <span v-if="englishName.prefix" class="mart-publish-form__prefix">{{
              englishName.prefix
            }}</span>
            <Input :value="englishName.value" disabled />
            <Button disabled class="mart-publish-form__case">
              {{ englishName.letterCase }}
            </Button>
          </div>
        </FormItem>
        <FormItem label="数据连接名称">
          <Input :value="asset?.datasourceName || '-'" disabled>
            <template
              v-if="asset?.datasourceType?.toLowerCase() === 'doris'"
              #prefix
            >
              <img
                :src="publishIcons.doris"
                alt=""
                aria-hidden="true"
                class="mart-publish-form__icon"
              />
            </template>
          </Input>
        </FormItem>
        <FormItem label="数据连接类型">
          <Input :value="asset?.datasourceType || '-'" disabled />
        </FormItem>
      </div>
      <div class="mart-publish-form__fields">
        <FormItem label="挂载集市类目" name="marketCategoryId" required>
          <TreeSelect
            v-model:value="model.marketCategoryId"
            :tree-data="categoryTree"
            :field-names="{
              value: 'id',
              label: 'name',
              children: 'children',
            }"
            tree-default-expand-all
            show-search
            tree-node-filter-prop="name"
            placeholder="请选择挂载集市类目"
          />
        </FormItem>
        <FormItem label="集市展示名称" name="name" required>
          <Input
            v-model:value="model.name"
            :maxlength="256"
            placeholder="请输入集市展示名称"
          />
        </FormItem>
        <FormItem label="更新频率" name="frequency" required>
          <Select
            v-model:value="model.frequency"
            :options="frequencyOptions"
            placeholder="请选择更新频率"
          />
        </FormItem>
        <FormItem label="交付状态" name="deliveryTypes" required>
          <CheckboxGroup
            v-model:value="model.deliveryTypes"
            :options="DELIVERY_TYPE_OPTIONS"
          />
        </FormItem>
        <FormItem
          label="描述"
          name="description"
          class="mart-publish-form__description"
        >
          <TextArea
            v-model:value="model.description"
            :maxlength="256"
            :rows="4"
            :show-count="{ formatter: ({ count }) => `${count} / 256个字符` }"
            placeholder="请输入描述"
          />
        </FormItem>
      </div>
    </Form>
    <template #footer>
      <Button
        class="mart-publish-footer__button"
        :disabled="saving"
        @click="modalApi.close()"
      >
        取消
      </Button>
      <Button
        class="mart-publish-footer__button"
        type="primary"
        :loading="saving"
        :disabled="loading || saving || !!loadError || !canPublish"
        @click="modalApi.onConfirm()"
      >
        确定
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.mart-publish-form {
  padding: 20px 40px;
  background: hsl(var(--background));
}

.mart-publish-form :deep(.ant-form-item) {
  margin-bottom: 0;
}

.mart-publish-form :deep(.ant-checkbox-group) {
  display: flex;
  column-gap: 20px;
  align-items: center;
}

.mart-publish-form :deep(.ant-checkbox-inner) {
  width: 14px;
}

.mart-publish-form :deep(.ant-input-textarea-show-count::after) {
  position: absolute;
  right: 10px;
  bottom: 4px;
}

.mart-publish-form__metadata,
.mart-publish-form__fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px 20px;
}

.mart-publish-form__metadata {
  margin-bottom: 18px;
}

.mart-publish-form__english-name {
  display: flex;
  width: 100%;
}

.mart-publish-form__english-name :deep(.ant-input) {
  min-width: 0;
}

.mart-publish-form__prefix {
  display: inline-flex;
  align-items: center;
  padding: 0 20px;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
  background: hsl(var(--muted));
  border: 1px solid hsl(var(--border));
  border-right: 0;
}

.mart-publish-form__case.ant-btn[disabled] {
  flex-shrink: 0;
}

.mart-publish-form__icon {
  width: 14px;
  height: 14px;
  margin-right: 2px;
}

.mart-publish-form__description {
  grid-column: 1 / -1;
}

.mart-publish-form__description :deep(textarea) {
  min-height: 98px;
  resize: vertical;
}

.mart-publish-footer__button {
  width: 60px;
  height: 32px;
  padding: 0;
  border-color: hsl(var(--border));
  border-radius: var(--radius);
}

@media (width <= 900px) {
  .mart-publish-form {
    padding: 20px 16px;
  }

  .mart-publish-form :deep(.ant-form-item-row) {
    flex-direction: column;
  }

  .mart-publish-form :deep(.ant-form-item-label) {
    flex: none;
    width: 100% !important;
    overflow: visible;
    text-align: left;
  }

  .mart-publish-form :deep(.ant-form-item-control) {
    flex: none !important;
    width: 100%;
  }

  .mart-publish-form__metadata,
  .mart-publish-form__fields {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
