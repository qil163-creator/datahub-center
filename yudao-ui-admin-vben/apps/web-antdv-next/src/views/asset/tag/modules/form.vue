<script lang="ts" setup>
import type { AssetCategoryTagApi } from '#/api/asset/categorytag';
import type { AssetTagApi } from '#/api/asset/tag';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  message,
  RadioGroup,
  Spin,
  TreeSelect,
} from 'antdv-next';

import { getCategoryTagList } from '#/api/asset/categorytag';
import { createAssetTag, getAssetTag, updateAssetTag } from '#/api/asset/tag';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';

defineOptions({ name: 'AssetTagForm' });
const emit = defineEmits<{ success: [id: AssetTagApi.Tag['id']] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const editing = ref(false);
const form = reactive<AssetTagApi.Tag>({ status: 0 });
const allowed = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes([editing.value ? 'asset:tag:update' : 'asset:tag:create']),
);
const categories = ref<AssetCategoryTagApi.CategoryTag[]>([]);
const categoryTree = computed(() =>
  handleTree(
    categories.value.map((item) => ({
      ...item,
      id: resourceIdKey(item.id),
      parentId: resourceIdKey(item.parentId) || '0',
    })),
  ),
);
const loading = ref(false);
const saving = ref(false);
const errorText = ref('');
const optionsReady = ref(false);
const guard = createLatestWins();
let opened = false;
let alive = true;
function reset() {
  guard.invalidate();
  for (const key of Object.keys(form)) Reflect.deleteProperty(form, key);
  form.status = 0;
  categories.value = [];
  loading.value = false;
  saving.value = false;
  errorText.value = '';
  optionsReady.value = false;
}
const [Modal, modalApi] = useVbenModal({
  fullscreenButton: false,
  zIndex: 2100,
  async onOpenChange(value: boolean) {
    opened = value;
    reset();
    if (!value) return;
    const data = modalApi.getData() as AssetTagApi.Tag | undefined;
    editing.value = !!parseResourceId(data?.id);
    if (!allowed.value) return;
    const ticket = guard.next();
    loading.value = true;
    try {
      const [items, tag] = await Promise.all([
        getCategoryTagList(),
        editing.value ? getAssetTag(data!.id!) : Promise.resolve(data),
      ]);
      if (!ticket.isCurrent()) return;
      if (
        editing.value &&
        (resourceIdKey(tag?.id) !== resourceIdKey(data?.id) ||
          tag?.status !== 1 ||
          !Number.isInteger(tag.revision))
      )
        throw new Error('TAG_NOT_EDITABLE');
      categories.value = items;
      Object.assign(form, tag ?? {}, {
        status: tag?.status ?? 0,
        categoryId:
          tag?.categoryId === undefined
            ? undefined
            : resourceIdKey(tag.categoryId),
      });
      optionsReady.value = true;
    } catch {
      if (ticket.isCurrent())
        errorText.value =
          '标签或类目读取失败，或标签已启用。请关闭后刷新重试。';
    } finally {
      if (ticket.isCurrent()) loading.value = false;
    }
  },
});
async function save() {
  if (
    !alive ||
    !opened ||
    !allowed.value ||
    loading.value ||
    saving.value ||
    !optionsReady.value
  )
    return;
  const name = form.name?.trim() || '';
  if (!name || name.length > 50 || !parseResourceId(form.categoryId)) {
    message.warning('请填写标签名称（最多50字）并选择标签类目');
    return;
  }
  const ticket = guard.next();
  const subject = identity.value;
  saving.value = true;
  errorText.value = '';
  try {
    const payload: AssetTagApi.Tag = {
      id: editing.value ? form.id : undefined,
      revision: editing.value ? form.revision : undefined,
      name,
      categoryId: form.categoryId,
      nearSynonyms: form.nearSynonyms,
      synonyms: form.synonyms,
      description: form.description,
      status: editing.value ? undefined : form.status,
    };
    const result = editing.value
      ? await updateAssetTag(payload)
      : await createAssetTag(payload);
    if (!ticket.isCurrent()) return;
    const id = editing.value ? form.id : (result as AssetTagApi.Tag['id']);
    if (!parseResourceId(id)) throw new Error('TAG_IDENTITY');
    await modalApi.close();
    if (subject !== identity.value || !alive) return;
    emit('success', id);
    message.success(editing.value ? '修改成功' : '新增成功');
  } catch {
    if (ticket.isCurrent())
      errorText.value = '保存失败，请检查填写内容或刷新标签状态后重试。';
  } finally {
    if (ticket.isCurrent()) saving.value = false;
  }
}
watch(
  identity,
  () => {
    reset();
    if (opened) void modalApi.close();
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  alive = false;
  opened = false;
  reset();
});
</script>
<template>
  <Modal
    :centered="false"
    :title="editing ? '修改标签' : '新增标签'"
    class="tag-form-modal !top-[6vh] !w-[800px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
    content-class="!p-0"
  >
    <Alert v-if="errorText && optionsReady" :message="errorText" type="error" show-icon />
    <Spin :spinning="loading">
      <Form
        :model="form"
        :label-col="{ style: { width: '110px' } }"
        :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
        :colon="false"
        class="tag-form"
      >
        <FormItem label="标签类目" required>
          <TreeSelect
            v-model:value="form.categoryId"
            :tree-data="categoryTree"
            :field-names="{
              value: 'id',
              label: 'categoryName',
              children: 'children',
            }"
            tree-default-expand-all
            placeholder="请选择标签类目"
            :disabled="saving"
          />
        </FormItem>
        <FormItem label="标签名称" required>
          <Input
            v-model:value="form.name"
            :maxlength="50"
            placeholder="请输入标签名称"
            :disabled="saving"
          />
        </FormItem>
        <FormItem label="近义词">
          <Input
            v-model:value="form.nearSynonyms"
            :maxlength="256"
            placeholder="请输入近义词"
            :disabled="saving"
          />
        </FormItem>
        <FormItem label="同义词">
          <Input
            v-model:value="form.synonyms"
            :maxlength="256"
            placeholder="请输入同义词"
            :disabled="saving"
          />
        </FormItem>
        <FormItem label="状态">
          <RadioGroup
            v-model:value="form.status"
            :options="[
              { label: '禁用', value: 1 },
              { label: '启用', value: 0 },
            ]"
            :disabled="saving || editing"
          />
        </FormItem>
        <FormItem label="描述">
          <Input.TextArea
            v-model:value="form.description"
            :maxlength="256"
            :rows="4"
            :show-count="{ formatter: ({ count }) => `${count} / 256个字符` }"
            placeholder="请输入描述"
            :disabled="saving"
          />
        </FormItem>
      </Form>
    </Spin>
    <template #footer>
      <Button :disabled="saving" @click="modalApi.onCancel()">取消</Button><Button
        type="primary"
        :loading="saving"
        :disabled="loading || !allowed || !optionsReady"
        @click="save"
      >
        确定
      </Button>
    </template>
  </Modal>
</template>
<style scoped>
.tag-form {
  padding: 20px 40px 36px;
  font-size: 14px;
}

.tag-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.tag-form :deep(.ant-form-item:last-child) {
  margin-bottom: 0;
}

.tag-form :deep(.ant-select) {
  width: 100%;
}

.tag-form :deep(textarea) {
  height: 96px !important;
}

.tag-form :deep(.ant-input-data-count) {
  position: absolute;
  right: 10px;
  bottom: 4px;
  pointer-events: none;
}
</style>
