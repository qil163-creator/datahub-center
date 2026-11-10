<script lang="ts" setup>
import type { TreeProps } from 'antdv-next';

import type { DataforgeDataFileApi as Api } from '#/api/dataforge/datafile';

import { computed, nextTick, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Card,
  Checkbox,
  Dropdown,
  Input,
  Menu,
  Modal,
  Spin,
  Tag,
  Tooltip,
  Tree,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

import {
  FILE_TYPE_OPTIONS,
  formatSize,
  parentPath,
  useGridColumns,
} from './data';
import ConnectionDialog from './modules/connection-dialog.vue';
import FileEmpty from './modules/file-empty.vue';
import folderOpenUrl from './reference-assets/folder-open.png';
import folderUrl from './reference-assets/folder.png';
import { useFileManager } from './use-file-manager';

defineOptions({ name: 'DataforgeDataFile' });
const manager = useFileManager();
const {
  canQuery,
  canCreate,
  canUpdate,
  canDelete,
  canDownload,
  connections,
  connection,
  connectionOpen,
  connectionLoading,
  summary,
  summaryLoading,
  summaryError,
  path,
  name,
  type,
  rows,
  total,
  pageNo,
  pageSize,
  loading,
  listError,
  selected,
  directoryFilter,
  expandedKeys,
  tree,
  treeLoading,
  directoryOperation,
  allDirectoriesExpanded,
  busy,
  failures,
  editOpen,
  editMode,
  editName,
  editError,
  previewOpen,
  preview,
  previewLoading,
  previewError,
  uploadOpen,
  batchUpload,
  uploads,
  uploading,
  uploadPath,
} = manager;
const treeVisible = ref(true);
const fileInput = ref<HTMLInputElement>();
const selectedCount = computed(() => selected.value.length);
const statisticItems = computed(() => [
  {
    key: 'files',
    label: '文件总数',
    value: summary.value?.fileCount ?? '−',
    icon: new URL('reference-assets/stat-files.png', import.meta.url).href,
  },
  {
    key: 'upload',
    label: '今日上传',
    value: summary.value?.todayUploadCount ?? '−',
    icon: new URL('reference-assets/stat-upload.png', import.meta.url).href,
  },
  {
    key: 'folders',
    label: '目录总数',
    value: summary.value?.directoryCount ?? '−',
    icon: new URL('reference-assets/stat-folders.png', import.meta.url).href,
  },
  {
    key: 'storage',
    label: '存储空间',
    value: formatSize(summary.value?.storageBytes),
    icon: new URL('reference-assets/stat-storage.png', import.meta.url).href,
  },
]);

const [Grid, gridApi] = useVbenVxeGrid<Api.Entry>({
  formOptions: {
    schema: [
      {
        fieldName: 'name',
        label: '文件名',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入文件名称' },
      },
      {
        fieldName: 'type',
        label: '文件类型',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: FILE_TYPE_OPTIONS,
          placeholder: '请选择文件类型',
        },
      },
    ],
    submitButtonOptions: { show: false },
    resetButtonOptions: { show: false },
    handleSubmit: async (values) => {
      name.value = values.name ?? '';
      type.value = values.type;
      await manager.search();
    },
    handleReset: resetSearch,
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    data: [],
    height: 'auto',
    proxyConfig: { enabled: false, autoLoad: false },
    rowConfig: { keyField: 'path', isHover: true },
    sortConfig: { remote: true, multiple: false },
    columns: useGridColumns(),
    pagerConfig: {
      currentPage: 1,
      pageSize: 10,
      pageSizes: [10, 20, 50, 100],
      total: 0,
    },
    toolbarConfig: { custom: true, refresh: false, zoom: false },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize: size,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      pageNo.value = currentPage;
      pageSize.value = size;
      void manager.loadList();
    },
    checkboxAll: ({ records }: { records: Api.Entry[] }) => {
      selected.value = records;
    },
    checkboxChange: ({ records }: { records: Api.Entry[] }) => {
      selected.value = records;
    },
    sortChange: ({ order }: { order: 'asc' | 'desc' | null }) => {
      manager.order.value = order ?? undefined;
      void manager.loadList(true);
    },
  },
});
const searchVisible = gridApi.useStore((state) => state.showSearchForm);
watch(
  [rows, total, loading, pageNo, pageSize],
  async () => {
    gridApi.setGridOptions({
      data: rows.value,
      loading: loading.value,
      pagerConfig: {
        total: total.value,
        currentPage: pageNo.value,
        pageSize: pageSize.value,
      },
    });
    gridApi.grid?.clearCheckboxRow?.();
    await nextTick();
    await nextTick();
    if (manager.order.value)
      void gridApi.grid.sort?.('updateTime', manager.order.value);
  },
  { immediate: true },
);
watch([name, type], () => {
  void gridApi.formApi.setValues?.({ name: name.value, type: type.value });
});
async function resetSearch() {
  await gridApi.formApi.reset();
  await manager.search(true);
}
const selectDirectory: TreeProps['onSelect'] = (keys) => {
  if (keys[0] !== undefined) void manager.navigate(String(keys[0]));
};
const loadTree: TreeProps['loadData'] = async (node) => {
  await manager.loadDirectory(String(node.key));
};
function handleFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  manager.addFiles([...(input.files ?? [])]);
  input.value = '';
}
function treeEntry(node: unknown) {
  const entry = node as { key: string; title: string };
  return { path: String(entry.key), name: entry.title };
}
</script>

