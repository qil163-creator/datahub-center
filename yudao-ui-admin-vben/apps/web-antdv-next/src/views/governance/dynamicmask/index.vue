<script lang="ts" setup>
import type { GovernanceMetaObjectApi } from '#/api/governance/metaobject';
import type { SafetySecurityApi } from '#/api/governance/security';
import type { SystemUserApi } from '#/api/system/user';

import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Input,
  message,
  Modal,
  Select,
  Spin,
  Switch,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import { getMetaTablePage } from '#/api/governance/metaobject';
import {
  getMaskSceneList,
  previewMaskPolicy,
  updateMaskScene,
} from '#/api/governance/security';
import { getSimpleUserList } from '#/api/system/user';
import { createLatestWins } from '#/utils/resource-id';

import {
  disableRemarkError,
  DYNAMIC_MASK_QUERY,
  DYNAMIC_MASK_UPDATE,
  MASK_SCENE_OPTIONS,
  reasonView,
  sceneLabel,
  whitelistSceneLabel,
} from './data';

defineOptions({ name: 'SafetyDynamicMask' });

const { hasAccessByCodes } = useAccess();
const canQuery = computed(() => hasAccessByCodes([DYNAMIC_MASK_QUERY]));
const canUpdate = computed(() => hasAccessByCodes([DYNAMIC_MASK_UPDATE]));

// ==================== 场景开关 ====================

const scenes = ref<SafetySecurityApi.MaskScene[]>([]);
const scenesLoading = ref(false);
const sceneGuard = createLatestWins();
const pendingScenes = reactive(new Set<string>());

const sceneColumns = [
  { title: '场景', key: 'scene', width: 160 },
  { title: '豁免口径', key: 'whitelistScene', width: 120 },
  { title: '动态脱敏', key: 'enabled', width: 120 },
  { title: '备注', key: 'remark', ellipsis: true },
];

async function loadScenes() {
  if (!canQuery.value) return;
  const ticket = sceneGuard.next();
  scenesLoading.value = true;
  try {
    const list = await getMaskSceneList();
    if (ticket.isCurrent()) scenes.value = list ?? [];
  } finally {
    if (ticket.isCurrent()) scenesLoading.value = false;
  }
}

const disableTarget = ref<SafetySecurityApi.MaskScene>();
const disableRemark = ref('');
const disableError = ref<string>();

function changeScene(scene: SafetySecurityApi.MaskScene, enabled: boolean) {
  if (!enabled) {
    disableTarget.value = scene;
    disableRemark.value = '';
    disableError.value = undefined;
    return;
  }
  void submitScene(scene, true, '');
}

async function confirmDisable() {
  const scene = disableTarget.value;
  if (!scene) return;
  disableError.value = disableRemarkError(disableRemark.value);
  if (disableError.value) return;
  const done = await submitScene(scene, false, disableRemark.value.trim());
  if (done) disableTarget.value = undefined;
}

async function submitScene(
  scene: SafetySecurityApi.MaskScene,
  enabled: boolean,
  remark: string,
): Promise<boolean> {
  if (pendingScenes.has(scene.sceneCode)) return false;
  pendingScenes.add(scene.sceneCode);
  try {
    await updateMaskScene({
      sceneCode: scene.sceneCode,
      enabled,
      remark,
      expectedRevision: scene.revision ?? 0,
    });
    message.success(enabled ? '已启用动态脱敏' : '已关闭动态脱敏');
    return true;
  } catch {
    // 统一错误提示由请求客户端处理；修订冲突时刷新拿最新状态。
    return false;
  } finally {
    pendingScenes.delete(scene.sceneCode);
    await loadScenes();
  }
}

// ==================== 策略试算 ====================

const users = ref<SystemUserApi.User[]>([]);
const userOptions = computed(() =>
  users.value
    .filter((user) => user.id !== undefined && user.id !== null)
    .map((user) => ({
      label: user.deptName
        ? `${user.nickname}（${user.deptName}）`
        : user.nickname,
      value: String(user.id),
    })),
);

const tableOptions = ref<{ label: string; value: string }[]>([]);
const tableSearching = ref(false);
const tableGuard = createLatestWins();
let tableTimer: ReturnType<typeof setTimeout> | undefined;

