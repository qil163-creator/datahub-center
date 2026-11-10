<script setup lang="ts">
import type { FormInstance } from 'antdv-next';

import type {
  CategoryAdapter,
  CategoryRow,
  ResourceId,
} from './model';

import { computed, onDeactivated, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';

import {
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  Spin,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { sameResourceId } from '#/utils/resource-id';

import { parentOptions } from './model';
import ReferenceIcon from './reference-icon.vue';

const props = defineProps<{ adapter: CategoryAdapter }>();
const emit = defineEmits<{ saved: [] }>();
const { hasAccessByCodes } = useAccess();
const visible = ref(false);
const loading = ref(false);
const ready = ref(false);
const saving = ref(false);
const failed = ref(false);
const editId = ref<ResourceId>();
const original = ref<CategoryRow>();
const parents = ref<CategoryRow[]>([]);
const formRef = ref<FormInstance>();
const model = reactive({
  parentId: '0',
  name: '',
  status: 0,
  sort: 0 as null | number,
  description: '',
});
let requestVersion = 0;

const title = computed(() => `${editId.value === undefined ? '添加' : '修改'}${props.adapter.label}`);
const canWrite = computed(() =>
  hasAccessByCodes([
    `${props.adapter.permissionPrefix}:${editId.value === undefined ? 'create' : 'update'}`,
  ]),
);
const options = computed(() => {
  const excluded = new Set<string>();
  if (editId.value !== undefined) {
    const target = String(editId.value);
    excluded.add(target);
    let changed = true;
    while (changed) {
      changed = false;
      for (const row of parents.value) {
        if (excluded.has(String(row.parentId)) && !excluded.has(String(row.id))) {
          excluded.add(String(row.id));
          changed = true;
        }
      }
    }
  }
  return parentOptions(parents.value, excluded);
});
const rules = computed(() => ({
  parentId: [{ required: true, message: '请选择上级类目' }],
  name: [
    { required: true, whitespace: true, message: `请输入${props.adapter.nameLabel}` },
    { max: 50, message: `${props.adapter.nameLabel}不能超过50个字符` },
    { pattern: /[\p{Script=Han}a-z0-9]/iu, message: `${props.adapter.nameLabel}不能为纯符号` },
  ],
  sort: [{ type: 'integer' as const, min: 0, max: 100_000, message: '排序须为0至100000的整数' }],
  description: [{ max: 256, message: '描述不能超过256个字符' }],
}));

function reset() {
  requestVersion += 1;
  visible.value = false;
  loading.value = false;
  ready.value = false;
  saving.value = false;
  failed.value = false;
  editId.value = undefined;
  original.value = undefined;
  parents.value = [];
  Object.assign(model, { parentId: '0', name: '', status: 0, sort: 0, description: '' });
  formRef.value?.clearValidate();
}

async function open(data: { id?: ResourceId; parentId?: ResourceId } = {}) {
  reset();
  const editing = data.id !== undefined;
  if (!hasAccessByCodes([`${props.adapter.permissionPrefix}:${editing ? 'update' : 'create'}`])) return;
  editId.value = data.id;
  model.parentId = String(data.parentId ?? 0);
  visible.value = true;
  loading.value = true;
  const version = ++requestVersion;
  try {
    const rawRows = await props.adapter.list();
    const rows = rawRows.map(props.adapter.normalize);
    const rawDetail = editing ? await props.adapter.get(data.id!) : undefined;
    if (version !== requestVersion || !visible.value) return;
    parents.value = rows;
    if (rawDetail) {
      const detail = props.adapter.normalize(rawDetail);
      if (!sameResourceId(detail.id, data.id)) throw new Error('CATEGORY_IDENTITY');
      original.value = detail;
      Object.assign(model, {
        parentId: String(detail.parentId ?? 0),
        name: detail.name,
        status: detail.status,
        sort: detail.sort,
        description: detail.description ?? '',
      });
    }
    ready.value = true;
  } catch {
    if (version === requestVersion) failed.value = true;
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}

function cancel() {
  if (!saving.value) reset();
}

async function save() {
  if (!visible.value || !ready.value || saving.value || !canWrite.value) return;
  saving.value = true;
  const version = ++requestVersion;
  try {
    await formRef.value?.validate();
    if (version !== requestVersion || !visible.value) return;
    const payload = props.adapter.toPayload({
      id: editId.value,
      parentId: model.parentId,
      name: model.name.trim(),
      status: model.status,
      sort: model.sort ?? 0,
      description: model.description,
    });
    if (editId.value === undefined) await props.adapter.create(payload as any);
    else await props.adapter.update(payload as any);
    if (version !== requestVersion || !visible.value) return;
    message.success(editId.value === undefined ? '新增成功' : '修改成功');
    reset();
    emit('saved');
  } catch {
    // 统一请求层负责展示服务端错误，表单内容保留给用户修正。
  } finally {
    if (version === requestVersion) saving.value = false;
  }
}

onDeactivated(reset);
defineExpose({ open, close: reset });
</script>

<template>
  <Modal
    :open="visible"
    :title="title"
    :width="800"
    :confirm-loading="saving"
    :ok-button-props="{ disabled: !ready }"
    :closable="!saving"
    :keyboard="!saving"
    :mask-closable="false"
    class="qpc-modal"
    ok-text="确定"
    cancel-text="取消"
    destroy-on-hidden
    @cancel="cancel"
    @ok="save"
  >
    <template #closeIcon><ReferenceIcon name="close" /></template>
    <Spin :spinning="loading">
      <Form
        ref="formRef"
        :model="model"
        :rules="rules"
        :label-col="{ style: { width: '130px' } }"
        :wrapper-col="{ style: { flex: '1', minWidth: '0' } }"
        :disabled="!ready || saving"
        layout="horizontal"
        class="qpc-form grid grid-cols-1 gap-x-6 sm:grid-cols-2"
        autocomplete="off"
      >
        <FormItem label="上级类目" name="parentId">
          <TreeSelect v-model:value="model.parentId" :tree-data="options" :allow-clear="false" tree-default-expand-all />
        </FormItem>
        <FormItem :label="props.adapter.nameLabel" name="name">
          <Input v-model:value="model.name" :placeholder="`请输入${props.adapter.nameLabel}`" :maxlength="50" />
        </FormItem>
        <FormItem label="状态" name="status">
          <RadioGroup v-model:value="model.status" :options="[{ label: '启用', value: 0 }, { label: '禁用', value: 1 }]" />
        </FormItem>
        <FormItem label="排序" name="sort">
          <InputNumber v-model:value="model.sort" :min="0" :max="100000" :precision="0" class="w-full" />
        </FormItem>
        <FormItem label="描述" name="description" class="col-span-full">
          <TextArea v-model:value="model.description" :maxlength="256" :rows="4" show-count placeholder="请输入描述" />
        </FormItem>
      </Form>
    </Spin>
  </Modal>
</template>
