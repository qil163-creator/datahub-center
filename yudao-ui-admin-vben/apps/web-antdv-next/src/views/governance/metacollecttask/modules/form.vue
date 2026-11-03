<script setup lang="ts">
import type { GovernanceMetaCollectTaskApi as Api } from '#/api/governance/metacollecttask';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';

import {
  Alert,
  Button,
  Input,
  message,
  RadioGroup,
  Select,
  TextArea,
  Transfer,
} from 'antdv-next';

import {
  createMetaCollectTask,
  getMetaCollectScopeList,
  getMetaCollectSourceOptions,
  getMetaCollectTask,
  updateMetaCollectTask,
} from '#/api/governance/metacollecttask';
import { getSimpleUserList } from '#/api/system/user';
import { CronTab } from '#/components/cron-tab';
import { useObjectRequest } from '#/hooks/use-object-request';
import DatabaseSelect from '#/views/supply/dataapi/modules/database-select.vue';

import {
  metadataConnectionIcon,
  metadataConnectionIconUrl,
} from '../../metadata-common/icons';
import MetadataIcon from '../../metadata-common/metadata-icon.vue';
import { EXECUTE_STRATEGY_OPTIONS, SCOPE_TYPE_OPTIONS } from '../data';
import FilePathPicker from './file-path-picker.vue';
import {
  collectTaskPayload,
  parseCollectBlacklist,
  parseCollectScope,
} from './task-form';

import '../../metadata-common/metadata.css';

const emit = defineEmits<{ success: [] }>();
const { hasAccessByCodes } = useAccess();
const canBrowseFiles = computed(() =>
  hasAccessByCodes(['dataforge:data-file:query']),
);
const [PathPickerModal, pathPickerApi] = useVbenModal({
  connectedComponent: FilePathPicker,
  destroyOnClose: true,
});
const model = ref<Api.CollectTask>({});
const identity = ref('closed');
const options = ref<Api.SourceOptions>({ connections: [], systems: [] });
const users = ref<{ label: string; mobile?: string; value: Api.Id }[]>([]);
const scopes = ref<Api.Scope[]>([]);
const selectedScopes = ref<Api.Scope[]>([]);
const blacklist = ref<string[]>([]);
const ready = ref(false);
const saving = ref(false);
const failed = ref(false);
const scopeLoading = ref(false);
const scopeFailed = ref(false);
const attempted = ref(false);
const errors = computed(() => ({
  name: attempted.value && !model.value.name?.trim(),
  source: attempted.value && !selectedSystem.value,
  connection: attempted.value && !connection.value,
}));
const request = useObjectRequest(
  () => identity.value,
  () => {
    ready.value = false;
    saving.value = false;
    scopes.value = [];
  },
);
const scopeRequest = useObjectRequest(
  () => `${identity.value}/${String(model.value.datasourceId)}`,
  () => {
    scopeLoading.value = false;
    scopeFailed.value = false;
    scopes.value = [];
  },
);
const connections = computed(() =>
  options.value.connections.filter(
    (item) => item.collectType === model.value.collectType,
  ),
);
const selectedSystem = computed(() =>
  options.value.systems.find(
    (item) => String(item.id) === String(model.value.sourceSystemId),
  ),
);
const choices = computed(() =>
  connections.value.map((item) => {
    let categoryCode = '关系型数据库';
    if (item.collectType === 2) {
      categoryCode = '文件/对象存储';
    } else if (item.dbType?.toLowerCase() === 'doris') {
      categoryCode = '分析性数据库';
    }
    return {
      id: item.id,
      name: item.name,
      productType: item.dbType,
      databaseTypeName: item.dbType,
      iconPath: metadataConnectionIconUrl(item.dbType),
      categoryCode,
    };
  }),
);
const connection = computed(() =>
  connections.value.find(
    (item) => String(item.id) === String(model.value.datasourceId),
  ),
);
const title = computed(
  () =>
    `${
      (model.value.id === undefined ? '新增' : '修改') +
      (model.value.collectType === 2 ? '非结构化' : '结构化')
    }采集任务`,
);
function selectConnection(id?: Api.Id) {
  model.value.datasourceId = id;
  const selected = connections.value.find(
    (item) => String(item.id) === String(id),
  );
  model.value.datasourceName = selected?.name;
  model.value.dbType = selected?.dbType;
  model.value.host = selected?.host;
  model.value.port = selected?.port;
  model.value.username = selected?.username;
  selectedScopes.value = [];
  scopes.value = [];
  void loadScopes();
}
function selectSystem(value: unknown) {
  const system = options.value.systems.find(
    (item) => String(item.id) === String(value),
  );
  model.value.sourceSystemId = system?.id;
  model.value.sourceSystemName = system?.name;
}
function selectOwner(id: unknown) {
  model.value.ownerPhone = users.value.find(
    (item) => String(item.value) === String(id),
  )?.mobile;
}

