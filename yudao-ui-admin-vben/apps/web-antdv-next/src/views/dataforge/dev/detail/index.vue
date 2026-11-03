<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, Empty, Skeleton } from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';
import { getDevDirList, getDevTask } from '#/api/dataforge/dev';
import { getSimpleUserList } from '#/api/system/user';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import { resolveDirectoryName, resolveUserName } from '../list-model';
import TaskDetailView from '../modules/task-detail-view.vue';
import { normalizeDevDatabaseType } from '../task-icon-model';
import { parseDevWorkloadMode } from '../workload-mode';
defineOptions({ name: 'DataforgeDevTaskDetail' });
const Workbench = defineAsyncComponent(() =>
  import('../workbench.vue').then((module) => module.default),
);

const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const taskId = computed(() =>
  route.name === 'DataforgeDevTaskDetail'
    ? parseResourceId(route.params.id)
    : undefined,
);
const canQuery = computed(() => hasAccessByCodes(['dataforge:dev-task:query']));
const workloadMode = computed(() =>
  parseDevWorkloadMode(route.query?.workloadMode),
);
const listPath = computed(() => {
  if (workloadMode.value === 'STREAMING') return '/develop/dev/realtime';
  if (workloadMode.value === 'BATCH') return '/develop/dev/offline';
  return '/develop/dev/workbench';
});
watch(
  listPath,
  (path) => {
    if (route.meta) route.meta.activePath = path;
  },
  { immediate: true },
);
const sessionKey = computed(
  () => `${access.accessToken}/${access.refreshToken}`,
);
const task = ref<DataforgeDevApi.Task>();
const dirs = ref<DataforgeDevApi.Dir[]>([]);
const users = ref<{ id: string; name: string }[]>([]);
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const databaseTypes = ref<FoundationDatabaseTypeApi.DatabaseType[]>([]);
const loading = ref(false);
const error = ref('');
const contextError = ref(false);
const guard = createLatestWins();
const directoryName = computed(() =>
  resolveDirectoryName(task.value?.dirId, dirs.value),
);
const ownerName = computed(
  () =>
    task.value?.ownerName || resolveUserName(task.value?.ownerId, users.value),
);
const creatorName = computed(() =>
  resolveUserName(task.value?.creator, users.value),
);
const databaseName = computed(
  () =>
    databases.value.find(
      (db) => String(db.id) === String(task.value?.databaseId),
    )?.name,
);
const databaseIconPath = computed(
  () =>
    databaseTypes.value.find(
      (type) =>
        normalizeDevDatabaseType(type.productId || type.typeCode) ===
        normalizeDevDatabaseType(task.value?.dbType),
    )?.iconPath,
);
const databaseIconPaths = computed(() =>
  Object.fromEntries(
    databaseTypes.value.flatMap((type) =>
      [type.productId, type.typeCode]
        .filter(Boolean)
        .map((code) => [normalizeDevDatabaseType(code), type.iconPath || '']),
    ),
  ),
);

function back() {
  void router.push(listPath.value);
}

async function loadDetail() {
  const request = guard.next();
  task.value = undefined;
  dirs.value = [];
  users.value = [];
  databases.value = [];
  databaseTypes.value = [];
  error.value = '';
  contextError.value = false;
  const id = taskId.value;
  loading.value = Boolean(id && canQuery.value);
  if (!id || !canQuery.value) return;
  try {
    const [detail, context] = await Promise.all([
      getDevTask(id),
      Promise.allSettled([
        getDevDirList(),
        getSimpleUserList(),
        getDatabaseSimpleList(),
        getDatabaseTypeSimpleList(),
      ]),
    ]);
    if (!request.isCurrent()) return;
    task.value = detail;
    const [directories, userList, sources, products] = context;
    if (directories.status === 'fulfilled') dirs.value = directories.value;
    if (userList.status === 'fulfilled')
      users.value = userList.value.map((user) => ({
        id: String(user.id),
        name: user.nickname,
      }));
    if (sources.status === 'fulfilled') databases.value = sources.value;
    if (products.status === 'fulfilled') databaseTypes.value = products.value;
    contextError.value = context.some((result) => result.status === 'rejected');
  } catch {
    if (request.isCurrent()) error.value = '任务详情加载失败，请重试。';
  } finally {
    if (request.isCurrent()) loading.value = false;
  }
}

watch(
  () => [taskId.value, sessionKey.value, canQuery.value],
  () => void loadDetail(),
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(() => guard.invalidate());
</script>

<template>
  <Workbench
    v-if="workloadMode && taskId && canQuery && task && !loading && !error"
    :key="`${sessionKey}/${taskId}/${workloadMode}`"
    :task-id="taskId"
    :database-icon-paths="databaseIconPaths"
    :workload-mode="workloadMode"
    read-only
    @back="back"
  />
  <Page v-else content-class="dev-task-detail-content">
    <div class="dev-task-detail-page">
      <section
        v-if="!taskId || !canQuery || loading || error || !task"
        class="bg-card rounded-lg p-5"
      >
        <div class="mb-5 flex items-center justify-between gap-3">
          <h1 class="m-0 text-lg font-semibold">数据开发详情</h1>
          <Button size="small" @click="back">
            <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
            返回
          </Button>
        </div>
        <Alert
          v-if="!taskId || !canQuery"
          :message="!taskId ? '无效的任务编号' : '暂无任务查询权限'"
          show-icon
          type="warning"
        />
        <Skeleton v-else-if="loading" active :paragraph="{ rows: 4 }" />
        <Alert v-else-if="error" :message="error" show-icon type="error">
          <template #action>
            <Button size="small" @click="loadDetail">
<template #icon>
<IconifyIcon icon="lucide:refresh-cw" />
</template>重试
</Button>
          </template>
        </Alert>
        <Empty v-else description="任务不存在或已删除" />
      </section>
      <template v-else>
        <Alert
          v-if="contextError"
          message="部分名称加载失败，相关字段保留编号。可重试加载。"
          show-icon
          type="warning"
        >
          <template #action>
            <Button size="small" @click="loadDetail">
<template #icon>
<IconifyIcon icon="lucide:refresh-cw" />
</template>重试
</Button>
          </template>
        </Alert>
        <TaskDetailView
          :key="`${sessionKey}/${taskId}`"
          :task="task"
          :directory-name="directoryName"
          :owner-name="ownerName"
          :creator-name="creatorName"
          :database-name="databaseName"
          :database-icon-path="databaseIconPath"
          presentation="page"
        >
          <template #headingExtra>
            <Button size="small" @click="back">
              <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
              返回
            </Button>
          </template>
        </TaskDetailView>
      </template>
    </div>
  </Page>
</template>

<style scoped>
.dev-task-detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
</style>
