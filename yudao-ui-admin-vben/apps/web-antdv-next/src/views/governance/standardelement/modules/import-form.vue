<script lang="ts" setup>
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { downloadFileFromBlobPart } from '@vben/utils';

import { Alert, Button, Checkbox, message, UploadDragger } from 'antdv-next';

import {
  downloadStandardElementImportTemplate,
  importStandardElement,
} from '#/api/governance/standardelement';

defineOptions({ name: 'GovernanceStandardElementImportForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const file = ref<File>();
const updateSupport = ref(false);
const submitting = ref(false);
const result = ref<GovernanceStandardElementApi.ImportResult>();

function handleBeforeUpload(selected: File) {
  file.value = selected;
  return false;
}

async function handleDownloadTemplate() {
  const blob = await downloadStandardElementImportTemplate();
  downloadFileFromBlobPart({
    fileName: '标准数据元导入模板.xls',
    source: blob,
  });
}

const [ModalComponent, modalApi] = useVbenModal({
  confirmText: '开始导入',
  async onConfirm() {
    if (!file.value) {
      message.warning('请选择数据元 Excel 文件');
      return;
    }
    submitting.value = true;
    modalApi.lock();
    try {
      result.value = await importStandardElement(
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
  <ModalComponent class="w-[800px]" title="导入标准数据元（Excel）">
    <div class="flex flex-col gap-3 px-2 pb-2">
      <Alert show-icon type="info">
        <template #message>
          按模板填写数据元清单后导入；导入的数据元默认为草稿态，需发布后正式生效。
          中文名称、英文名称、字段类型为必填，数据元类目按类目名称匹配。
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
        <p class="py-4">点击或拖拽数据元 Excel 文件到此处</p>
      </UploadDragger>
      <div v-if="file" class="text-sm">
        已选择：<span class="font-mono">{{ file.name }}</span>
      </div>
      <Checkbox v-model:checked="updateSupport">
        英文名称已存在时更新已有数据元（不勾选则视为导入失败）
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
          创建 {{ result.createNames?.length ?? 0 }} 个、更新
          {{ result.updateNames?.length ?? 0 }} 个、失败
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
