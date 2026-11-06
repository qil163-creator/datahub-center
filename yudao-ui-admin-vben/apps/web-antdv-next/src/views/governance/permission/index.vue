<script lang="ts" setup>
import type { SafetyLevelApi } from '#/api/governance/level';
import type { SafetyPermissionApi } from '#/api/governance/permission';
import type { SystemRoleApi } from '#/api/system/role';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, Card, message, Tag } from 'antdv-next';

import { getSimpleLevelList } from '#/api/governance/level';
import {
  getPermissionConfig,
  getRuleSimpleList,
  savePermissionConfig,
} from '#/api/governance/permission';
import { getSimpleRoleList } from '#/api/system/role';
import PageGuide from '#/components/page-guide/index.vue';

import BatchConfigModal from './components/batch-config-modal.vue';
import DatabaseTreePanel from './components/database-tree-panel.vue';
import FieldPermissionModal from './components/field-permission-modal.vue';
import FieldPermissionTable from './components/field-permission-table.vue';
import PermissionConfigPanel from './components/permission-config-panel.vue';
import { configKey, isConfigForTable } from './data';

defineOptions({ name: 'SafetyPermission' });

const configPanelRef = ref<InstanceType<typeof PermissionConfigPanel>>();
const fieldTableRef = ref<InstanceType<typeof FieldPermissionTable>>();
const actionSeq = ref(0);
const accessStore = useAccessStore();
let metadataSeq = 0;
const configReady = ref(false);
const configError = ref('');
let columnsLoading: Promise<void> | undefined;
const dirtyKeys = ref(new Set<string>());
const dirty = computed(() => dirtyKeys.value.size > 0);

const currentTable = ref<
  | (SafetyPermissionApi.TableNode & {
      databaseId: number | string;
      databaseName?: string;
    })
  | undefined
>();
const currentField = ref<SafetyPermissionApi.FieldPermission>();
const currentRoleId = ref<number | string>();
const selectedFields = ref<SafetyPermissionApi.FieldPermission[]>([]);
const maskingRules = ref<
  { id: number; methodCode?: string; ruleName?: string; status?: number }[]
>([]);
const roles = ref<SystemRoleApi.Role[]>([]);
const securityLevels = ref<SafetyLevelApi.Level[]>([]);
const roleConfigs = ref(
  new Map<string, SafetyPermissionApi.RolePermissionConfig>(),
);
const fieldDialogOpen = ref(false);
const batchDialogOpen = ref(false);
const saving = ref(false);

const cardBodyStyles = {
  body: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    overflow: 'hidden',
    padding: '12px',
  },
} as const;

const currentTableDirty = computed(() => {
  const table = currentTable.value;
  return (
    !!table &&
    [...dirtyKeys.value].some((key) =>
      isConfigForTable(
        key,
        table.databaseId,
        table.schemaName,
        table.tableName,
      ),
    )
  );
});

const enabledMaskingRules = computed(() =>
  maskingRules.value.filter((item) => item.status !== CommonStatusEnum.DISABLE),
);

function markDirty() {
  if (!configReady.value || !currentTable.value || !currentRoleId.value) return;
  dirtyKeys.value.add(
    configKey(
      currentTable.value.databaseId,
      currentTable.value.schemaName,
      currentTable.value.tableName,
      currentRoleId.value,
    ),
  );
  saveCurrentRoleConfig();
}

async function initData() {
  const seq = ++metadataSeq;
  const [rulePage, roleList, levelList] = await Promise.allSettled([
    getRuleSimpleList(),
    getSimpleRoleList(),
    getSimpleLevelList(),
  ]);
  if (seq !== metadataSeq) return;
  maskingRules.value =
    rulePage.status === 'fulfilled' ? (rulePage.value.list ?? []) : [];
  roles.value = roleList.status === 'fulfilled' ? (roleList.value ?? []) : [];
  securityLevels.value =
    levelList.status === 'fulfilled' ? (levelList.value ?? []) : [];
  if (rulePage.status === 'rejected') {
    message.warning('脱敏规则加载失败，脱敏访问时可能无法选择规则');
  }
  if (roleList.status === 'rejected') {
    message.warning('角色列表加载失败，请确认是否有角色查询权限');
  }
  if (levelList.status === 'rejected') {
    message.warning('安全等级加载失败，不影响表和字段权限配置');
  }
}