function tableLabel(table: GovernanceMetaObjectApi.MetaTable): string {
  const location = [table.datasourceName, table.dbName]
    .filter(Boolean)
    .join(' / ');
  const name = table.businessName
    ? `${table.tableName}（${table.businessName}）`
    : (table.tableName ?? '');
  return location ? `${name} · ${location}` : name;
}

function searchTables(keyword: string) {
  if (tableTimer) clearTimeout(tableTimer);
  tableTimer = setTimeout(() => void runTableSearch(keyword.trim()), 300);
}

async function runTableSearch(keyword: string) {
  const ticket = tableGuard.next();
  tableSearching.value = true;
  try {
    const page = await getMetaTablePage({
      pageNo: 1,
      pageSize: 20,
      tableName: keyword || undefined,
    });
    if (!ticket.isCurrent()) return;
    tableOptions.value = (page?.list ?? [])
      .filter((table) => table.id !== undefined && table.id !== null)
      .map((table) => ({ label: tableLabel(table), value: String(table.id) }));
  } catch {
    if (ticket.isCurrent()) tableOptions.value = [];
  } finally {
    if (ticket.isCurrent()) tableSearching.value = false;
  }
}

const previewForm = reactive<{
  metaTableId?: string;
  sampleValue: string;
  sceneCode: string;
  userId?: string;
}>({ sampleValue: '', sceneCode: 'DATA_QUERY' });
const previewResult = ref<SafetySecurityApi.MaskPreview>();
const previewLoading = ref(false);
const previewGuard = createLatestWins();
const canPreview = computed(
  () =>
    !!previewForm.userId &&
    !!previewForm.metaTableId &&
    !!previewForm.sceneCode,
);

const previewColumns = [
  { title: '字段', dataIndex: 'columnName', key: 'columnName', width: 200 },
  { title: '处理', key: 'masked', width: 100 },
  { title: '原因', key: 'reason', width: 200 },
  { title: '分类', key: 'categoryNames' },
  {
    title: '示例效果',
    dataIndex: 'sampleResult',
    key: 'sampleResult',
    width: 220,
  },
];

function invalidatePreview() {
  previewGuard.invalidate();
  previewResult.value = undefined;
  previewLoading.value = false;
}

async function runPreview() {
  if (!canPreview.value) return;
  const ticket = previewGuard.next();
  previewLoading.value = true;
  try {
    const result = await previewMaskPolicy({
      userId: previewForm.userId!,
      sceneCode: previewForm.sceneCode,
      metaTableId: previewForm.metaTableId!,
      sampleValue: previewForm.sampleValue.trim() || undefined,
    });
    if (ticket.isCurrent()) previewResult.value = result;
  } catch {
    if (ticket.isCurrent()) previewResult.value = undefined;
  } finally {
    if (ticket.isCurrent()) previewLoading.value = false;
  }
}

const maskedCount = computed(
  () =>
    previewResult.value?.columns.filter((column) => column.masked).length ?? 0,
);

onMounted(async () => {
  if (!canQuery.value) return;
  void loadScenes();
  void runTableSearch('');
  try {
    users.value = (await getSimpleUserList()) ?? [];
  } catch {
    users.value = [];
  }
});

onBeforeUnmount(() => {
  if (tableTimer) clearTimeout(tableTimer);
  sceneGuard.invalidate();
  tableGuard.invalidate();
  previewGuard.invalidate();
});
</script>

