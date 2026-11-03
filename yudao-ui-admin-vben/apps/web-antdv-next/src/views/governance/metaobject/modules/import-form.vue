<script lang="ts" setup>
import type { UploadChangeParam, UploadFile } from 'antdv-next';

import type { GovernanceMetaImportApi } from '#/api/governance/metaimport';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { downloadFileFromBlobPart } from '@vben/utils';

import { Alert, Button, message, Select, UploadDragger } from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getSystemSourcePage } from '#/api/dataforge/systemsource';
import {
  downloadMetaImportTemplate,
  importMetaDict,
} from '#/api/governance/metaimport';

defineOptions({ name: 'GovernanceMetaObjectImportForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const datasourceId = ref<number | string>();
const sourceSystemId = ref<number>();
const sourceSystemName = ref<string>();
const fileList = ref<UploadFile[]>([]);
const file = computed(() => fileList.value[0]?.originFileObj);
const submitting = ref(false);
const result = ref<GovernanceMetaImportApi.ImportResult>();

/** 下拉数据 */
const databases = ref<{ label: string; value: number | string }[]>([]);
const systems = ref<{ label: string; value: number }[]>([]);

async function loadOptions() {
  const [databaseList, systemPage] = await Promise.all([
    getDatabaseSimpleList(),
    getSystemSourcePage({ pageNo: 1, pageSize: 100 }),
  ]);
  databases.value = databaseList.map((item) => ({
    label: item.name ?? `数据源#${item.id}`,
    value: item.id,
  }));
  systems.value = (systemPage.list ?? []).map((item) => ({
    label: item.name ?? `系统#${item.id}`,
    value: item.id,
  }));
}

function handleSystemChange(value: any) {
  sourceSystemName.value = systems.value.find(
    (item) => item.value === value,
  )?.label;
}

function handleBeforeUpload() {
  return false;
}

function handleFileChange(value: UploadChangeParam) {
  fileList.value = value.fileList.slice(-1);
  result.value = undefined;
}

async function handleDownloadTemplate() {
  const blob = await downloadMetaImportTemplate();
  downloadFileFromBlobPart({
    fileName: '元数据数据字典导入模板.xlsx',
    source: blob,
  });
}

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  confirmText: '开始导入',
  async onConfirm() {
    if (submitting.value) return;
    if (!datasourceId.value) {
      message.warning(
        '请选择数据源（无直连场景请先在数据源中心登记逻辑数据源）',
      );
      return;
    }
    if (!file.value) {
      message.warning('请选择数据字典 Excel 文件');
      return;
    }
    submitting.value = true;
    modalApi.lock();
    try {
      result.value = await importMetaDict(
        datasourceId.value,
        file.value,
        sourceSystemId.value,
        sourceSystemName.value,
      );
      message.success('导入完成，可在采集实例中查看明细日志');
      emit('success');
    } finally {
      submitting.value = false;
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      datasourceId.value = undefined;
      sourceSystemId.value = undefined;
      sourceSystemName.value = undefined;
      fileList.value = [];
      result.value = undefined;
      return;
    }
    await loadOptions();
  },
});
</script>

<template>
  <ModalComponent class="w-[800px]" title="导入数据字典（Excel）">
    <div
      class="import-form governance-modal-form governance-form-item-horizontal"
    >
      <Alert show-icon type="info">
        <template #message>
          面向无直连报送场景：按模板填写库/表/字段清单后导入，与直连采集走同一套
          差异引擎（版本、快照、变更留痕一致）；导入不做删除识别。
          <Button size="small" type="link" @click="handleDownloadTemplate">
            <template #icon><IconifyIcon icon="lucide:download" /></template>
            下载模板
          </Button>
        </template>
      </Alert>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label form-item__label--required">数据源</label>
          <Select
            v-model:value="datasourceId"
            :options="databases"
            class="w-full"
            option-filter-prop="label"
            placeholder="请选择数据源"
            show-search
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">来源系统</label>
          <Select
            v-model:value="sourceSystemId"
            :options="systems"
            allow-clear
            class="w-full"
            option-filter-prop="label"
            placeholder="请选择来源系统"
            show-search
            @change="handleSystemChange"
          />
        </div>
      </div>
      <UploadDragger
        :before-upload="handleBeforeUpload"
        :file-list="fileList"
        :disabled="submitting"
        :max-count="1"
        :show-upload-list="true"
        accept=".xlsx,.xls"
        @change="handleFileChange"
      >
        <p class="py-4">点击或拖拽数据字典 Excel 文件到此处</p>
      </UploadDragger>
      <div v-if="file" class="text-sm">
        已选择：<span class="font-mono">{{ file.name }}</span>
      </div>
      <!-- 导入结果 -->
      <Alert
        v-if="result"
        :type="
          (result.invalidRows ?? 0) > 0 || result.errors?.length
            ? 'warning'
            : 'success'
        "
        show-icon
      >
        <template #message>
          实例 #{{ result.instanceId }}：库 {{ result.dbCount ?? 0 }} 个、表
          {{ result.tableCount ?? 0 }} 张（新增 {{ result.tableAdd ?? 0 }}、变更
          {{ result.tableUpdate ?? 0 }}），字段新增
          {{ result.columnAdd ?? 0 }}、变更 {{ result.columnUpdate ?? 0 }}、删除
          {{ result.columnDelete ?? 0 }}；非法行 {{ result.invalidRows ?? 0 }}。
        </template>
        <template v-if="result.errors?.length" #description>
          <div
            v-for="(error, index) in result.errors"
            :key="index"
            class="text-xs"
          >
            {{ error }}
          </div>
        </template>
      </Alert>
    </div>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';
</style>