<template>
  <Page auto-content-height>
    <div class="file-manager">
      <Alert
        v-if="!canQuery"
        type="warning"
        message="暂无文件管理查询权限"
        show-icon
      />
      <Card class="shrink-0" :styles="{ body: { padding: '16px' } }">
        <section class="file-statistics" aria-label="连接与文件统计">
          <div class="connection-summary">
            <div>
              <span>当前数据连接：</span
              ><button
                type="button"
                class="connection-name"
                :title="connection?.name"
                :disabled="busy || uploading"
                @click="connectionOpen = true"
              >
                {{ connection?.name || '−' }}</button
              ><span class="connection-status">连接状态：</span
              ><Tag
                v-if="summary"
                :color="
                  summary.connectionStatus === 'CONNECTED' ? 'success' : 'error'
                "
              >
                {{
                  summary.connectionStatus === 'CONNECTED' ? '正常' : '异常'
                }} </Tag
              ><span v-else>−</span>
            </div>
            <div>
              <span>连接地址：</span
              ><span class="connection-address" :title="connection?.address">{{
                connection?.address || '−'
              }}</span>
            </div>
          </div>
          <Spin
            v-for="item in statisticItems"
            :key="item.key"
            :spinning="summaryLoading"
            class="statistic-spin"
          >
            <div class="statistic-item">
              <img
                :src="item.icon"
                alt=""
                aria-hidden="true"
                draggable="false"
              />
              <div>
                <span>{{ item.label }}</span
                ><strong>{{ item.value }}</strong>
              </div>
            </div>
          </Spin>
          <div class="connection-switch">
            <Button
              type="primary"
              :disabled="busy || uploading || !canQuery"
              @click="connectionOpen = true"
            >
              <IconifyIcon icon="lucide:arrow-left-right" />{{
                connection ? '切换连接' : '选择连接'
              }}
            </Button>
          </div>
        </section>
      </Card>
      <Alert
        v-if="
          !summaryError && summary && summary.connectionStatus === 'CONNECTED' && !summary.complete
        "
        type="warning"
        :message="
          summary?.message || '本次扫描未完成，统计暂不可用。'
        "
        show-icon
      />
      <Alert
        v-if="failures.length"
        type="error"
        :message="`${failures.length} 项删除失败`"
        show-icon
        closable
        @close="failures = []"
      >
        <template #description>
          <ul>
            <li v-for="failure in failures" :key="failure.path">
              {{ failure.path }}：{{ failure.message }}
            </li>
          </ul>
        </template>
      </Alert>

      <div
        class="file-workspace"
        :class="{ 'file-workspace--collapsed': !treeVisible }"
      >
        <Card
          v-if="treeVisible"
          class="file-tree-panel"
          :classes="{ body: 'subtle-tree-scrollbar' }"
          :styles="{
            body: {
              display: 'flex',
              flexDirection: 'column',
              height: '100%',
              overflow: 'auto',
              padding: '12px',
            },
          }"
        >
          <div class="mb-3 flex items-center gap-2">
            <Input
              v-model:value="directoryFilter"
              class="min-w-0 flex-1"
              allow-clear
              placeholder="请输入目录名称"
              aria-label="搜索目录"
            >
              <template #prefix><IconifyIcon icon="lucide:search" /></template>
            </Input>
            <TreeToolbar
              :expanded="
                allDirectoriesExpanded || directoryOperation === 'expand'
              "
              :loading="treeLoading || !!directoryOperation"
              :disabled="
                !connection ||
                busy ||
                uploading ||
                directoryOperation === 'refresh'
              "
              @toggle-expand="manager.toggleExpandDirectories"
              @refresh="manager.refreshDirectories"
            />
          </div>
          <Spin :spinning="treeLoading || !!directoryOperation">
            <Tree
              v-if="connection"
              v-model:expanded-keys="expandedKeys"
              :tree-data="tree"
              :auto-expand-parent="false"
              :selected-keys="[path]"
              :load-data="loadTree"
              :disabled="busy || uploading"
              block-node
              @select="selectDirectory"
            >
              <template #titleRender="node">
                <span class="file-tree-title"
                  ><img
                    class="file-folder-icon"
                    :src="
                      treeEntry(node).path === path ? folderOpenUrl : folderUrl
                    "
                    alt=""
                    aria-hidden="true"
                  /><span
                    class="file-tree-label"
                    :title="treeEntry(node).name"
                    >{{ treeEntry(node).name }}</span
                  ><Dropdown :trigger="['click']" placement="bottomRight"
                    ><button
                      type="button"
                      class="file-tree-more"
                      :aria-label="`${treeEntry(node).name}目录操作`"
                      @click.stop
                    >
                      <IconifyIcon icon="lucide:ellipsis" /></button
                    ><template #popupRender
                      ><Menu>
                        <Menu.Item
                          v-if="canCreate"
                          key="child"
                          @click="manager.openMkdir(treeEntry(node).path)"
                          ><template #icon
                            ><IconifyIcon icon="lucide:folder-plus" /></template
                          >新增子级</Menu.Item
                        >
                        <Menu.Item
                          v-if="canCreate && treeEntry(node).path !== '/'"
                          key="sibling"
                          @click="
                            manager.openMkdir(parentPath(treeEntry(node).path))
                          "
                          ><template #icon
                            ><IconifyIcon icon="lucide:folder-plus" /></template
                          >新增同级</Menu.Item
                        >
                        <Menu.Item
                          v-if="canUpdate && treeEntry(node).path !== '/'"
                          key="rename"
                          @click="
                            manager.openRename(
                              treeEntry(node).path,
                              treeEntry(node).name,
                            )
                          "
                          ><template #icon
                            ><IconifyIcon icon="lucide:edit" /></template
                          >修改</Menu.Item
                        >
                        <Menu.Item
                          v-if="canDelete && treeEntry(node).path !== '/'"
                          key="delete"
                          danger
                          @click="manager.remove([treeEntry(node)])"
                          ><template #icon
                            ><IconifyIcon icon="lucide:trash-2" /></template
                          >删除</Menu.Item
                        >
                      </Menu></template
                    ></Dropdown
                  ></span
                >
              </template>
            </Tree>
            <FileEmpty v-else compact />
          </Spin>
        </Card>
        <SidebarCollapse
          :collapsed="!treeVisible"
          label="目录"
          @toggle="treeVisible = !treeVisible"
        />
        <section class="file-list-panel">
          <div v-if="connection && path !== '/'" class="file-location">
            <button
              type="button"
              :disabled="busy || uploading"
              @click="manager.navigate(parentPath(path))"
            >
              <IconifyIcon icon="lucide:corner-up-left" />返回上级</button
            ><span :title="path">{{ path }}</span>
          </div>
          <Grid table-title="文件管理" class="min-h-0 flex-1">
            <template #form-submit-before>
              <Button :disabled="!connection || !canQuery" @click="resetSearch">
                <IconifyIcon icon="lucide:brush-cleaning" />重置
              </Button>
              <Button
                type="primary"
                :disabled="!connection || !canQuery"
                @click="gridApi.formApi.validateAndSubmit()"
              >
                <IconifyIcon icon="lucide:list-filter" />查询
              </Button>
            </template>
            <template #toolbar-tools>
              <div class="file-toolbar-actions">
                <Button
                  v-if="canCreate"
                  type="primary"
                  :disabled="!connection || busy || uploading"
                  @click="manager.openUpload(false)"
                >
                  <IconifyIcon icon="lucide:upload" />上传文件
                </Button>
                <Button
                  v-if="canCreate"
                  type="primary"
                  :disabled="!connection || busy || uploading"
                  @click="manager.openUpload(true)"
                >
                  <IconifyIcon icon="lucide:upload" />批量上传
                </Button>
                <Button
                  v-if="canDownload"
                  :disabled="!selectedCount || busy || uploading"
                  @click="manager.download(selected)"
                >
                  <IconifyIcon icon="lucide:download" />批量下载
                </Button>
                <Button
                  v-if="canDelete"
                  danger
                  :disabled="!selectedCount || busy || uploading"
                  @click="manager.remove(selected)"
                >
                  <IconifyIcon icon="lucide:trash-2" />批量删除
                </Button>
              </div>
              <div class="file-toolbar-tools">
                <Tooltip title="搜索">
                  <Button
                    aria-label="显示或隐藏搜索"
                    shape="circle"
                    :type="searchVisible ? 'primary' : 'default'"
                    @click="gridApi.toggleSearchForm()"
                  >
                    <IconifyIcon icon="lucide:search" />
                  </Button> </Tooltip
                ><Tooltip title="刷新">
                  <Button
                    aria-label="刷新文件"
                    shape="circle"
                    :disabled="!connection || busy || uploading"
                    @click="manager.refresh()"
                  >
                    <IconifyIcon icon="lucide:refresh-cw" />
                  </Button>
                </Tooltip>
              </div>
            </template>
            <template #empty>
              <FileEmpty
                :description="
                  connection
                    ? listError ? '' : '暂无数据'
                    : '请选择数据连接'
                "
              />
            </template>
            <template #name="{ row }">
              <button
                type="button"
                class="file-name-link"
                :title="row.name"
                :disabled="busy || uploading"
                @click="manager.previewEntry(row)"
              >
                {{ row.name }}
              </button>
            </template>
            <template #type="{ row }">
              <Tag color="blue">
                {{ row.directory ? '文件夹' : row.type }}
              </Tag>
            </template>
            <template #actions="{ row }">
              <TableAction
                :actions="[
                  {
                    label: '预览',
                    icon: ACTION_ICON.VIEW,
                    type: 'link',
                    auth: ['dataforge:data-file:query'],
                    disabled: busy || uploading,
                    onClick: () => manager.previewEntry(row),
                  },
                  {
                    label: '下载',
                    icon: ACTION_ICON.DOWNLOAD,
                    type: 'link',
                    auth: ['dataforge:data-file:download'],
                    disabled: busy || uploading,
                    onClick: () => manager.download([row]),
                  },
                  {
                    label: '重命名',
                    icon: ACTION_ICON.EDIT,
                    type: 'link',
                    auth: ['dataforge:data-file:update'],
                    disabled: busy || uploading,
                    onClick: () => manager.openRename(row.path, row.name),
                  },
                  {
                    label: '删除',
                    icon: ACTION_ICON.DELETE,
                    type: 'link',
                    danger: true,
                    auth: ['dataforge:data-file:delete'],
                    disabled: busy || uploading,
                    onClick: () => manager.remove([row]),
                  },
                ]"
              />
            </template>
          </Grid>
        </section>
      </div>

      <ConnectionDialog
        v-model:open="connectionOpen"
        :connections="connections"
        :selected="connection"
        :loading="connectionLoading"
        @select="manager.selectConnection"
      />
      <Modal
        v-model:open="editOpen"
        :title="editMode === 'rename' ? '重命名' : '新建文件夹'"
        :width="500"
        :confirm-loading="busy"
        :closable="!busy"
        :mask-closable="!busy"
        :cancel-button-props="{ disabled: busy }"
        class="file-name-modal"
        @ok="manager.saveName"
      >
        <div class="file-name-form">
          <label for="new-file-name"
            ><span>*</span
            >{{ editMode === 'rename' ? '文件名' : '文件夹名称' }}</label
          >
          <div>
            <Input
              id="new-file-name"
              v-model:value="editName"
              :maxlength="50"
              :disabled="busy"
              @press-enter="manager.saveName"
            />
            <p v-if="editError" class="file-error">{{ editError }}</p>
          </div>
        </div>
      </Modal>
      <Modal
        v-model:open="previewOpen"
        :title="preview?.name || '文件预览'"
        :width="900"
        :footer="null"
        class="file-preview-modal"
      >
        <Spin :spinning="previewLoading">
          <Alert
            v-if="previewError"
            type="error"
            message="文件预览失败，请关闭后重试。"
            show-icon
          /><Alert
            v-else-if="preview?.type === 'UNSUPPORTED'"
            type="info"
            message="当前文件类型暂不支持在线预览，可下载后查看。"
            show-icon
          /><template v-else-if="preview">
            <Alert
              v-if="preview.truncated"
              type="warning"
              message="文件内容过大，仅展示前 1 MB。"
              show-icon
            />
            <img
              v-if="preview.type === 'IMAGE'"
              :src="manager.previewUrl.value"
              :alt="preview.name"
              class="file-preview-image"
            />
            <iframe
              v-else-if="preview.type === 'PDF'"
              :src="manager.previewUrl.value"
              :title="preview.name"
              referrerpolicy="no-referrer"
              class="file-preview-pdf"
            ></iframe>
            <pre v-else class="file-preview-text">{{ preview.content }}</pre>
          </template>
          <div v-else class="file-preview-placeholder"></div>
        </Spin>
      </Modal>
      <Modal
        v-model:open="uploadOpen"
        :title="batchUpload ? '批量上传' : '上传文件'"
        :width="900"
        :closable="!uploading"
        :mask-closable="!uploading"
        :cancel-button-props="{ disabled: uploading }"
        :confirm-loading="uploading"
        :ok-text="
          uploads.some((item) => item.state === 'error')
            ? '重试失败项'
            : '开始上传'
        "
        :ok-button-props="{
          disabled:
            uploads.length === 0 ||
            uploads.every((item) => item.state === 'success'),
        }"
        class="file-upload-modal"
        @ok="manager.submitUploads"
      >
        <div class="file-upload-location">
          <span>上传目录</span><Input :value="uploadPath" disabled />
        </div>
        <input
          ref="fileInput"
          type="file"
          :multiple="batchUpload"
          class="file-input-hidden"
          @change="handleFiles"
        />
        <button
          type="button"
          class="file-upload-drop"
          :disabled="uploading"
          @click="fileInput?.click()"
          @dragover.prevent
          @drop.prevent="
            !uploading &&
            manager.addFiles([...($event.dataTransfer?.files ?? [])])
          "
        >
          <IconifyIcon icon="lucide:cloud-upload" /><strong
            >点击选择或拖拽文件到此处上传</strong
          ><span>{{
            batchUpload
              ? '单批最多 20 个文件，总大小不超过 500 MB'
              : '单个文件大小不超过 500 MB'
          }}</span>
        </button>
        <div v-if="uploads.length" class="file-upload-queue">
          <div v-for="(item, index) in uploads" :key="item.file.name">
            <span :title="item.file.name">{{ item.file.name }}</span
            ><span>{{ formatSize(item.file.size) }}</span
            ><Tag
              :color="
                item.state === 'success'
                  ? 'success'
                  : item.state === 'error'
                    ? 'error'
                    : 'processing'
              "
            >
              {{
                item.state === 'success'
                  ? '上传成功'
                  : item.state === 'error'
                    ? '上传失败，可重试'
                    : item.state === 'uploading'
                      ? '正在上传'
                      : '等待上传'
              }} </Tag
            ><Button
              v-if="item.state !== 'success'"
              type="text"
              size="small"
              :disabled="uploading"
              :aria-label="`移除${item.file.name}`"
              @click="uploads.splice(index, 1)"
            >
              <IconifyIcon icon="lucide:x" />
            </Button>
          </div>
        </div>
        <p class="file-upload-help">
          <Checkbox
            v-if="connection?.supportsOverwrite"
            v-model:checked="manager.overwrite.value"
            :disabled="uploading"
          >
            覆盖同名文件 </Checkbox
          ><span>同名文件默认不覆盖，失败的文件可重试。</span>
        </p>
      </Modal>
    </div>
  </Page>
