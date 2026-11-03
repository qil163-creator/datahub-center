<script setup lang="ts">
import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { computed, ref, watch } from 'vue';

import {
  Button,
  Input,
  InputNumber,
  message,
  Modal,
  Radio,
  RadioGroup,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import {
  createIntegrationCategory,
  deleteIntegrationCategory,
  updateIntegrationCategory,
} from '#/api/dataforge/integration';
import { useObjectRequest } from '#/hooks/use-object-request';

import { categoryTree } from '../model';

const props = defineProps<{
  action: 'child' | 'delete' | 'edit' | 'sibling';
  categories: Api.Category[];
  category?: Api.Category;
  open: boolean;
}>();
const emit = defineEmits<{ saved: []; 'update:open': [value: boolean] }>();
const form = ref<Api.CategorySave>({
  parentId: '0',
  name: '',
  sort: 0,
  validFlag: true,
  description: '',
});
const busy = ref(false);
const error = ref('');
const request = useObjectRequest(
  () => `${props.open}/${props.action}/${props.category?.id ?? 'root'}`,
);
const title = computed(
  () =>
    ({
      child: '新增数据集成类目',
      sibling: '新增数据集成类目同级',
      edit: '编辑数据集成类目',
      delete: '删除数据集成类目',
    })[props.action],
);
const tree = computed(() => [
  {
    key: '0',
    value: '0',
    title: '数据集成类目',
    selectable: true,
    children: categoryTree(
      props.categories.filter(
        (item) =>
          props.action !== 'edit' ||
          String(item.id) !== String(props.category?.id),
      ),
    ),
  },
]);
watch(
  () => [props.open, props.action, props.category],
  () => {
    if (!props.open) return;
    form.value =
      props.action === 'edit' && props.category
        ? {
            id: props.category.id,
            parentId: String(props.category.parentId),
            name: props.category.name,
            sort: props.category.sort ?? 0,
            validFlag: props.category.validFlag ?? true,
            description: props.category.description ?? '',
          }
        : {
            parentId:
              props.action === 'sibling'
                ? String(props.category?.parentId ?? '0')
                : String(props.category?.id ?? '0'),
            name: '',
            sort: 0,
            validFlag: true,
            description: '',
          };
    busy.value = false;
    error.value = '';
  },
  { immediate: true },
);
async function save() {
  if (busy.value) return;
  error.value =
    props.action !== 'delete' && !/[\p{L}\p{N}]/u.test(form.value.name.trim())
      ? '请输入数据集成类目名称，名称至少包含一个汉字、字母或数字'
      : '';
  if (error.value) return;
  const current = request.begin();
  busy.value = true;
  try {
    if (props.action === 'delete' && props.category)
      await deleteIntegrationCategory(props.category.id);
    else
      await (form.value.id === undefined
        ? createIntegrationCategory(form.value)
        : updateIntegrationCategory(form.value));
    if (!current()) return;
    message.success('操作成功');
    emit('saved');
    emit('update:open', false);
  } finally {
    if (current()) busy.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open"
    :title="title"
    :width="action === 'delete' ? 460 : 'min(800px, calc(100vw - 32px))'"
    wrap-class-name="qi-modal qi-category-modal"
    :mask-closable="false"
    :closable="!busy"
    @cancel="!busy && $emit('update:open', false)"
  >
    <p v-if="action === 'delete'">是否确认删除“{{ category?.name }}”？</p>
    <form v-else class="qi-category-form" @submit.prevent="save">
      <div class="qi-form-field">
        <label><em>*</em>上级类目</label><TreeSelect
          v-model:value="form.parentId"
          :tree-data="tree"
          tree-default-expand-all
          aria-label="上级类目"
        />
      </div>
      <div class="qi-form-field">
        <label><em>*</em>数据集成类目名称</label>
        <div>
          <Input
            v-model:value="form.name"
            :maxlength="50"
            placeholder="请输入数据集成类目名称"
            aria-label="数据集成类目名称"
            :status="error ? 'error' : undefined"
          /><small v-if="error" class="qi-error">{{ error }}</small>
        </div>
      </div>
      <div class="qi-form-field">
        <label>状态</label><RadioGroup v-model:value="form.validFlag">
          <Radio :value="true">启用</Radio><Radio :value="false">禁用</Radio>
        </RadioGroup>
      </div>
      <div class="qi-form-field">
        <label>排序</label><InputNumber
          v-model:value="form.sort"
          :min="0"
          :precision="0"
          aria-label="排序"
        />
      </div>
      <div class="qi-form-field">
        <label>描述</label><TextArea
          v-model:value="form.description"
          :rows="4"
          :maxlength="256"
          show-count
          placeholder="请输入描述"
          aria-label="类目描述"
        />
      </div>
    </form>
    <template #footer>
      <Button :disabled="busy" @click="$emit('update:open', false)">
        取消
</Button><Button
        type="primary"
        :danger="action === 'delete'"
        :loading="busy"
        @click="save"
      >
        确定
      </Button>
    </template>
  </Modal>
</template>