function saveCurrentRoleConfig() {
  if (!configReady.value || !currentRoleId.value || !currentTable.value) {
    return;
  }
  const key = configKey(
    currentTable.value.databaseId,
    currentTable.value.schemaName,
    currentTable.value.tableName,
    currentRoleId.value,
  );
  if (!dirtyKeys.value.has(key)) return;
  const tablePermissionConfig =
    configPanelRef.value?.getCurrentRoleTablePermissionConfig();
  if (!tablePermissionConfig) {
    return;
  }
  const fieldPermissions =
    fieldTableRef.value?.getCurrentRoleFieldPermissionConfig() ?? [];
  roleConfigs.value.set(key, {
    roleId: currentRoleId.value,
    roleName: tablePermissionConfig.roleName,
    tablePermission: tablePermissionConfig.tablePermission,
    fieldPermissions,
  });
}

async function loadRoleConfig(roleId: number | string, seq: number) {
  if (!currentTable.value) {
    return;
  }
  const key = configKey(
    currentTable.value.databaseId,
    currentTable.value.schemaName,
    currentTable.value.tableName,
    roleId,
  );
  const cached = roleConfigs.value.get(key);
  if (cached) {
    configPanelRef.value?.loadRoleConfig(roleId, cached);
    fieldTableRef.value?.loadRoleFieldPermissionConfig(cached);
    configReady.value = true;
    return;
  }
  const remote = await getPermissionConfig(
    currentTable.value.databaseId,
    currentTable.value,
    roleId,
  );
  if (seq !== actionSeq.value) {
    return;
  }
  if (
    String(remote.databaseId) !== String(currentTable.value?.databaseId) ||
    remote.schemaName !== currentTable.value?.schemaName ||
    remote.tableName !== currentTable.value?.tableName ||
    remote.tableReference !== currentTable.value?.tableReference ||
    remote.legacyUnbound
  ) {
    throw new Error('返回配置不匹配当前精确 schema/表，拒绝加载');
  }
  const backendConfig = remote.rolePermissionConfigs?.[0];
  if (!backendConfig || String(backendConfig.roleId) !== String(roleId)) {
    throw new Error('返回角色配置不完整，拒绝使用默认权限覆盖');
  }
  if (backendConfig) {
    configPanelRef.value?.loadRoleConfig(roleId, backendConfig);
    fieldTableRef.value?.loadRoleFieldPermissionConfig(backendConfig);
    configReady.value = true;
    return;
  }
  configPanelRef.value?.resetToDefault(roleId);
  fieldTableRef.value?.resetToDefault();
  configReady.value = true;
}

async function handleTableSelect(
  table: SafetyPermissionApi.TableNode & {
    databaseId: number | string;
    databaseName?: string;
  },
) {
  const seq = ++actionSeq.value;
  if (currentTable.value && currentRoleId.value) {
    saveCurrentRoleConfig();
  }
  configReady.value = false;
  configError.value = '';
  const previousRoleId = currentRoleId.value;
  currentTable.value = table;
  selectedFields.value = [];
  currentField.value = undefined;
  columnsLoading = fieldTableRef.value?.reloadColumns(table);
  try {
    await columnsLoading;
    if (seq !== actionSeq.value) return;
    if (previousRoleId) {
      currentRoleId.value = previousRoleId;
      await loadRoleConfig(previousRoleId, seq);
    } else {
      currentRoleId.value = undefined;
    }
  } catch {
    if (seq === actionSeq.value) {
      configError.value = '字段或角色配置加载失败，请重试';
    }
  }
}