<template>
  <Page auto-content-height>
    <Alert
      v-if="!canQuery"
      type="warning"
      show-icon
      message="当前账号没有动态脱敏查询权限"
    />
    <div v-else class="flex flex-col gap-4">
      <Card title="场景开关" size="small">
        <template #extra>
          <Button size="small" :loading="scenesLoading" @click="loadScenes">
            刷新
          </Button>
        </template>
        <Alert
          class="mb-3"
          type="info"
          show-icon
          message="开启后，该场景返回的数据按“字段分类 → 脱敏规则”处理，白名单用户按豁免口径查看原值；无法确认字段来源或策略时整体打码。"
        />
        <Table
          :columns="sceneColumns"
          :data-source="scenes"
          :loading="scenesLoading"
          :pagination="false"
          row-key="sceneCode"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'scene'">
              {{ sceneLabel(record.sceneCode, record.sceneName) }}
            </template>
            <template v-else-if="column.key === 'whitelistScene'">
              {{ whitelistSceneLabel(record.whitelistScene) }}
            </template>
            <template v-else-if="column.key === 'enabled'">
              <Switch
                :checked="record.enabled"
                :disabled="!canUpdate"
                :loading="pendingScenes.has(record.sceneCode)"
                checked-children="开"
                un-checked-children="关"
                @change="(checked) => changeScene(record, !!checked)"
              />
            </template>
            <template v-else-if="column.key === 'remark'">
              {{ record.remark || '-' }}
            </template>
          </template>
        </Table>
      </Card>

      <Card title="策略试算" size="small">
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
          <Select
            v-model:value="previewForm.userId"
            :options="userOptions"
            allow-clear
            option-filter-prop="label"
            placeholder="选择用户"
            show-search
            @change="invalidatePreview"
          />
          <Select
            v-model:value="previewForm.sceneCode"
            :options="[...MASK_SCENE_OPTIONS]"
            placeholder="选择场景"
            @change="invalidatePreview"
          />
          <Select
            v-model:value="previewForm.metaTableId"
            :filter-option="false"
            :loading="tableSearching"
            :options="tableOptions"
            allow-clear
            placeholder="搜索元数据表"
            show-search
            @change="invalidatePreview"
            @search="searchTables"
          />
          <Input
            v-model:value="previewForm.sampleValue"
            :maxlength="200"
            allow-clear
            placeholder="示例值（选填，如 13800138000）"
          />
        </div>
        <div class="mt-3 flex items-center gap-3">
          <Button
            type="primary"
            :disabled="!canPreview"
            :loading="previewLoading"
            @click="runPreview"
          >
            试算
          </Button>
          <span class="text-muted-foreground text-xs">
            试算只读取字段分类、白名单与规则，不读取业务数据。
          </span>
        </div>

        <Spin :spinning="previewLoading">
          <div v-if="previewResult" class="mt-4 flex flex-col gap-3">
            <div class="flex flex-wrap items-center gap-2">
              <Tag :color="previewResult.sceneEnabled ? 'green' : 'default'">
                场景{{ previewResult.sceneEnabled ? '已启用' : '已关闭' }}
              </Tag>
              <Tag v-if="previewResult.globallyExempt" color="blue">
                该用户在此场景全局豁免
              </Tag>
              <Tag color="orange">{{ maskedCount }} 个字段会被脱敏</Tag>
              <span
                v-if="previewResult.policyFingerprint"
                class="text-muted-foreground text-xs"
              >
                策略指纹 {{ previewResult.policyFingerprint }}
              </span>
            </div>
            <Table
              :columns="previewColumns"
              :data-source="previewResult.columns"
              :pagination="false"
              row-key="columnName"
              size="small"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'masked'">
                  <Tag :color="record.masked ? 'orange' : 'green'">
                    {{ record.masked ? '脱敏' : '原值' }}
                  </Tag>
                </template>
                <template v-else-if="column.key === 'reason'">
                  <Tag
                    v-if="record.reason"
                    :color="reasonView(record.reason).color"
                  >
                    {{ reasonView(record.reason).label }}
                  </Tag>
                  <span v-else>-</span>
                </template>
                <template v-else-if="column.key === 'categoryNames'">
                  {{
                    record.categoryNames?.length
                      ? record.categoryNames.join('、')
                      : '-'
                  }}
                </template>
                <template v-else-if="column.key === 'sampleResult'">
                  {{ record.sampleResult ?? '-' }}
                </template>
              </template>
            </Table>
          </div>
        </Spin>
      </Card>
    </div>

    <Modal
      :confirm-loading="
        !!disableTarget && pendingScenes.has(disableTarget.sceneCode)
      "
      :open="!!disableTarget"
      :title="`关闭「${sceneLabel(disableTarget?.sceneCode, disableTarget?.sceneName)}」动态脱敏`"
      ok-text="确认关闭"
      @cancel="disableTarget = undefined"
      @ok="confirmDisable"
    >
      <Alert
        class="mb-3"
        type="warning"
        show-icon
        message="关闭后该场景将返回原值，操作会记录到业务操作日志。"
      />
      <TextArea
        v-model:value="disableRemark"
        :maxlength="500"
        :rows="3"
        placeholder="请填写关闭原因（必填）"
        show-count
      />
      <div v-if="disableError" class="text-destructive mt-1 text-xs">
        {{ disableError }}
      </div>
    </Modal>
  </Page>
</template>
