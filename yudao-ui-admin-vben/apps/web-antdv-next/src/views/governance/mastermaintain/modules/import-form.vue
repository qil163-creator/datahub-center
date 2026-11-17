<script lang="ts" setup>
import type { UploadChangeParam, UploadFile } from 'antdv-next';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { downloadFileFromBlobPart } from '@vben/utils';

import { Alert, Button, UploadDragger } from 'antdv-next';

import { downloadMasterImportTemplate, importMasterData } from '#/api/governance/mastermanage';

import { masterImportCommand } from '../../mastercommon/master-submit';
import { useMasterScope } from '../../mastercommon/use-master-scope';

const emit = defineEmits<{ success: [] }>();
const model = ref<GovernanceMasterDataApi.Model>();
const files = ref<UploadFile[]>([]);
const errorMessage = ref('');
const busy = ref(false);
const receipt = ref<GovernanceMasterManageApi.SubmitReceipt>();
const scope = useMasterScope(() => { void modalApi.close(); });
const receiptText = computed(() => {
  const value = receipt.value;
  if (!value) return '';
  if (value.status === 'APPLIED') return '导入已生效';
  if (['PENDING', 'WAIT_START'].includes(value.status)) return '导入已提交审批，审批通过后生效';
  return value.message || '请在审批记录中查看该批次的处理结果';
});
const [Modal, modalApi] = useVbenModal({
  confirmText: '开始导入',
  onOpenChange(open) {
    scope.invalidate();
    files.value = [];
    receipt.value = undefined;
    errorMessage.value = '';
    busy.value = false;
    model.value = open ? (modalApi.getData() as GovernanceMasterDataApi.Model) : undefined;
  },
  async onConfirm() {
    const id = model.value?.id;
    const file = files.value[0]?.originFileObj;
    if (busy.value || receipt.value || id === undefined) return;
    if (!file) {
      errorMessage.value = '请选择填写好的 .xlsx 文件';
      return;
    }
    if (!file.name.toLowerCase().endsWith('.xlsx') || file.size > 5 * 1024 * 1024) {
      errorMessage.value = '请选择不超过 5 MB 的 .xlsx 文件';
      return;
    }
    const current = scope.begin('submit');
    busy.value = true;
    errorMessage.value = '';
    modalApi.lock();
    try {
      const cmd = await masterImportCommand(scope.owner.value, String(id), file);
      if (!current()) return;
      const result = await importMasterData(id, cmd.requestId, file);
      if (!['APPLYING', 'APPROVED', 'UNKNOWN'].includes(result.status)) cmd.complete();
      if (!current()) return;
      receipt.value = result;
      emit('success');
    } catch (error) {
      const response = error as { msg?: string };
      if (current()) errorMessage.value = response?.msg || (error instanceof Error ? error.message : '') || '导入未完成，可修正文件后重试';
    } finally {
      if (current()) {
        busy.value = false;
        modalApi.unlock();
      }
    }
  },
});
function choose(change: UploadChangeParam) {
  if (busy.value) return;
  files.value = change.fileList.slice(-1);
  errorMessage.value = '';
  receipt.value = undefined;
}
async function downloadTemplate() {
  const target = model.value;
  if (target?.id === undefined) return;
  const current = scope.begin('download');
  const blob = await downloadMasterImportTemplate(target.id);
  if (current()) downloadFileFromBlobPart({ fileName: `${target.tableName || '主数据'}-主数据导入模板.xlsx`, source: blob });
}
</script>

<template>
  <Modal class="w-[650px]" :title="`批量导入 · ${ model?.tableCn || model?.tableName || ''}`" :show-confirm-button="!receipt" :cancel-text="receipt ? '关闭' : '取消'">
    <div class="flex flex-col gap-4">
      <div>
        <Button @click="downloadTemplate">下载模板</Button>
        <p class="mt-2 text-muted-foreground">在首张工作表保留第一行字段名，从第二行填写新增记录。每批最多 200 行，支持 5 MB 以内的 .xlsx 文件。请将长编号和精确小数按文本填写。</p>
      </div>
      <Alert v-if="model?.approvalEnabled" type="info" show-icon message="本批数据将一起提交审批，审批通过后写入源表。" />
      <UploadDragger :file-list="files" :before-upload="() => false" :max-count="1" :disabled="busy" accept=".xlsx" @change="choose">
        <p class="py-4">点击或拖入填写好的 Excel 文件</p>
      </UploadDragger>
      <Alert v-if="errorMessage" type="error" show-icon :message="errorMessage" />
      <Alert v-if="receipt" :type="receipt.status === 'APPLIED' ? 'success' : receipt.status === 'REJECTED' ? 'error' : 'info'" show-icon :message="receiptText" :description="`变更单编号 ${ receipt.id}`" />
    </div>
  </Modal>
</template>