async function handleRoleChange(roleId?: number | string) {
  const seq = ++actionSeq.value;
  configReady.value = false;
  configError.value = '';
  currentRoleId.value = roleId;
  if (roleId && currentTable.value) {
    try {
      await columnsLoading;
      if (seq !== actionSeq.value) return;
      await loadRoleConfig(roleId, seq);
    } catch {
      if (seq === actionSeq.value) {
        configError.value = '字段或角色配置加载失败，请重试';
      }
    }
  }
}

function handleFieldSelect(fields: SafetyPermissionApi.FieldPermission[]) {
  selectedFields.value = fields;
}

function handleFieldEdit(field: SafetyPermissionApi.FieldPermission) {
  currentField.value = field;
  fieldDialogOpen.value = true;
}

function handleFieldPermissionSave(
  config: SafetyPermissionApi.FieldPermission,
) {
  if (currentField.value) {
    fieldTableRef.value?.updateFieldConfig(currentField.value, config);
  }
  markDirty();
  message.success('字段权限配置已更新');
}

function handleBatchConfig() {
  if (selectedFields.value.length === 0) {
    message.warning('请先选择要配置的字段');
    return;
  }
  batchDialogOpen.value = true;
}

function handleBatchConfigSave(
  config: Partial<SafetyPermissionApi.FieldPermission>,
) {
  fieldTableRef.value?.batchUpdateConfig(selectedFields.value, config);
  markDirty();
  message.success('批量配置已应用');
}

async function saveAllConfig() {
  if (saving.value || !configReady.value) {
    message.warning('请等待精确表及角色配置加载完成');
    return;
  }
  if (!currentTable.value) {
    message.warning('请先选择要配置的数据表');
    return;
  }
  if (currentRoleId.value) {
    saveCurrentRoleConfig();
  }
  const rolePermissionConfigs = [...roleConfigs.value.entries()]
    .filter(
      ([key]) =>
        dirtyKeys.value.has(key) &&
        isConfigForTable(
          key,
          currentTable.value!.databaseId,
          currentTable.value!.schemaName,
          currentTable.value!.tableName,
        ),
    )
    .map(([, config]) => config);
  if (rolePermissionConfigs.length === 0) {
    message.warning('请先配置至少一个角色的权限');
    return;
  }
  const invalid = rolePermissionConfigs.flatMap((roleConfig) =>
    (roleConfig.fieldPermissions ?? [])
      .filter((field) => field.permissionType === 2 && !field.maskingRuleId)
      .map(
        (field) =>
          `角色「${roleConfig.roleName || roleConfig.roleId}」的字段「${field.fieldName}」选择了脱敏访问但未配置脱敏规则`,
      ),
  );
  if (invalid.length > 0) {
    message.error(invalid.join('；'));
    return;
  }
  const target = { ...currentTable.value };
  const seq = actionSeq.value;
  saving.value = true;
  try {
    await savePermissionConfig({
      databaseId: target.databaseId,
      schemaName: target.schemaName,
      tableName: target.tableName,
      tableReference: target.tableReference,
      rolePermissionConfigs,
    });
    if (seq !== actionSeq.value) return;
    for (const key of dirtyKeys.value) {
      if (
        isConfigForTable(
          key,
          target.databaseId,
          target.schemaName,
          target.tableName,
        )
      ) {
        dirtyKeys.value.delete(key);
        roleConfigs.value.delete(key);
      }
    }
    if (currentRoleId.value) {
      configReady.value = false;
      try {
        await loadRoleConfig(currentRoleId.value, actionSeq.value);
      } catch {
        if (seq === actionSeq.value) {
          configError.value = '配置已保存，但重新加载失败，请重试';
        }
        return;
      }
    }
    message.success('配置保存成功');
  } finally {
    saving.value = false;
  }
}

async function handleBindingVerified() {
  if (currentTable.value) {
    await handleTableSelect(currentTable.value);
  }
}

