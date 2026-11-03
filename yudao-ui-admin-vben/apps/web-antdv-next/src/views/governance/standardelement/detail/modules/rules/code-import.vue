<script setup lang="ts">
import type { CleanMappingItem } from '../../../../cleanrule/modules/clean-rule-parameters';

import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';

import { h, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, message, Radio, Table } from 'antdv-next';

import { getStandardCodeSetPage } from '#/api/governance/standardcodeset';
import { getCodeMappingPage } from '#/api/governance/standardcodeset/mapping';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import StandardPagination from '../../../../standardcommon/standard-pagination.vue';
import CategoryTree from '../../../components/category-tree.vue';
type Id = GovernanceStandardCodeSetApi.Id;
const emit = defineEmits<{ select: [CleanMappingItem[]] }>();
const access = useAccessStore();
const category = ref<Id>();
const rows = ref<GovernanceStandardCodeSetApi.StandardCodeSet[]>([]);
const selected = ref<Id>();
const failed = ref(false);
const loading = ref(false);
const page = ref(1);
const pageSize = ref(10);
const saving = ref(false);
const total = ref(0);
const requests = useObjectRequest(
  () =>
    JSON.stringify([
      page.value,
      pageSize.value,
      category.value,
      access.accessCodes,
    ]),
  () => {
    rows.value = [];
    selected.value = undefined;
  },
);
const imports = useObjectRequest(
  () => JSON.stringify([selected.value, access.accessCodes]),
  () => {
    saving.value = false;
  },
);
const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  showCancelButton: false,
  onClosed() {
    requests.begin();
    imports.begin();
  },
});
useObjectRequest(
  () => access.accessCodes.join(','),
  () => {
    void modalApi.close();
  },
);
async function open() {
  selected.value = undefined;
  category.value = undefined;
  page.value = 1;
  await modalApi.open();
  void load();
}
async function load() {
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getStandardCodeSetPage({
      pageNo: page.value,
      pageSize: pageSize.value,
      categoryId: category.value,
      status: 0,
    });
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function save() {
  if (selected.value === undefined || saving.value) return;
  const current = imports.begin();
  saving.value = true;
  try {
    const imported: CleanMappingItem[] = [];
    let pageNo = 1;
    while (true) {
      const result = await getCodeMappingPage({
        codeSetId: selected.value,
        pageNo,
        pageSize: 100,
      });
      if (!current()) return;
      if (result.total > 1000 || imported.length + result.list.length > 1000) {
        message.warning('代码映射超过 1000 条，请精简后导入');
        return;
      }
      imported.push(
        ...result.list.map((row) => ({
          value: row.originalValue,
          name: row.codeValue ?? '',
        })),
      );
      if (imported.length >= result.total) break;
      if (result.list.length === 0) throw new Error('代码映射分页结果不完整');
      if (imported.length >= 1000) {
        message.warning('代码映射超过 1000 条，请精简后导入');
        return;
      }
      pageNo++;
    }
    if (imported.length === 0) {
      message.warning('所选代码表暂无代码映射');
      return;
    }
    if (current()) {
      emit('select', imported);
      await modalApi.close();
    }
  } finally {
    if (current()) saving.value = false;
  }
}
const columns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 80 },
  { title: '中文名称', dataIndex: 'name', key: 'name', width: 130 },
  { title: '英文名称', dataIndex: 'type', key: 'type', width: 130 },
  { title: '类型', key: 'typeLabel', width: 100 },
  {
    title: '元描述',
    dataIndex: 'description',
    key: 'description',
    ellipsis: false,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '操作', key: 'action', width: 100, fixed: 'right' as const },
];
defineExpose({ open });
</script>
<template>
  <Modal
    title="选择数据元"
    :fullscreen-button="false"
    class="q-quality-modal standard-code-import"
    header-class="q-quality-dialog-header"
    content-class="q-quality-dialog-content"
    footer-class="q-quality-dialog-footer"
  >
    <div class="standard-import-layout">
      <aside class="standard-sidebar">
        <CategoryTree
          @select="
            category = $event;
            page = 1;
            load();
          "
        />
      </aside>
      <main>
        <Alert v-if="failed" type="error" message="代码表加载失败">
          <template #action>
            <Button @click="load">
              <template #icon>
                <IconifyIcon icon="lucide:refresh-cw" />
              </template>
              重试
            </Button>
          </template> </Alert
        ><Table
          :columns="columns"
          :scroll="{ x: 'max-content' }"
          :data-source="rows"
          row-key="id"
          :pagination="false"
          :loading="loading"
          class="standard-table"
        >
          <template #bodyCell="{ column, record }">
            <span v-if="column.key === 'typeLabel'">代码表</span
            ><Radio
              v-else-if="column.key === 'action'"
              :checked="String(selected) === String(record.id)"
              :aria-label="`选择${record.name}`"
              @change="selected = record.id"
            />
          </template> </Table
        ><StandardPagination
          v-model:page="page"
          v-model:page-size="pageSize"
          :total="total"
          @change="load"
        />
      </main>
    </div>
    <template #footer>
      <Button :disabled="saving" @click="modalApi.close()">取消</Button
      ><Button
        type="primary"
        :disabled="selected === undefined"
        :loading="saving"
        @click="save"
      >
        保存
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
:deep(.standard-table .ant-table-thead > tr > th),
:deep(.standard-table .ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