</template>

<style scoped>
.file-manager {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  min-width: 0;
  height: 100%;
  min-height: 0;
  color: hsl(var(--foreground));
}

.file-statistics {
  display: grid;
  grid-template-columns: minmax(280px, 1.5fr) repeat(4, minmax(0, 1fr)) auto;
  align-items: center;
  min-height: 76px;
}

.connection-summary {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  padding-right: 16px;
}

.connection-summary > div {
  display: flex;
  align-items: center;
  min-width: 0;
}

.connection-summary > div > span:first-child,
.connection-status {
  flex-shrink: 0;
  color: hsl(var(--muted-foreground));
}

.connection-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: hsl(var(--primary));
  white-space: nowrap;
}

.connection-status {
  margin-left: 10px;
}

.connection-address {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.statistic-spin {
  min-width: 0;
}

.statistic-item {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  min-height: 50px;
  padding: 0 12px;
  border-left: 1px solid hsl(var(--border));
}

.statistic-item img {
  flex-shrink: 0;
  width: 50px;
  height: 50px;
  object-fit: contain;
}

.statistic-item > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.statistic-item span {
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
}

.statistic-item strong {
  font-size: 24px;
  font-weight: 400;
  white-space: nowrap;
}

.connection-switch {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  min-height: 50px;
  padding-left: 16px;
  border-left: 1px solid hsl(var(--border));
}

.file-workspace {
  position: relative;
  display: grid;
  flex: 1;
  grid-template-columns: minmax(220px, 16.6667%) 12px minmax(0, 1fr);
  gap: 0;
  min-width: 0;
  min-height: 0;
}

.file-workspace--collapsed {
  grid-template-columns: 12px minmax(0, 1fr);
}

.file-tree-panel {
  min-width: 0;
  height: 100%;
  overflow: hidden;
}

.file-tree-panel :deep(.ant-input-affix-wrapper) {
  flex-shrink: 0;
}

.file-tree-panel :deep(.ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.file-tree-panel :deep(.ant-tree-title) {
  display: block;
}

.file-tree-title {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.file-folder-icon {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.file-tree-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  white-space: nowrap;
}

.file-tree-more {
  flex-shrink: 0;
  width: 20px;
  height: 22px;
  opacity: 0;
}

.file-tree-title:hover .file-tree-more,
.file-tree-more:focus-visible {
  opacity: 1;
}

.file-list-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.file-toolbar-actions,
.file-toolbar-tools {
  display: inline-flex;
  gap: 8px;
  align-items: center;
}

.file-toolbar-tools {
  margin-left: 8px;
}

.file-name-link {
  max-width: 100%;
  color: hsl(var(--primary));
  overflow-wrap: anywhere;
  white-space: normal;
}

.file-location {
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  align-items: center;
  padding: 8px 12px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.file-location button {
  display: flex;
  flex-shrink: 0;
  gap: 5px;
  align-items: center;
  color: hsl(var(--primary));
}

.file-location > span {
  overflow: hidden;
  text-overflow: ellipsis;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
}

.file-name-form {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 20px 20px 30px;
}

.file-name-form label {
  padding-top: 6px;
  text-align: right;
}

.file-name-form label span {
  margin-right: 5px;
  color: #f56c6c;
}

.file-error {
  margin: 6px 0 0;
  color: #e55656;
}

.file-preview-text {
  min-height: 280px;
  max-height: 65vh;
  padding: 15px;
  margin: 0;
  overflow: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 35%);
}

.file-preview-placeholder {
  min-height: 280px;
}

.file-preview-image {
  display: block;
  max-width: 100%;
  max-height: 65vh;
  margin: auto;
  object-fit: contain;
}

.file-preview-pdf {
  width: 100%;
  height: 65vh;
  border: 0;
}

.file-upload-location {
  display: flex;
  gap: 20px;
  align-items: center;
  padding: 10px 0 20px;
}

.file-upload-location span {
  flex-shrink: 0;
}

.file-input-hidden {
  display: none;
}

.file-upload-drop {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 185px;
  padding: 20px;
  color: hsl(var(--foreground));
  background: hsl(var(--muted) / 35%);
  border: 1px dashed hsl(var(--border));
}

.file-upload-drop > .iconify {
  width: 45px;
  height: 45px;
  color: hsl(var(--primary));
}

.file-upload-drop strong {
  font-size: 14px;
  font-weight: 400;
}

.file-upload-drop span,
.file-upload-help {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.file-upload-queue > div {
  display: flex;
  gap: 15px;
  align-items: center;
  min-height: 44px;
  border-bottom: 1px solid hsl(var(--border));
}

.file-upload-queue > div > span:first-child {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-upload-help {
  margin: 16px 0 0;
}

@media (width <= 1440px) {
  .file-statistics {
    grid-template-columns: minmax(260px, 2fr) repeat(4, minmax(0, 1fr));
    row-gap: 12px;
  }

  .connection-switch {
    grid-column: 1 / -1;
    min-height: 32px;
    padding-left: 0;
    border-left: 0;
  }
}

@media (width <= 767px) {
  .file-manager {
    height: auto;
    min-height: 100%;
  }

  .file-statistics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }

  .connection-summary,
  .connection-switch {
    grid-column: 1 / -1;
  }

  .statistic-item {
    justify-content: flex-start;
    padding: 0;
    border-left: 0;
  }

  .file-workspace {
    display: flex;
    flex: none;
    flex-direction: column;
  }

  .file-tree-panel {
    height: 220px;
  }

  .file-tree-panel :deep(.file-empty--compact) {
    min-height: 150px;
  }

  .file-tree-panel :deep(.file-empty--compact img) {
    width: 90px;
  }

  .file-list-panel {
    height: 600px;
  }

  .file-toolbar-actions {
    flex-wrap: wrap;
  }

  .file-name-form {
    grid-template-columns: minmax(0, 1fr);
    padding: 10px 0 20px;
  }

  .file-name-form label {
    text-align: left;
  }

  .file-upload-queue > div {
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px 0;
  }

  .file-upload-queue > div > span:first-child {
    flex-basis: 100%;
  }
}

@media (max-width: 767px) {
  .file-workspace > :deep(.sidebar-collapse) {
    flex: 0 0 12px;
    width: 100%;
    min-width: 0;
    height: 12px;
  }

  .file-workspace > :deep(.sidebar-collapse svg) {
    transform: rotate(90deg);
  }
}

@media (min-width: 641px) and (max-width: 767px) {
  .file-workspace > :deep(.sidebar-collapse .sidebar-collapse__button),
  .file-workspace
    > :deep(.sidebar-collapse.is-collapsed .sidebar-collapse__button) {
    top: -8px;
    right: 4px;
    left: auto;
    width: 24px;
    height: 24px;
  }
}
</style>
