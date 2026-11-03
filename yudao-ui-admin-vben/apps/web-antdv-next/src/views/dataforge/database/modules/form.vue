<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import {
  computed,
  onBeforeUnmount,
  onDeactivated,
  ref,
  shallowRef,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, message, Spin } from 'antdv-next';

import { getDatabase, updateDatabase } from '#/api/dataforge/database';
import { getDatabaseType } from '#/api/dataforge/databasetype';
import { parseResourceId, sameResourceId } from '#/utils/resource-id';

import {
  connectionTestNeedsPassword,
  databaseEditValues,
} from '../credential-policy';
import { fileConnectionProtocol } from '../file-connection';
import ConnectionSteps from './connection-steps.vue';
import ConnectionDetails from './create-connection-details.vue';
import ConnectionReview from './create-connection-review.vue';
interface FormModalData {
  id?: FoundationDatabaseApi.Id;
  draft?: Partial<FoundationDatabaseApi.Database>;
  selectedType?: FoundationDatabaseTypeApi.DatabaseType;
}
const emit = defineEmits<{
  changeType: [draft: Partial<FoundationDatabaseApi.Database>];
  success: [];
}>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canUpdate = computed(
  () =>
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['dataforge:database:update']),
);
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const steps = ['修改连接信息', '测试连接并保存'];
const step = ref<0 | 1>(0);
const draft = shallowRef<Partial<FoundationDatabaseApi.Database>>({});
const original = shallowRef<FoundationDatabaseApi.Database>();
const selectedType = shallowRef<FoundationDatabaseTypeApi.DatabaseType>();
const connection = shallowRef<FoundationDatabaseApi.Database>();
const details = ref<InstanceType<typeof ConnectionDetails>>();
const loading = ref(false);
const loadError = ref('');
const moving = ref(false);
const saving = ref(false);
const testing = ref(false);
let revision = 0;
let opened = false;
const testBlockedReason = computed(() =>
  connection.value &&
  fileConnectionProtocol(selectedType.value) !== 'HDFS' &&
  connectionTestNeedsPassword(
    original.value,
    connection.value,
    selectedType.value?.typeCode,
  )
    ? '连接目标、账号或认证配置已改变，请返回上一步重新输入凭据后测试和保存。'
    : undefined,
);
function reset() {
  revision++;
  opened = false;
  step.value = 0;
  draft.value = {};
  original.value = undefined;
  selectedType.value = undefined;
  connection.value = undefined;
  loading.value = false;
  loadError.value = '';
  moving.value = false;
  saving.value = false;
  testing.value = false;
}
const [Modal, modalApi] = useVbenModal({
  showCancelButton: false,
  showConfirmButton: false,
  fullscreenButton: false,
  contentClass: 'overflow-hidden p-0',
  headerClass: 'px-6 py-5',
  footerClass: 'px-6 py-4',
  onBeforeClose: () => !saving.value,
  onOpenChange(open) {
    reset();
    modalApi.unlock();
    opened = open;
    if (open) void load();
  },
});
async function load() {
  const data = modalApi.getData() as FormModalData | undefined;
  const id = parseResourceId(data?.draft?.id ?? data?.id);
  if (!opened || !canUpdate.value || !id) {
    loadError.value = '连接编号或修改权限无效，请关闭后重新打开。';
    return;
  }
  const current = ++revision;
  loading.value = true;
  loadError.value = '';
  try {
    const saved = databaseEditValues(await getDatabase(id));
    if (current !== revision) return;
    if (!sameResourceId(saved.id, id)) throw new Error('CONNECTION_IDENTITY');
    original.value = saved;
    if (data?.selectedType) selectedType.value = data.selectedType;
    else if (saved.databaseType) {
      try {
        const type = await getDatabaseType(saved.databaseType);
        if (current !== revision) return;
        selectedType.value = type;
      } catch {
        if (current !== revision) return;
      }
    }
    if (current !== revision) return;
    const values = {
      ...saved,
      ...(data?.selectedType ? data.draft : {}),
      id: saved.id,
    } as FoundationDatabaseApi.Database & { secretKey?: unknown };
    delete values.secretKey;
    draft.value = values;
  } catch {
    if (current === revision) {
      original.value = undefined;
      draft.value = {};
      loadError.value = '连接信息读取失败，请重试。';
    }
  } finally {
    if (current === revision) loading.value = false;
  }
}
async function next() {
  if (
    !opened ||
    !canUpdate.value ||
    loading.value ||
    loadError.value ||
    moving.value ||
    saving.value ||
    step.value !== 0 ||
    !details.value
  )
    return;
  const current = revision;
  moving.value = true;
  try {
    const values = await details.value.readValues();
    if (current !== revision || !values || !original.value) return;
    const data = {
      ...values,
      id: original.value.id,
    } as FoundationDatabaseApi.Database & { secretKey?: unknown };
    delete data.secretKey;
    draft.value = { ...data };
    connection.value = data;
    step.value = 1;
  } finally {
    if (current === revision) moving.value = false;
  }
}
function previous() {
  if (saving.value || moving.value || step.value !== 1) return;
  step.value = 0;
  connection.value = undefined;
  testing.value = false;
}
async function changeType() {
  if (
    !opened ||
    !canUpdate.value ||
    saving.value ||
    moving.value ||
    !details.value ||
    !original.value
  )
    return;
  const current = revision;
  moving.value = true;
  try {
    const values = await details.value.getValues();
    if (current !== revision || !original.value) return;
    emit('changeType', { ...values, id: original.value.id });
    await modalApi.close();
  } finally {
    if (current === revision) moving.value = false;
  }
}
async function save() {
  if (
    !opened ||
    !canUpdate.value ||
    step.value !== 1 ||
    !connection.value ||
    saving.value ||
    testing.value ||
    !!testBlockedReason.value
  )
    return;
  const current = revision;
  const values = { ...connection.value } as FoundationDatabaseApi.Database & {
    secretKey?: unknown;
  };
  delete values.secretKey;
  delete values.connectionStatus;
  delete values.lastTestTime;
  delete values.lastTestMsg;
  saving.value = true;
  modalApi.lock();
  try {
    await updateDatabase(values);
    if (current !== revision) return;
    saving.value = false;
    modalApi.unlock();
    emit('success');
    message.success('连接信息已保存');
    await modalApi.close();
  } catch {
    // 请求层已统一提示，保留当前修改与步骤供重试，并吸收按钮事件的拒绝。
  } finally {
    if (current === revision) {
      saving.value = false;
      modalApi.unlock();
    }
  }
}
function closeContext() {
  reset();
  modalApi.unlock();
  void modalApi.close();
}
watch(scope, closeContext, { flush: 'sync' });
onDeactivated(closeContext);
onBeforeUnmount(reset);
</script>
<template>
  <Modal title="修改连接信息" class="w-[1240px] max-w-[calc(100vw-32px)]">
    <div class="connection-edit" data-testid="connection-edit-wizard">
      <ConnectionSteps :step="step" :steps="steps" label="修改数据连接步骤" />
      <p class="connection-edit__hint">
        {{
          step === 0
            ? '第一步：修改连接信息。凭据留空时保留原凭据，连接目标或认证配置变化后需重新输入才能测试和保存。'
            : '第二步：核对当前修改并测试连接，确认后保存。测试结果不代替保存，也不修改已存连接。'
        }}
      </p>
      <div v-if="loading" class="connection-edit__loading">
        <Spin />正在读取连接信息…
      </div>
      <Alert v-else-if="loadError" :message="loadError" type="error" show-icon>
        <template #action>
          <Button @click="load">
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
            >重试
          </Button>
        </template>
      </Alert>
      <section
        v-else-if="original"
        v-show="step === 0"
        class="connection-edit__body"
        data-testid="connection-edit-step-1"
      >
        <ConnectionDetails
          ref="details"
          mode="edit"
          :draft="draft"
          :selected-type="selectedType"
          @change-type="changeType"
        />
      </section>
      <section
        v-if="step === 1 && connection"
        class="connection-edit__body"
        data-testid="connection-edit-step-2"
      >
        <ConnectionReview
          :connection="connection"
          :selected-type="selectedType"
          :test-blocked-reason="testBlockedReason"
          @testing="testing = $event"
        />
      </section>
    </div>
    <template #footer>
      <div class="connection-edit__footer">
        <span class="connection-edit__summary">{{
          selectedType?.typeName || draft.databaseTypeName || '连接配置'
        }}</span>
        <div class="connection-edit__actions">
          <Button :disabled="saving" @click="modalApi.close()">取消</Button
          ><Button
            v-if="step === 1"
            :disabled="saving || moving"
            @click="previous"
          >
            上一步 </Button
          ><Button
            v-if="step === 0"
            type="primary"
            class="connection-edit__primary"
            :loading="moving"
            :disabled="
              !canUpdate ||
              loading ||
              !!loadError ||
              !original ||
              moving ||
              saving
            "
            @click="next"
          >
            下一步 </Button
          ><Button
            v-else
            type="primary"
            class="connection-edit__primary"
            :loading="saving"
            :disabled="!canUpdate || saving || testing || !!testBlockedReason"
            @click="save"
          >
            保存
          </Button>
        </div>
      </div>
    </template>
  </Modal>
