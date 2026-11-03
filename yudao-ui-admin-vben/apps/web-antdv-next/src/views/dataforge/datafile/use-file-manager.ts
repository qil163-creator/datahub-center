import type { FileTreeNode } from './data';

import type { DataforgeDataFileApi as Api } from '#/api/dataforge/datafile';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue';
import { useRoute } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { downloadFileFromBlobPart } from '@vben/utils';

import { message } from 'antdv-next';

import {
  createFileDirectory,
  deleteDataFiles,
  downloadDataFile,
  downloadDataFiles,
  getDataFilePage,
  getFileConnections,
  getFileDirectories,
  getFileSummary,
  previewDataFile,
  previewDataFileContent,
  renameDataFile,
  uploadDataFile,
} from '#/api/dataforge/datafile';

import {
  filterDirectoryTree,
  MAX_BATCH_FILES,
  MAX_UPLOAD_BYTES,
  parentPath,
  validateFileName,
} from './data';

export interface UploadItem {
  file: File;
  state: 'error' | 'pending' | 'success' | 'uploading';
  message?: string;
}

/** 远端文件工作台：连接、会话与页面生命周期共同约束异步结果。 */
export function useFileManager() {
  const access = useAccessStore();
  const user = useUserStore();
  const route = useRoute();
  const { hasAccessByCodes } = useAccess();
  const canQuery = computed(() =>
    hasAccessByCodes(['dataforge:data-file:query']),
  );
  const canCreate = computed(() =>
    hasAccessByCodes(['dataforge:data-file:create']),
  );
  const canUpdate = computed(() =>
    hasAccessByCodes(['dataforge:data-file:update']),
  );
  const canDelete = computed(() =>
    hasAccessByCodes(['dataforge:data-file:delete']),
  );
  const canDownload = computed(() =>
    hasAccessByCodes(['dataforge:data-file:download']),
  );
  const identity = computed(() =>
    JSON.stringify([
      access.accessToken,
      access.refreshToken,
      access.accessCodes,
      user.userInfo?.userId,
      user.userRoles,
    ]),
  );
  const connections = ref<Api.Connection[]>([]);
  const connection = ref<Api.Connection>();
  const connectionOpen = ref(false);
  const connectionLoading = ref(false);
  const connectionError = ref(false);
  const summary = ref<Api.Summary>();
  const summaryLoading = ref(false);
  const summaryError = ref(false);
  const path = ref('/');
  const name = ref('');
  const type = ref<string>();
  const order = ref<'asc' | 'desc'>();
  const appliedName = ref('');
  const appliedType = ref<string>();
  const rows = ref<Api.Entry[]>([]);
  const total = ref(0);
  const pageNo = ref(1);
  const pageSize = ref(10);
  const loading = ref(false);
  const listError = ref(false);
  const selected = ref<Api.Entry[]>([]);
  const directoryFilter = ref('');
  const expandedKeys = ref<string[]>(['/']);
  const children = ref(new Map<string, Api.Directory[]>());
  const treeLoading = ref(false);
  const treeError = ref(false);
  const directoryOperation = ref<'expand' | 'refresh'>();
  const busy = ref(false);
  const failures = ref<Api.DeleteResult['failures']>([]);
  const editOpen = ref(false);
  const editMode = ref<'mkdir' | 'rename'>('rename');
  const editPath = ref('/');
  const editName = ref('');
  const originalName = ref('');
  const editError = ref('');
  const previewOpen = ref(false);
  const preview = ref<Api.Preview>();
  const previewUrl = ref('');
  const previewLoading = ref(false);
  const previewError = ref(false);
  const uploadOpen = ref(false);
  const batchUpload = ref(false);
  const uploads = ref<UploadItem[]>([]);
  const uploading = ref(false);
  const uploadPath = ref('/');
  const overwrite = ref(false);
  let epoch = 0;
  let active = true;
  let listSequence = 0;
  let connectionSequence = 0;
  let summarySequence = 0;
  let previewSequence = 0;
  let treeSequence = 0;
  let directoryOperationSequence = 0;
  const directoryRequests = new Map<string, number>();

  function releasePreview() {
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
    previewUrl.value = '';
  }

  function currentScope() {
    const generation = epoch;
    const owner = identity.value;
    const id = connection.value?.id;
    return () =>
      active &&
      epoch === generation &&
      identity.value === owner &&
      String(connection.value?.id) === String(id);
  }
  const tree = computed<FileTreeNode[]>(() => {
    if (!connection.value) return [];
    const build = (
      location: string,
      title: string,
      ancestors = new Set<string>(),
    ): FileTreeNode => ({
      key: location,
      title,
      isLeaf:
        children.value.has(location) &&
        children.value.get(location)?.length === 0,
      ...(children.value.has(location)
        ? {
            children: children.value
              .get(location)!
              .filter(
                (item) => item.path !== location && !ancestors.has(item.path),
              )
              .map((item) =>
                build(item.path, item.name, new Set([...ancestors, location])),
              ),
          }
        : {}),
    });
    return filterDirectoryTree([build('/', '/')], directoryFilter.value);
  });
  const allDirectoriesExpanded = computed(() => {
    if (!connection.value) return false;
    const pending = ['/'];
    const seen = new Set<string>();
    const expanded = new Set(expandedKeys.value);
    let hasBranch = false;
    for (const location of pending) {
      if (seen.has(location)) continue;
      seen.add(location);
      const entries = children.value.get(location);
      if (!entries) return false;
      if (entries.length > 0) {
        hasBranch = true;
        if (!expanded.has(location)) return false;
        pending.push(...entries.map((entry) => entry.path));
      }
    }
    return hasBranch;
  });

  function clearContent() {
    ++epoch;
    ++directoryOperationSequence;
    directoryOperation.value = undefined;
    rows.value = [];
    total.value = 0;
    selected.value = [];
    summary.value = undefined;
    children.value = new Map();
    directoryRequests.clear();
    path.value = '/';
    expandedKeys.value = ['/'];
    directoryFilter.value = '';
    listError.value = false;
    summaryError.value = false;
    treeError.value = false;
    loading.value = false;
    treeLoading.value = false;
    summaryLoading.value = false;
    editOpen.value = false;
    previewOpen.value = false;
    preview.value = undefined;
    releasePreview();
    previewLoading.value = false;
    uploadOpen.value = false;
    uploading.value = false;
    uploads.value = [];
    busy.value = false;
    failures.value = [];
  }

  async function loadConnections() {
    const owner = identity.value;
    const sequence = ++connectionSequence;
    const valid = () =>
      active && sequence === connectionSequence && identity.value === owner;
    connectionLoading.value = true;
    connectionError.value = false;
    try {
      if (!canQuery.value) return;
      const result = await getFileConnections();
      if (!valid()) return;
      connections.value = result;
      const queryId = route.query.datasourceId;
      if (!connection.value && typeof queryId === 'string') {
        const match = result.find((item) => String(item.id) === queryId);
        if (match) await selectConnection(match);
      }
    } catch {
      if (valid()) connectionError.value = true;
    } finally {
      if (valid()) connectionLoading.value = false;
    }
  }

  async function loadList(reset = false) {
    if (reset) pageNo.value = 1;
    const scope = currentScope();
    const sequence = ++listSequence;
    const valid = () => scope() && sequence === listSequence;
    rows.value = [];
    total.value = 0;
    selected.value = [];
    loading.value = !!connection.value && canQuery.value;
    listError.value = false;
    if (!connection.value || !canQuery.value) return;
    try {
      const response = await getDataFilePage({
        connectionId: connection.value.id,
        path: path.value,
        name: appliedName.value || undefined,
        type: appliedType.value,
        order: order.value,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
      });
      if (!valid()) return;
      rows.value = response.list;
      total.value = response.total;
    } catch {
      if (valid()) listError.value = true;
    } finally {
      if (valid()) loading.value = false;
    }
  }

  async function loadSummary() {
    if (!connection.value || !canQuery.value) return;
    const scope = currentScope();
    const sequence = ++summarySequence;
    const valid = () => scope() && sequence === summarySequence;
    summaryLoading.value = true;
    summaryError.value = false;
    try {
      const response = await getFileSummary(connection.value.id);
      if (valid()) summary.value = response;
    } catch {
      if (valid()) summaryError.value = true;
    } finally {
      if (valid()) summaryLoading.value = false;
    }
  }

  async function loadDirectory(location: string) {
    if (!connection.value || !canQuery.value) return;
    const scope = currentScope();
    const sequence = ++treeSequence;
    directoryRequests.set(location, sequence);
    const valid = () => scope() && directoryRequests.get(location) === sequence;
    treeLoading.value = true;
    treeError.value = false;
    try {
      const response = await getFileDirectories({
        connectionId: connection.value.id,
        path: location,
      });
      if (!valid()) return;
      children.value = new Map(children.value).set(location, response);
      return response;
    } catch {
      if (valid()) treeError.value = true;
    } finally {
      if (valid()) {
        directoryRequests.delete(location);
        treeLoading.value = directoryRequests.size > 0;
      }
    }
  }

  async function toggleExpandDirectories() {
    if (directoryOperation.value === 'expand' || allDirectoriesExpanded.value) {
      ++directoryOperationSequence;
      directoryOperation.value = undefined;
      expandedKeys.value = [];
      return;
    }
    if (!connection.value || !canQuery.value || directoryOperation.value) return;
    const scope = currentScope();
    const sequence = ++directoryOperationSequence;
    const valid = () => scope() && sequence === directoryOperationSequence;
    directoryOperation.value = 'expand';
    const pending = ['/'];
    const seen = new Set<string>();
    try {
      // 逐层读取；收起、切换连接或离开页面后不再追加请求和展开键。
      for (const location of pending) {
        if (!valid()) return;
        if (seen.has(location)) continue;
        seen.add(location);
        const entries =
          children.value.get(location) ?? (await loadDirectory(location));
        if (!valid() || !entries) return;
        if (entries.length > 0) {
          expandedKeys.value = [...new Set([...expandedKeys.value, location])];
          pending.push(...entries.map((entry) => entry.path));
        }
      }
    } finally {
      if (valid()) directoryOperation.value = undefined;
    }
  }

  async function refreshDirectories() {
    if (!connection.value || !canQuery.value) return;
    const scope = currentScope();
    const sequence = ++directoryOperationSequence;
    const valid = () => scope() && sequence === directoryOperationSequence;
    const loaded = new Set(children.value.keys());
    directoryRequests.clear();
    directoryOperation.value = 'refresh';
    const pending = ['/'];
    const seen = new Set<string>();
    try {
      for (const location of pending) {
        if (!valid()) return;
        if (seen.has(location)) continue;
        seen.add(location);
        const entries = await loadDirectory(location);
        if (!valid() || !entries) return;
        pending.push(
          ...entries
            .filter((entry) => loaded.has(entry.path))
            .map((entry) => entry.path),
        );
      }
    } finally {
      if (valid()) directoryOperation.value = undefined;
    }
  }

  async function selectConnection(value: Api.Connection) {
    if (busy.value || uploading.value) return;
    clearContent();
    name.value = '';
    type.value = undefined;
    appliedName.value = '';
    appliedType.value = undefined;
    connection.value = value;
    connectionOpen.value = false;
    await Promise.all([loadList(true), loadSummary(), loadDirectory('/')]);
  }
  async function refresh() {
    ++directoryOperationSequence;
    directoryOperation.value = undefined;
    children.value = new Map();
    directoryRequests.clear();
    const ancestors = path.value.split('/').filter(Boolean);
    const directories = [
      '/',
      ...ancestors.map(
        (_, index) => `/${ancestors.slice(0, index + 1).join('/')}`,
      ),
    ];
    expandedKeys.value = directories;
    await Promise.all([
      loadList(),
      loadSummary(),
      ...directories.map((location) => loadDirectory(location)),
    ]);
  }
  async function navigate(location: string) {
    if (busy.value || uploading.value || !connection.value) return;
    path.value = location;
    expandedKeys.value = [
      ...new Set([...expandedKeys.value, location, parentPath(location)]),
    ];
    await Promise.all([
      loadList(true),
      children.value.has(location)
        ? Promise.resolve()
        : loadDirectory(location),
    ]);
  }
  async function search(reset = false) {
    if (reset) {
      name.value = '';
      type.value = undefined;
    }
    appliedName.value = name.value.trim();
    appliedType.value = type.value;
    await loadList(true);
  }
  async function previewEntry(entry: Api.Entry) {
    if (entry.directory) return navigate(entry.path);
    if (!connection.value) return;
    const scope = currentScope();
    const sequence = ++previewSequence;
    const valid = () =>
      scope() && sequence === previewSequence && previewOpen.value;
    preview.value = undefined;
    releasePreview();
    previewError.value = false;
    previewOpen.value = true;
    previewLoading.value = true;
    try {
      const response = await previewDataFile({
        connectionId: connection.value.id,
        path: entry.path,
      });
      if (!valid()) return;
      if (response.type === 'IMAGE' || response.type === 'PDF') {
        const blob = await previewDataFileContent({
          connectionId: connection.value.id,
          path: entry.path,
        });
        if (!valid()) return;
        const allowed =
          response.type === 'PDF'
            ? blob.type === 'application/pdf'
            : /^image\/(png|jpeg|gif|webp|bmp)$/.test(blob.type);
        if (!allowed || blob.size > 20 * 1024 * 1024) {
          previewError.value = true;
          return;
        }
        previewUrl.value = URL.createObjectURL(blob);
      }
      preview.value = response;
    } catch {
      if (valid()) previewError.value = true;
    } finally {
      if (valid()) previewLoading.value = false;
    }
  }
  async function download(entries: Api.Entry[]) {
    if (
      !connection.value ||
      busy.value ||
      entries.length === 0 ||
      !canDownload.value
    )
      return;
    if (entries.length > 100) {
      message.warning('一次最多下载 100 项');
      return;
    }
    const scope = currentScope();
    busy.value = true;
    try {
      const first = entries[0]!;
      const blob = await (entries.length === 1
        ? downloadDataFile({
            connectionId: connection.value.id,
            path: first.path,
          })
        : downloadDataFiles({
            connectionId: connection.value.id,
            paths: entries.map((item) => item.path),
          }));
      if (scope())
        downloadFileFromBlobPart({
          fileName:
            entries.length > 1
              ? '文件打包下载.zip'
              : `${first.name}${first.directory ? '.zip' : ''}`,
          source: blob,
        });
    } catch {
      // 下载错误由统一请求客户端显示，保留页面选择以便重试。
    } finally {
      if (scope()) busy.value = false;
    }
  }
  function openRename(location: string, title: string) {
    if (!canUpdate.value || busy.value || location === '/') return;
    editMode.value = 'rename';
    editPath.value = location;
    editName.value = title;
    originalName.value = title;
    editError.value = '';
    editOpen.value = true;
  }
  function openMkdir(location: string) {
    if (!canCreate.value || busy.value) return;
    editMode.value = 'mkdir';
    editPath.value = location;
    editName.value = '';
    originalName.value = '';
    editError.value = '';
    editOpen.value = true;
  }
  async function saveName() {
    if (!connection.value || busy.value) return;
    const value = editName.value.trim();
    editError.value = validateFileName(value);
    if (editError.value) return;
    if (editMode.value === 'rename' && value === originalName.value) {
      editOpen.value = false;
      return;
    }
    const scope = currentScope();
    busy.value = true;
    try {
      const payload = {
        connectionId: connection.value.id,
        path: editPath.value,
        name: value,
      };
      await (editMode.value === 'rename'
        ? renameDataFile(payload)
        : createFileDirectory(payload));
      if (!scope()) return;
      if (
        editMode.value === 'rename' &&
        (path.value === editPath.value ||
          path.value.startsWith(`${editPath.value}/`))
      ) {
        const parent = parentPath(editPath.value);
        path.value = `${parent === '/' ? '' : parent}/${value}${path.value.slice(editPath.value.length)}`;
      }
      editOpen.value = false;
      message.success(
        editMode.value === 'rename' ? '重命名成功' : '文件夹创建成功',
      );
      await refresh();
    } catch {
      if (scope()) editError.value = '保存失败，请检查名称或连接后重试';
    } finally {
      if (scope()) busy.value = false;
    }
  }
  async function remove(entries: { name: string; path: string }[]) {
    if (
      !connection.value ||
      busy.value ||
      entries.length === 0 ||
      !canDelete.value
    )
      return;
    const scope = currentScope();
    const connectionId = connection.value.id;
    busy.value = true;
    try {
      await confirm(
        `确认删除${entries.length === 1 ? `「${entries[0]!.name}」` : `选中的 ${entries.length} 项`}吗？文件夹及其内容将一并删除。`,
      );
    } catch {
      if (scope()) busy.value = false;
      return;
    }
    if (!scope()) return;
    failures.value = [];
    try {
      const response = await deleteDataFiles({
        connectionId,
        paths: entries.map((item) => item.path),
      });
      if (!scope()) return;
      failures.value = response.failures;
      if (response.succeededPaths.length > 0) {
        message.success(`已删除 ${response.succeededPaths.length} 项`);
        const deletedParent = response.succeededPaths.find(
          (item) => path.value === item || path.value.startsWith(`${item}/`),
        );
        if (deletedParent) path.value = parentPath(deletedParent);
      }
      await refresh();
    } catch {
      // 请求失败时维持远端数据的真实状态，允许再次刷新核实。
    } finally {
      if (scope()) busy.value = false;
    }
  }
  function openUpload(batch: boolean) {
    if (!connection.value || busy.value || !canCreate.value) return;
    uploadPath.value = path.value;
    batchUpload.value = batch;
    uploads.value = [];
    overwrite.value = false;
    uploadOpen.value = true;
  }
  function addFiles(files: File[]) {
    if (uploading.value) return;
    const combined = batchUpload.value
      ? [...uploads.value.map((item) => item.file), ...files]
      : files.slice(0, 1);
    if (combined.length > MAX_BATCH_FILES) {
      message.warning(`单批最多上传 ${MAX_BATCH_FILES} 个文件`);
      return;
    }
    if (
      combined.some((file) => file.size > MAX_UPLOAD_BYTES) ||
      combined.reduce((sum, file) => sum + file.size, 0) > MAX_UPLOAD_BYTES
    ) {
      message.warning('单批文件总大小不能超过 500 MB');
      return;
    }
    if (new Set(combined.map((file) => file.name)).size !== combined.length) {
      message.warning('同一批次不能包含同名文件');
      return;
    }
    uploads.value = combined.map(
      (file) =>
        uploads.value.find((item) => item.file === file) ?? {
          file,
          state: 'pending',
        },
    );
  }
  async function submitUploads() {
    if (!connection.value || uploading.value || !canCreate.value) return;
    const pending = uploads.value.filter((item) => item.state !== 'success');
    if (pending.length === 0) {
      message.warning('请选择需要上传的文件');
      return;
    }
    const scope = currentScope();
    const location = {
      connectionId: connection.value.id,
      path: uploadPath.value,
    };
    uploading.value = true;
    try {
      for (const item of pending) {
        if (!scope() || !uploadOpen.value) break;
        item.state = 'uploading';
        item.message = undefined;
        try {
          await uploadDataFile(
            item.file,
            location,
            overwrite.value && !!connection.value?.supportsOverwrite,
          );
          if (scope()) item.state = 'success';
        } catch {
          if (scope()) {
            item.state = 'error';
            item.message = '上传失败，可重试';
          }
        }
      }
      if (!scope()) return;
      const succeeded = uploads.value.filter(
        (item) => item.state === 'success',
      ).length;
      if (succeeded) message.success(`已成功上传 ${succeeded} 个文件`);
      await refresh();
    } finally {
      if (scope()) uploading.value = false;
    }
  }
  watch(previewOpen, (open) => {
    if (!open) {
      ++previewSequence;
      previewLoading.value = false;
      preview.value = undefined;
      releasePreview();
    }
  });
  watch(
    identity,
    () => {
      clearContent();
      connection.value = undefined;
      connections.value = [];
      connectionOpen.value = false;
      connectionLoading.value = false;
      void loadConnections();
    },
    { flush: 'sync' },
  );
  watch(
    () => route.query.datasourceId,
    (value) => {
      if (typeof value !== 'string') return;
      const match = connections.value.find((item) => String(item.id) === value);
      if (match && String(connection.value?.id) !== value)
        void selectConnection(match);
    },
  );
  onMounted(() => {
    void loadConnections();
  });
  onActivated(() => {
    if (!active) {
      active = true;
      void loadConnections();
      if (connection.value) void refresh();
    }
  });
  onDeactivated(() => {
    active = false;
    clearContent();
  });
  onBeforeUnmount(() => {
    active = false;
    clearContent();
  });

  return {
    canQuery,
    canCreate,
    canUpdate,
    canDelete,
    canDownload,
    connections,
    connection,
    connectionOpen,
    connectionLoading,
    connectionError,
    loadConnections,
    selectConnection,
    summary,
    summaryLoading,
    summaryError,
    path,
    name,
    type,
    order,
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
    treeError,
    directoryOperation,
    allDirectoriesExpanded,
    busy,
    failures,
    loadList,
    loadDirectory,
    refreshDirectories,
    toggleExpandDirectories,
    refresh,
    navigate,
    search,
    previewEntry,
    download,
    openRename,
    openMkdir,
    remove,
    editOpen,
    editMode,
    editName,
    editError,
    saveName,
    previewOpen,
    preview,
    previewUrl,
    previewLoading,
    previewError,
    uploadOpen,
    batchUpload,
    uploads,
    uploading,
    uploadPath,
    overwrite,
    openUpload,
    addFiles,
    submitUploads,
  };
}
