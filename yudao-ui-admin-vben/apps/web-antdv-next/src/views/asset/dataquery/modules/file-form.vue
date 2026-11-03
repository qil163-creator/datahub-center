<script lang="ts" setup>
import { computed, reactive, ref } from 'vue';

import { handleTree } from '@vben/utils';

import {
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
  createDataQueryFile,
  DataQueryApi,
  getDataQueryFileList,
  updateDataQueryFile,
} from '#/api/asset/dataquery';

defineOptions({ name: 'AssetDataQueryFileForm' });

const emit = defineEmits<{
  saved: [file: DataQueryApi.QueryFile];
}>();

const visible = ref(false);
const saving = ref(false);
const form = reactive<DataQueryApi.QueryFile>({
  nodeType: DataQueryApi.FILE_NODE_FOLDER,
  name: '',
  parentId: 0,
  sort: 0,
  visibility: DataQueryApi.FILE_VISIBILITY_PRIVATE,
});
const folderTree = ref<any[]>([]);

const isFile = computed(() => form.nodeType === DataQueryApi.FILE_NODE_FILE);
const getTitle = computed(() => {
  if (form.id) {
    return isFile.value ? '编辑脚本' : '编辑目录';
  }
  return isFile.value ? '保存脚本' : '新建目录';
});

async function loadFolderTree(excludeId?: number) {
  const files = await getDataQueryFileList();
  const list = files.filter(
    (item) =>
      item.nodeType === DataQueryApi.FILE_NODE_FOLDER && item.id !== excludeId,
  );
  folderTree.value = [{ id: 0, name: '根目录', children: handleTree(list) }];
}

async function open(payload: Partial<DataQueryApi.QueryFile>) {
  Object.assign(
    form,
    Object.fromEntries(Object.keys(form).map((key) => [key, undefined])),
  );
  Object.assign(form, {
    nodeType: DataQueryApi.FILE_NODE_FOLDER,
    name: '',
    parentId: 0,
    sort: 0,
    visibility: DataQueryApi.FILE_VISIBILITY_PRIVATE,
    ...payload,
  });
  if (form.nodeType === DataQueryApi.FILE_NODE_FOLDER) {
    form.visibility = DataQueryApi.FILE_VISIBILITY_PUBLIC;
  } else if (form.visibility === null || form.visibility === undefined) {
    form.visibility = DataQueryApi.FILE_VISIBILITY_PRIVATE;
  }
  visible.value = true;
  await loadFolderTree(form.id);
}

defineExpose({ open });

async function handleOk() {
  if (!form.name?.trim()) {
    message.warning('请输入名称');
    return;
  }
  saving.value = true;
  try {
    const data: DataQueryApi.QueryFile = {
      ...form,
      name: form.name.trim(),
      parentId: form.parentId ?? 0,
    };
    if (form.id) {
      await updateDataQueryFile(data);
      message.success('已保存');
      emit('saved', data);
    } else {
      const id = await createDataQueryFile(data);
      message.success(isFile.value ? '脚本已保存' : '目录已创建');
      emit('saved', { ...data, id });
    }
    visible.value = false;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <Modal
    v-model:open="visible"
    :confirm-loading="saving"
    :title="getTitle"
    ok-text="保存"
    width="800px"
    @ok="handleOk"
  >
    <div class="file-form">
      <div class="file-form__item">
        <label class="file-form__label">父级目录</label>
        <TreeSelect
          v-model:value="form.parentId"
          :field-names="{ children: 'children', label: 'name', value: 'id' }"
          :tree-data="folderTree"
          class="flex-1"
          placeholder="不选则为根目录"
          tree-default-expand-all
        />
      </div>
      <div class="file-form__item">
        <label class="file-form__label file-form__label--required">
          {{ isFile ? '脚本名称' : '目录名称' }}
        </label>
        <Input
          v-model:value="form.name"
          :maxlength="128"
          class="flex-1"
          :placeholder="isFile ? '如：月报统计' : '请输入目录名称'"
        />
      </div>
      <div v-if="isFile" class="file-form__item">
        <label class="file-form__label file-form__label--required">可见范围</label>
        <div class="flex flex-1 flex-col gap-1">
          <RadioGroup v-model:value="form.visibility">
            <Radio :value="DataQueryApi.FILE_VISIBILITY_PUBLIC">公开</Radio>
            <Radio :value="DataQueryApi.FILE_VISIBILITY_PRIVATE">私有</Radio>
          </RadioGroup>
          <span class="text-muted-foreground text-xs">
            公开后平台内所有人可见；私有仅自己可见
          </span>
        </div>
      </div>
      <div class="file-form__item">
        <label class="file-form__label">排序</label>
        <InputNumber v-model:value="form.sort as any" :min="0" class="flex-1" />
      </div>
      <div class="file-form__item">
        <label class="file-form__label">备注</label>
        <TextArea
          v-model:value="form.remark"
          :maxlength="500"
          :rows="3"
          class="flex-1"
          placeholder="可选"
        />
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.file-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 8px 0;
}

.file-form__item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.file-form__label {
  flex-shrink: 0;
  width: 130px;
  padding-top: 5px;
  font-size: 14px;
  text-align: right;
}

.file-form__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}
</style>
