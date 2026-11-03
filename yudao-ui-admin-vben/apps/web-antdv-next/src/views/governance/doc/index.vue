<script lang="ts" setup>
import type { StandardDocApi } from '#/api/governance/doc';

import { onMounted, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictLabel } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { formatDate } from '@vben/utils';

import { Button, Empty, Input, message, Pagination, Spin } from 'antdv-next';

import { getDocPage } from '#/api/governance/doc';
import { DictTag } from '#/components/dict-tag';

import { getDocFileIconInfo } from './data';
import Detail from './modules/detail.vue';
import Preview from './modules/preview.vue';

const { hasAccessByCodes } = useAccess();

const loading = ref(false);
const list = ref<StandardDocApi.Doc[]>([]);
const total = ref(0);

const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  nameCn: '',
});

const STD_TYPE_TAG_CLASS: Record<string, string> = {
  NATIONAL: 'bg-[#f56c6c] text-white',
  INDUSTRY: 'bg-[#e6a23c] text-white',
  LOCAL: 'bg-[#409eff] text-white',
  GROUP: 'bg-[#67c23a] text-white',
  INNER: 'bg-[#909399] text-white',
};

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

const [PreviewModal, previewModalApi] = useVbenModal({
  connectedComponent: Preview,
  destroyOnClose: true,
});

async function getList() {
  loading.value = true;
  try {
    const data = await getDocPage({
      pageNo: queryParams.pageNo,
      pageSize: queryParams.pageSize,
      nameCn: queryParams.nameCn || undefined,
    });
    list.value = data.list || [];
    total.value = data.total || 0;
  } finally {
    loading.value = false;
  }
}

function handleQuery() {
  queryParams.pageNo = 1;
  getList();
}

function handleReset() {
  queryParams.nameCn = '';
  handleQuery();
}

function handlePageChange(page: number, pageSize: number) {
  queryParams.pageNo = page;
  queryParams.pageSize = pageSize;
  getList();
}

function handleDetail(row: StandardDocApi.Doc) {
  detailModalApi.setData(row).open();
}

function handlePreview(row: StandardDocApi.Doc) {
  if (!row.fileId && !row.fileUrl) {
    message.warning('该标准尚未上传原文文件');
    return;
  }
  previewModalApi.setData(row).open();
}

function hasOriginalFile(row: StandardDocApi.Doc) {
  return Boolean(row.fileId || row.fileUrl || row.fileName);
}

function getStdTypeTagClass(stdType?: string) {
  return STD_TYPE_TAG_CLASS[stdType || ''] || 'bg-[#909399] text-white';
}

function formatDay(value?: null | number | string) {
  return value ? formatDate(value, 'YYYY-MM-DD') : '-';
}

onMounted(() => {
  getList();
});
</script>