</template>
<style scoped>
.connection-edit {
  display: flex;
  flex-direction: column;
  gap: 18px;
  height: 520px;
  max-height: calc(100dvh - 190px);
  padding: 20px 24px 0;
  color: hsl(var(--foreground));
  background: hsl(var(--background));
}

.connection-edit__hint {
  flex-shrink: 0;
  margin: 0;
  font-size: 14px;
  line-height: 22px;
}

.connection-edit__body {
  flex: 1;
  min-height: 0;
  padding: 0 0 24px;
  overflow-y: auto;
}

.connection-edit__loading {
  display: flex;
  flex: 1;
  gap: 12px;
  align-items: center;
  justify-content: center;
  color: hsl(var(--muted-foreground));
}

.connection-edit__footer {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.connection-edit__summary {
  color: hsl(var(--muted-foreground));
}

.connection-edit__actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.connection-edit__primary {
  background: #4964ff;
  border-color: #4964ff;
}

.connection-edit__primary:hover:not(:disabled) {
  background: #3c56e8;
  border-color: #3c56e8;
}

@media (width <= 640px) {
  .connection-edit {
    gap: 12px;
    max-height: calc(100dvh - 180px);
    padding: 12px 12px 0;
  }

  .connection-edit__hint {
    font-size: 14px;
    line-height: 20px;
  }

  .connection-edit__footer {
    gap: 10px;
  }

  .connection-edit__summary {
    font-size: 14px;
  }

  .connection-edit__actions {
    gap: 6px;
  }
}
</style>
