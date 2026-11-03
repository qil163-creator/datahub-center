<script lang="ts" setup>
import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { downloadFileFromBlobPart } from '@vben/utils';

import { Alert, Button, Checkbox, message, UploadDragger } from 'antdv-next';

import {
  downloadStandardCodeSetImportTemplate,
  importStandardCodeSet,
} from '#/api/governance/standardcodeset';

defineOptions({ name: 'GovernanceStandardCodeSetImportForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const file = ref<File>();
const updateSupport = ref(false);
const submitting = ref(false);
const result = ref<GovernanceStandardCodeSetApi.ImportResult>();

function handleBeforeUpload(selected: File) {
  file.value = selected;
  return false;
}

async function handleDownloadTemplate() {
  const blob = await downloadStandardCodeSetImportTemplate();
  downloadFileFromBlobPart({
    fileName: '码表码值导入模板.xls',
    source: blob,
  });
}

const [ModalComponent, modalApi] = useVbenModal({
  confirmText: '开始导入',
  async onConfirm() {
    if (!file.value) {
      message.warning('请选择码表/码值 Excel 文件');
      return;
    }
    submitting.value = true;
    modalApi.lock();
    try {
      result.value = await importStandardCodeSet(
        file.value,
        updateSupport.value,
      );
      message.success('导入完成');
      emit('success');
    } finally {
      submitting.value = false;
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      file.value = undefined;
      updateSupport.value = false;
      result.value = undefined;
    }
  },
});
</script>

<template>
  <ModalComponent class="w-[800px]" title="导入码表/码值（Excel）">
    <div class="flex flex-col gap-3 px-2 pb-2">
      <Alert show-icon type="info">
        <template #message>
          每行填写一个码值，同一字典类型的行归属同一码表；码表不存在时按「码表名称」自动创建。
          字典类型、码值键值、码值标签为必填。
          <Button size="small" type="link" @click="handleDownloadTemplate">
            <template #icon><IconifyIcon icon="lucide:download" /></template>
            下载模板
          </Button>
        </template>
      </Alert>
      <UploadDragger
        :before-upload="handleBeforeUpload"
        :max-count="1"
        :show-upload-list="true"
        accept=".xlsx,.xls"
      >
        <p class="py-4">点击或拖拽码表/码值 Excel 文件到此处</p>
      </UploadDragger>
      <div v-if="file" class="text-sm">
        已选择：<span class="font-mono">{{ file.name }}</span>
      </div>
      <Checkbox v-model:checked="updateSupport">
        码值已存在（字典类型+键值相同）时更新标签、排序、备注（不勾选则视为导入失败）
      </Checkbox>
      <!-- 导入结果 -->
      <Alert
        v-if="result"
        :type="
          Object.keys(result.failureReasons ?? {}).length > 0
            ? 'warning'
            : 'success'
        "
        show-icon
      >
        <template #message>
          新建码表 {{ result.createCodeSetTypes?.length ?? 0 }} 个、新建码值
          {{ result.createItemKeys?.length ?? 0 }} 个、更新码值
          {{ result.updateItemKeys?.length ?? 0 }} 个、失败
          {{ Object.keys(result.failureReasons ?? {}).length }} 个。
        </template>
        <template
          v-if="Object.keys(result.failureReasons ?? {}).length > 0"
          #description
        >
          <div
            v-for="(reason, key) in result.failureReasons"
            :key="key"
            class="text-xs"
          >
            {{ key }}：{{ reason }}
          </div>
        </template>
      </Alert>
    </div>
  </ModalComponent>
</template>