watch(
  () => accessStore.accessToken,
  () => {
    ++actionSeq.value;
    currentTable.value = undefined;
    currentRoleId.value = undefined;
    roleConfigs.value.clear();
    selectedFields.value = [];
    currentField.value = undefined;
    dirtyKeys.value.clear();
    configReady.value = false;
    configError.value = '';
    columnsLoading = undefined;
    fieldDialogOpen.value = false;
    batchDialogOpen.value = false;
    roles.value = [];
    maskingRules.value = [];
    securityLevels.value = [];
    void initData();
  },
);
onBeforeUnmount(() => {
  ++actionSeq.value;
  ++metadataSeq;
});
onMounted(initData);
</script>

<template>
  <Page auto-content-height content-class="flex flex-col overflow-hidden">
    <div
      :key="String(accessStore.accessToken)"
      class="flex h-full min-h-0 flex-col gap-3"
    >
      <Card class="shrink-0" size="small">
        <div class="flex items-start justify-between gap-4">
          <PageGuide
            guide-id="governance.permission.scope"
            class="flex-1"
            show-icon
            type="info"
            message="权限配置说明"
            description="本页面保留数据权限规则的配置基础。当前业务查询、同步、研发和数据服务暂不应用此处规则，保存配置不会限制这些模块的数据访问。系统登录、角色菜单及功能操作权限继续生效。"
          />
          <div class="ml-auto flex shrink-0 items-center gap-2">
            <Tag v-if="dirty" color="warning">有未保存的配置</Tag>
            <Button
              v-access:code="'governance:safety-permission:update'"
              :loading="saving"
              :disabled="!configReady || !currentTableDirty"
              type="primary"
              @click="saveAllConfig"
            >
              <template #icon>
                <IconifyIcon icon="lucide:check" />
              </template>
              保存配置
            </Button>
          </div>
        </div>
      </Card>

      <Alert v-if="configError" type="error" show-icon :message="configError">
        <template #action>
          <Button
            size="small"
            @click="currentTable && handleTableSelect(currentTable)"
          >
            重试
          </Button>
        </template>
      </Alert>

      <div
        class="permission-workspace grid min-h-0 flex-1 grid-cols-[320px_340px_minmax(0,1fr)] gap-3"
        :inert="saving"
      >
        <Card
          class="min-h-0"
          size="small"
          title="数据表"
          :styles="cardBodyStyles"
        >
          <DatabaseTreePanel @table-select="handleTableSelect" />
        </Card>
        <Card
          class="min-h-0"
          size="small"
          title="角色权限配置"
          :styles="cardBodyStyles"
        >
          <PermissionConfigPanel
            ref="configPanelRef"
            :config-ready="configReady"
            :has-unsaved-changes="dirty"
            :current-table="currentTable?.tableReference"
            :current-binding="currentTable"
            :masking-rules="enabledMaskingRules"
            :roles="roles"
            :security-levels="securityLevels"
            @change="markDirty"
            @role-change="handleRoleChange"
            @binding-verified="handleBindingVerified"
          />
        </Card>
        <Card
          class="min-h-0"
          size="small"
          :title="
            currentTable ? `${currentTable.tableReference}` : '请选择数据表'
          "
          :styles="cardBodyStyles"
        >
          <FieldPermissionTable
            ref="fieldTableRef"
            :config-ready="configReady"
            :current-role-id="currentRoleId"
            :current-table="currentTable"
            :masking-rules="enabledMaskingRules"
            :security-levels="securityLevels"
            @batch-config="handleBatchConfig"
            @change="markDirty"
            @field-edit="handleFieldEdit"
            @field-select="handleFieldSelect"
          />
        </Card>
      </div>
    </div>

    <FieldPermissionModal
      v-model:open="fieldDialogOpen"
      :field-data="currentField"
      :masking-rules="enabledMaskingRules"
      :security-levels="securityLevels"
      @confirm="handleFieldPermissionSave"
    />
    <BatchConfigModal
      v-model:open="batchDialogOpen"
      :masking-rules="enabledMaskingRules"
      :security-levels="securityLevels"
      :selected-fields="selectedFields"
      @confirm="handleBatchConfigSave"
    />
  </Page>
</template>

<style scoped>
.permission-workspace :deep(.ant-card) {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.permission-workspace :deep(.ant-card-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
</style>
