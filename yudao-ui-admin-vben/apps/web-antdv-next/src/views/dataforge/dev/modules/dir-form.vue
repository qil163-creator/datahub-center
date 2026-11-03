<script lang="ts" setup>
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { onBeforeUnmount, reactive, ref } from 'vue';

import { handleTree } from '@vben/utils';

import {
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { createDevDir, getDevDirList, updateDevDir } from '#/api/dataforge/dev';

const emit = defineEmits<{ saved: [] }>();

const visible = ref(false);
const saving = ref(false);
const formRef = ref();
const editId = ref<DataforgeDevApi.Id>();

const formData = reactive<DataforgeDevApi.Dir>({});
const dirTree = ref<any[]>([]);

const rules = {
  name: [{ required: true, message: '请输入目录名称' }],
};

let openSeq = 0;

async function open(id?: DataforgeDevApi.Id, parentId?: DataforgeDevApi.Id) {
  const seq = ++openSeq;
  editId.value = id;
  Object.keys(formData).forEach((key) => Reflect.deleteProperty(formData, key));
  formData.parentId = parentId === undefined ? '0' : String(parentId);
  formData.status = 0;
  formData.sort = 0;
  visible.value = true;
  dirTree.value = [];
  const dirs = await getDevDirList();
  if (seq !== openSeq || !visible.value) return;
  dirTree.value = [
    {
      id: '0',
      name: '数据开发类目',
      children: handleTree(
        dirs.map((dir) => ({
          ...dir,
          id: String(dir.id),
          parentId: String(dir.parentId ?? 0),
        })),
      ),
    },
  ];
  if (id) {
    const dir = dirs.find((item) => String(item.id) === String(id));
    if (dir) {
      Object.assign(formData, dir, { parentId: String(dir.parentId ?? 0) });
    }
  }
}

async function handleOk() {
  if (saving.value) return;
  const seq = openSeq;
  saving.value = true;
  try {
    await formRef.value?.validate();
    if (seq !== openSeq || !visible.value) return;
    await (editId.value
      ? updateDevDir({ ...formData, id: editId.value })
      : createDevDir(formData));
    if (seq !== openSeq || !visible.value) return;
    message.success(editId.value ? '修改成功' : '新增成功');
    visible.value = false;
    emit('saved');
  } finally {
    if (seq === openSeq) saving.value = false;
  }
}

function close() {
  ++openSeq;
  visible.value = false;
  saving.value = false;
}
onBeforeUnmount(close);
defineExpose({ open, close });
</script>

<template>
  <Modal
    v-model:open="visible"
    :confirm-loading="saving"
    :title="editId ? '编辑数据开发类目' : '新增数据开发类目'"
    :width="800"
    destroy-on-hidden
    @cancel="close"
    @ok="handleOk"
  >
    <Form
      ref="formRef"
      :label-col="{ style: { width: '130px' } }"
      :model="formData"
      :rules="rules"
      :wrapper-col="{ style: { flex: '1 1 0' } }"
    >
      <FormItem label="上级类目" name="parentId">
        <TreeSelect
          v-model:value="formData.parentId"
          :field-names="{ children: 'children', label: 'name', value: 'id' }"
          :tree-data="dirTree as any"
          tree-default-expand-all
        />
      </FormItem>
      <FormItem label="类目名称" name="name">
        <Input
          v-model:value="formData.name"
          placeholder="请输入类目名称"
          :maxlength="100"
        />
      </FormItem>
      <FormItem label="状态">
        <RadioGroup
          v-model:value="formData.status"
          :options="[
            { label: '启用', value: 0 },
            { label: '禁用', value: 1 },
          ]"
        />
      </FormItem>
      <FormItem label="排序">
        <InputNumber v-model:value="formData.sort" :min="0" class="w-full" />
      </FormItem>
      <FormItem label="描述">
        <TextArea
          v-model:value="formData.description"
          :maxlength="500"
          :rows="4"
          show-count
          placeholder="请输入描述"
        />
      </FormItem>
      <FormItem label="备注">
        <TextArea
          v-model:value="formData.remark"
          :maxlength="500"
          :rows="4"
          show-count
          placeholder="请输入备注"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