<template>
  <Page auto-content-height content-class="flex flex-col gap-3">
    <DetailModal />
    <PreviewModal />

    <div class="doc-search bg-card rounded-md">
      <form class="doc-search__form" @submit.prevent="handleQuery">
        <label class="doc-search__field" for="standard-doc-name">
          <span>标准名称</span>
          <Input
            id="standard-doc-name"
            v-model:value="queryParams.nameCn"
            allow-clear
            placeholder="请输入标准名称"
          />
        </label>
        <div class="doc-search__actions">
          <Button type="primary" html-type="submit">搜索</Button>
          <Button @click="handleReset">重置</Button>
        </div>
      </form>
      <span class="doc-search__total">共 {{ total }} 个标准</span>
    </div>

    <!-- 卡片列表 -->
    <div class="bg-muted/30 min-h-0 flex-1 overflow-auto rounded-md py-3">
      <Spin :spinning="loading" class="min-h-[320px] w-full">
        <div
          v-if="list.length > 0"
          class="grid grid-cols-1 gap-4 xl:grid-cols-2"
        >
          <div
            v-for="item in list"
            :key="item.id"
            class="bg-card rounded border border-border/60 px-6 py-4 shadow-sm transition-shadow hover:shadow-md"
          >
            <!-- 标题行 -->
            <div
              class="mb-3 flex items-start justify-between gap-3 border-b border-[#eee] pb-3"
            >
              <div class="flex min-w-0 flex-1 items-center gap-4">
                <button
                  type="button"
                  class="inline-flex shrink-0 items-center justify-center border-0 bg-transparent p-0"
                  :class="
                    hasOriginalFile(item)
                      ? 'cursor-pointer'
                      : 'cursor-default opacity-50'
                  "
                  @click="handlePreview(item)"
                >
                  <IconifyIcon
                    class="size-10"
                    :icon="getDocFileIconInfo(item.fileName).icon"
                    :style="{ color: getDocFileIconInfo(item.fileName).color }"
                  />
                </button>
                <div class="min-w-0 flex-1">
                  <div
                    class="truncate text-sm font-semibold text-[#3d446e]"
                    :title="item.nameCn"
                  >
                    {{ item.nameCn }}
                  </div>
                  <div
                    class="mt-0.5 truncate text-xs text-[rgba(88,88,88,0.85)]"
                    :title="item.fileName || item.stdCode || '-'"
                  >
                    {{ item.fileName || item.stdCode || '暂无原文文件' }}
                  </div>
                </div>
              </div>
              <div class="flex shrink-0 flex-wrap items-center gap-2">
                <button
                  type="button"
                  class="rounded bg-[#e6f4ff] px-3 py-1 text-sm font-medium text-[#2666fb]"
                  @click="handlePreview(item)"
                >
                  <IconifyIcon
                    icon="lucide:eye"
                    class="mr-1 inline-block size-4 align-middle"
                  />
                  查看
                </button>
                <button
                  v-if="hasAccessByCodes(['governance:doc:query'])"
                  type="button"
                  class="rounded bg-[#e6f4ff] px-3 py-1 text-sm font-medium text-[#2666fb]"
                  @click="handleDetail(item)"
                >
                  <IconifyIcon
                    icon="lucide:eye"
                    class="mr-1 inline-block size-4 align-middle"
                  />
                  详情
                </button>
              </div>
            </div>

            <!-- 信息区 -->
            <div class="grid grid-cols-1 gap-x-4 gap-y-2.5 sm:grid-cols-2">
              <div class="flex min-w-0 items-center gap-2 text-sm">
                <span class="shrink-0 text-[#717171]">标准分类:</span>
                <span
                  class="inline-flex rounded px-2 py-0.5 text-xs"
                  :class="getStdTypeTagClass(item.stdType)"
                >
                  {{
                    getDictLabel(DICT_TYPE.STANDARD_STD_TYPE, item.stdType) ||
                    '-'
                  }}
                </span>
              </div>
              <div class="flex min-w-0 items-center gap-2 text-sm">
                <span class="shrink-0 text-[#717171]">实施状态:</span>
                <DictTag
                  :type="DICT_TYPE.STANDARD_IMPLEMENT_STATUS"
                  :value="item.implementStatus"
                />
              </div>
              <div class="flex min-w-0 items-center gap-2 text-sm">
                <span class="shrink-0 text-[#717171]">发布日期:</span>
                <span class="truncate text-[rgba(0,0,0,0.85)]">
                  {{ formatDay(item.publishDate) }}
                </span>
              </div>
              <div class="flex min-w-0 items-center gap-2 text-sm">
                <span class="shrink-0 text-[#717171]">实施日期:</span>
                <span class="truncate text-[rgba(0,0,0,0.85)]">
                  {{ formatDay(item.implementDate) }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="flex min-h-[320px] items-center justify-center">
          <Empty description="暂无标准数据" />
        </div>
      </Spin>
    </div>

    <!-- 分页 -->
    <div
      v-if="total > 0"
      class="bg-card flex justify-end rounded-md px-4 py-2 shadow-sm"
    >
      <Pagination
        v-model:current="queryParams.pageNo"
        v-model:page-size="queryParams.pageSize"
        :total="total"
        :show-size-changer="true"
        :show-total="(count) => `共 ${count} 条`"
        @change="handlePageChange"
      />
    </div>
  </Page>
</template>

<style scoped>
.doc-search {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
}

.doc-search__form,
.doc-search__field,
.doc-search__actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.doc-search__form {
  flex-wrap: wrap;
  min-width: 0;
}

.doc-search__field {
  min-width: 0;
}

.doc-search__field > span {
  flex-shrink: 0;
}

.doc-search__field :deep(.ant-input-affix-wrapper) {
  width: 210px;
  max-width: 100%;
}

.doc-search__total {
  white-space: nowrap;
}

@media (max-width: 480px) {
  .doc-search__form,
  .doc-search__field {
    width: 100%;
  }

  .doc-search__field :deep(.ant-input-affix-wrapper) {
    flex: 1;
    width: 0;
  }
}
</style>