function browsePath() {
  if (!canBrowseFiles.value) return;
  if (!connection.value) {
    message.warning('未选择源数据连接名称，请先选择数据连接名称');
    return;
  }
  pathPickerApi
    .setData({
      connectionId: connection.value.id,
      connectionName: connection.value.name,
    })
    .open();
}

function setFilePath(path: string) {
  model.value.filePath = path;
}
async function loadScopes() {
  const localConnection = connection.value;
  if (
    model.value.collectType !== 1 ||
    model.value.scopeType !== 2 ||
    !localConnection
  )
    return;
  const current = scopeRequest.begin();
  scopeLoading.value = true;
  scopeFailed.value = false;
  try {
    const data = await getMetaCollectScopeList(localConnection.id);
    if (current()) scopes.value = data;
  } catch {
    if (current()) scopeFailed.value = true;
  } finally {
    if (current()) scopeLoading.value = false;
  }
}
function setScopes(keys: (number | string)[]) {
  selectedScopes.value = keys.map(
    (key) =>
      selectedScopes.value.find((scope) => scope.db === String(key)) ?? {
        db: String(key),
        tables: [],
      },
  );
}
function setTables(db: string, tables: string[]) {
  const scope = selectedScopes.value.find((item) => item.db === db);
  if (scope) scope.tables = tables;
}
async function initialize(input?: Api.CollectTask) {
  const current = request.begin();
  attempted.value = false;
  failed.value = false;
  modalApi.setState({ loading: true, confirmDisabled: true });
  try {
    const [sourceOptions, userOptions, loaded] = await Promise.all([
      getMetaCollectSourceOptions(),
      getSimpleUserList(),
      input?.id === undefined
        ? Promise.resolve(input)
        : getMetaCollectTask(input.id),
    ]);
    if (!current()) return;
    model.value = {
      collectType: input?.collectType ?? 1,
      executeStrategy: 'SERIAL_DISCARD',
      scopeType: 1,
      cron: '0 0 0 * * ?',
      filePath: '/',
      ...loaded,
    };
    options.value = sourceOptions;
    users.value = userOptions
      .filter((item) => item.id !== undefined)
      .map((item) => ({
        label: item.nickname,
        value: item.id!,
        mobile: item.mobile,
      }));
    selectedScopes.value = parseCollectScope(model.value.scopeConfig);
    blacklist.value = parseCollectBlacklist(model.value.blacklist);
    ready.value = true;
    await loadScopes();
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current())
      modalApi.setState({ loading: false, confirmDisabled: !ready.value });
  }
}
const [Modal, modalApi] = useVbenModal({
  class: 'metadata-modal top-[6vh] w-[1200px] max-w-[calc(100vw-32px)]',
  fullscreenButton: false,
  confirmText: '确定',
  cancelText: '取消',
  headerClass: 'metadata-modal__header',
  contentClass: 'metadata-modal__body',
  footerClass: 'metadata-modal__footer',
  onBeforeClose: () => !saving.value,
  async onOpenChange(open) {
    if (!open) {
      identity.value = 'closed';
      request.begin();
      modalApi.unlock();
      return;
    }
    const input = modalApi.getData() as Api.CollectTask | undefined;
    identity.value = `${String(input?.id ?? 'new')}/${String(input?.collectType ?? 1)}`;
    await initialize(input);
  },
  async onConfirm() {
    if (!ready.value || saving.value) return;
    attempted.value = true;
    if (
      !model.value.name?.trim() ||
      !connection.value ||
      !selectedSystem.value
    ) {
      message.warning('请填写来源系统、任务名称和数据连接');
      return;
    }
    if (!model.value.cron?.trim()) {
      message.warning('请配置调度周期');
      return;
    }
    if (model.value.collectType === 2 && !model.value.filePath?.trim()) {
      message.warning('请输入文件路径');
      return;
    }
    if (
      model.value.collectType === 1 &&
      model.value.scopeType === 2 &&
      selectedScopes.value.length === 0
    ) {
      message.warning('请至少选择一个采集库');
      return;
    }
    const current = request.begin();
    saving.value = true;
    modalApi.lock();
    try {
      const data = collectTaskPayload(
        model.value,
        selectedScopes.value,
        blacklist.value,
      );
      await (model.value.id === undefined
        ? createMetaCollectTask(data)
        : updateMetaCollectTask(data));
      if (current()) {
        saving.value = false;
        message.success('保存成功');
        emit('success');
        await modalApi.close();
      }
    } finally {
      if (current()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
});
</script>

<template>
  <Modal :title="title">
    <PathPickerModal @select="setFilePath" />
    <Alert
      v-if="failed"
      type="error"
      message="任务或可选数据读取失败，请重试。"
      class="mb-4"
    >
      <template #action>
        <Button
          size="small"
          @click="initialize(modalApi.getData() as Api.CollectTask)"
        >
          <template #icon>
            <MetadataIcon name="refresh" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <div class="metadata-form-grid">
      <div class="metadata-form-item metadata-form-item--required">
        <label>来源系统</label><Select
          :value="selectedSystem?.id"
          @change="selectSystem"
          :status="errors.source ? 'error' : undefined"
          :options="
            options.systems.map((item) => ({
              label: item.name,
              value: item.id,
            }))
          "
          :placeholder="model.sourceSystemName || '请选择来源系统'"
          show-search
          option-filter-prop="label"
        />
        <span v-if="errors.source" class="metadata-field-error">请选择来源系统</span>
      </div>
      <div class="metadata-form-item metadata-form-item--required">
        <label>任务名称</label><Input
          v-model:value="model.name"
          :status="errors.name ? 'error' : undefined"
          :maxlength="200"
          placeholder="建议格式：数据域_层级/库名_表名_采集方式(全量/增量)_调度周期"
        />
        <span v-if="errors.name" class="metadata-field-error">请输入任务名称</span>
      </div>
      <div class="metadata-form-item metadata-form-item--required">
        <label>数据连接名称</label>
        <div
          class="metadata-connection-control"
          :class="{ 'metadata-connection-control--invalid': errors.connection }"
        >
          <DatabaseSelect
            :value="model.datasourceId"
            :databases="choices"
            :catalog="[]"
            :disabled="!ready"
            @change="selectConnection"
          /><span
            v-if="!connection && model.datasourceName"
            class="metadata-saved-connection"
            ><MetadataIcon :name="metadataConnectionIcon(model.dbType)" />{{
              model.datasourceName
            }}</span>
        </div>
        <span v-if="errors.connection" class="metadata-field-error">请选择数据连接名称</span>
      </div>
      <div class="metadata-form-item metadata-form-item--required">
        <label>数据库类型</label><Input
          :value="connection?.dbType || model.dbType"
          disabled
          placeholder="请选择数据连接"
        />
      </div>
      <template v-if="model.collectType === 1">
        <div class="metadata-form-item metadata-form-item--required">
          <label>IP</label><Input
            :value="connection?.host || model.host"
            disabled
            placeholder="请输入 IP"
          />
        </div>
        <div class="metadata-form-item metadata-form-item--required">
          <label>端口号</label><Input
            :value="connection?.port || model.port"
            disabled
            placeholder="请输入端口号"
          />
        </div>
        <div class="metadata-form-item metadata-form-item--required">
          <label>账号</label><Input
            :value="connection?.username || model.username"
            disabled
            placeholder="请输入账号"
          />
        </div>
      </template>
      <div class="metadata-form-item metadata-form-item--required">
        <label>执行策略</label><Select
          v-model:value="model.executeStrategy"
          :options="EXECUTE_STRATEGY_OPTIONS"
        />
      </div>
      <div class="metadata-form-item metadata-form-item--required">
        <label>调度周期</label>
        <div>
          <CronTab v-model="model.cron" class="metadata-cron w-full">
            <template #trigger="{ open, disabled }">
              <Button
                type="primary"
                :disabled="disabled || !ready"
                @click="open"
              >
                <template #icon><MetadataIcon name="settings" /></template>配置
              </Button>
            </template>
          </CronTab>
          <div class="metadata-form-hint">定义任务自动触发的时间和频率</div>
        </div>
      </div>
      <div
        v-if="model.collectType === 2"
        class="metadata-form-item metadata-form-item--required"
      >
        <label>文件路径</label>
        <div class="metadata-file-path">
          <Input
            v-model:value="model.filePath"
            :disabled="canBrowseFiles"
            placeholder="请输入文件目录，如 /"
          /><Button v-if="canBrowseFiles" type="primary" @click="browsePath">
            搜索
          </Button>
        </div>
      </div>
      <div class="metadata-form-item">
        <label>责任人</label><Select
          v-model:value="model.ownerUserId"
          :options="users"
          allow-clear
          show-search
          option-filter-prop="label"
          :placeholder="model.ownerName || '请选择责任人'"
          @change="selectOwner"
        />
      </div>
      <div class="metadata-form-item">
        <label>责任人电话</label><Input
          v-model:value="model.ownerPhone"
          placeholder="请输入责任人电话"
        />
      </div>
      <div
        v-if="model.collectType === 1"
        class="metadata-form-item metadata-form-item--wide metadata-form-item--required"
      >
        <label>采集范围</label><RadioGroup
          v-model:value="model.scopeType"
          :options="SCOPE_TYPE_OPTIONS"
          @change="loadScopes"
        />
      </div>
      <div
        v-if="model.collectType === 1 && model.scopeType === 2"
        class="metadata-scope-transfer metadata-form-item--wide"
      >
        <div>
          <Alert v-if="scopeFailed" type="error" message="读取采集范围失败">
            <template #action>
              <Button size="small" @click="loadScopes">
                <template #icon>
                  <MetadataIcon name="refresh" />
                </template>
                重试
              </Button>
            </template>
          </Alert>
          <span v-else-if="scopeLoading">正在读取库表…</span>
          <Transfer
            :data-source="
              scopes.map((scope) => ({ key: scope.db, title: scope.db }))
            "
            :target-keys="selectedScopes.map((scope) => scope.db)"
            :titles="['来源库', '已选来源库']"
            :render="(item) => item.title"
            :list-style="{ width: '320px', height: '318px' }"
            show-search
            :disabled="scopeLoading || scopeFailed"
            @change="setScopes"
          />
          <details class="mt-3">
            <summary class="metadata-link mb-2 cursor-pointer">
              高级范围配置
            </summary>
            <div
              v-for="scope in selectedScopes"
              :key="scope.db"
              class="scope-row"
            >
              <span class="min-w-[150px]">{{ scope.db }}</span><Select
                mode="multiple"
                :value="scope.tables"
                :options="
                  (
                    scopes.find((item) => item.db === scope.db)?.tables ?? []
                  ).map((name) => ({ label: name, value: name }))
                "
                placeholder="全部表"
                @change="(values) => setTables(scope.db, values as string[])"
              />
            </div>
            <div class="scope-row">
              <span class="min-w-[150px]">黑名单</span>
              <Select
                v-model:value="blacklist"
                mode="tags"
                placeholder="输入忽略的表名或通配符，按回车添加"
              />
            </div>
          </details>
          <div
            v-if="!scopeLoading && !scopeFailed && !scopes.length"
            class="metadata-form-hint"
          >
            请先选择数据连接；当前没有可选库表。
          </div>
        </div>
      </div>
      <div class="metadata-form-item metadata-form-item--wide">
        <label>描述</label><TextArea
          v-model:value="model.description"
          :rows="4"
          :maxlength="256"
          show-count
          placeholder="请输入描述"
        />
      </div>
    </div>
  </Modal>
</template>
<style scoped>
.metadata-connection-control {
  position: relative;
  min-width: 0;
}

.metadata-file-path {
  display: flex;
  gap: 8px;
}

.metadata-file-path :deep(.ant-input) {
  min-width: 0;
}

.metadata-file-path :deep(.ant-btn) {
  flex-shrink: 0;
}

.metadata-field-error {
  grid-column: 2;
  margin-top: -8px;
  font-size: 12px;
  line-height: 16px;
  color: hsl(var(--destructive));
}

.metadata-connection-control--invalid :deep(button) {
  border-color: hsl(var(--destructive));
}

.metadata-saved-connection {
  position: absolute;
  inset: 1px 24px 1px 10px;
  display: flex;
  gap: 6px;
  align-items: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
  background: hsl(var(--card));
}

.metadata-scope-transfer {
  min-width: 0;
  padding-left: 110px;
}

.metadata-scope-transfer :deep(.ant-transfer) {
  max-width: 800px;
}

.metadata-scope-transfer :deep(.ant-transfer-section) {
  flex: 1;
  min-width: 0;
  max-width: calc((100% - 160px) / 2);
}

.metadata-scope-transfer :deep(.ant-transfer-actions) {
  flex: 0 0 160px;
  flex-direction: row-reverse;
  gap: 10px;
  justify-content: center;
  margin: 0;
}

@media (max-width: 900px) {
  .metadata-scope-transfer {
    padding-left: 0;
  }

  .metadata-scope-transfer :deep(.ant-transfer-section) {
    max-width: calc((100% - 80px) / 2);
  }

  .metadata-scope-transfer :deep(.ant-transfer-actions) {
    flex-basis: 80px;
    gap: 8px;
  }
}

.scope-row {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 8px;
}

.scope-row :deep(.ant-checkbox-wrapper) {
  min-width: 150px;
}

.scope-row :deep(.ant-select) {
  flex: 1;
}
</style>
