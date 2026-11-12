<script lang="ts" setup>
import type { SafetySecurityApi } from '#/api/governance/security';

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
  Switch,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  createKeyRef,
  getKeyCenterStatus,
  getKeyRefList,
  updateKeyRefStatus,
} from '#/api/governance/security';
import { createLatestWins } from '#/utils/resource-id';

import { KEY_CODE_PATTERN, keyRefFormError } from './data';

defineOptions({ name: 'SafetyKeyRef' });

const { hasAccessByCodes } = useAccess();
const canQuery = computed(() => hasAccessByCodes(['governance:key-ref:query']));
const canCreate = computed(() =>
  hasAccessByCodes(['governance:key-ref:create']),
);
const canUpdate = computed(() =>
  hasAccessByCodes(['governance:key-ref:update']),
);

const refs = ref<SafetySecurityApi.KeyRef[]>([]);
const status = ref<SafetySecurityApi.KeyCenterStatus>();
const loading = ref(false);
const guard = createLatestWins();
const pending = reactive(new Set<string>());

const columns = [
  { title: '引用编码', dataIndex: 'keyCode', key: 'keyCode', width: 200 },
  { title: '名称', dataIndex: 'keyName', key: 'keyName' },
  { title: '类型', key: 'keyType', width: 150 },
  {
    title: '主密钥版本',
    dataIndex: 'keyVersion',
    key: 'keyVersion',
    width: 110,
  },
  { title: '启用', key: 'status', width: 100 },
  { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true },
];

async function load() {
  if (!canQuery.value) return;
  const ticket = guard.next();
  loading.value = true;
  try {
    const [list, center] = await Promise.all([
      getKeyRefList(),
      getKeyCenterStatus(),
    ]);
    if (!ticket.isCurrent()) return;
    refs.value = list ?? [];
    status.value = center;
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

async function toggle(record: SafetySecurityApi.KeyRef, enabled: boolean) {
  const key = String(record.id);
  if (pending.has(key)) return;
  pending.add(key);
  try {
    await updateKeyRefStatus({ id: record.id, status: enabled ? 0 : 1 });
    message.success(
      enabled ? '已启用' : '已停用，引用它的加密规则将返回不可逆掩码',
    );
  } finally {
    pending.delete(key);
    await load();
  }
}

const createOpen = ref(false);
const creating = ref(false);
const form = reactive({ keyCode: '', keyName: '', remark: '' });
const formError = ref<string>();

function openCreate() {
  form.keyCode = '';
  form.keyName = '';
  form.remark = '';
  formError.value = undefined;
  createOpen.value = true;
}

async function submitCreate() {
  formError.value = keyRefFormError(form.keyCode, form.keyName);
  if (formError.value || creating.value) return;
  creating.value = true;
  try {
    await createKeyRef({
      keyCode: form.keyCode.trim(),
      keyName: form.keyName.trim(),
      remark: form.remark.trim() || undefined,
    });
    message.success('已创建密钥引用');
    createOpen.value = false;
    await load();
  } finally {
    creating.value = false;
  }
}

onMounted(load);
onBeforeUnmount(() => guard.invalidate());
</script>

<template>
  <Page auto-content-height>
    <Alert
      v-if="!canQuery"
      type="warning"
      show-icon
      message="当前账号没有密钥管理查询权限"
    />
    <div v-else class="flex flex-col gap-4">
      <Alert
        v-if="status && !status.available"
        type="error"
        show-icon
        message="密钥中心未配置主密钥"
        description="请在部署环境配置 yudao.governance.key-center.master-keys 与 active-version（Base64，至少 32 字节，不得写入代码或数据库）。未配置时字段加密规则返回不可逆掩码。"
      />
      <Alert
        v-if="status && status.inlineSecretRuleCount > 0"
        type="warning"
        show-icon
        :message="`有 ${status.inlineSecretRuleCount} 条加密规则仍在规则中保存了密钥`"
        description="请在“脱敏规则库”中编辑这些规则，改用密钥引用后重新保存；历史密钥会随之从规则当前版本中移除。"
      />
      <Card title="密钥引用" size="small">
        <template #extra>
          <div class="flex gap-2">
            <Tag v-if="status?.available" color="green">
              主密钥版本 {{ status.activeVersion }}
            </Tag>
            <Button size="small" :loading="loading" @click="load">刷新</Button>
            <Button
              v-if="canCreate"
              size="small"
              type="primary"
              :disabled="!status?.available"
              @click="openCreate"
            >
              新建引用
            </Button>
          </div>
        </template>
        <Table
          :columns="columns"
          :data-source="refs"
          :loading="loading"
          :pagination="false"
          :row-key="(record: SafetySecurityApi.KeyRef) => String(record.id)"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'keyType'">
              {{ record.provider }} · {{ record.keyType }}
            </template>
            <template v-else-if="column.key === 'status'">
              <Switch
                :checked="record.status === 0"
                :disabled="!canUpdate"
                :loading="pending.has(String(record.id))"
                @change="(checked) => toggle(record, !!checked)"
              />
            </template>
          </template>
        </Table>
      </Card>
    </div>

    <Modal
      :confirm-loading="creating"
      :open="createOpen"
      title="新建密钥引用"
      @cancel="createOpen = false"
      @ok="submitCreate"
    >
      <Alert
        class="mb-3"
        type="info"
        show-icon
        message="引用只登记名称和编码；数据密钥由主密钥派生，平台不保存、不展示任何密钥。编码创建后不可修改。"
      />
      <div class="flex flex-col gap-3">
        <Input
          v-model:value="form.keyCode"
          :maxlength="64"
          :pattern="KEY_CODE_PATTERN.source"
          placeholder="引用编码，如 pii_default"
        />
        <Input
          v-model:value="form.keyName"
          :maxlength="128"
          placeholder="名称"
        />
        <TextArea
          v-model:value="form.remark"
          :maxlength="512"
          :rows="2"
          placeholder="备注（选填）"
        />
        <div v-if="formError" class="text-destructive text-xs">
          {{ formError }}
        </div>
      </div>
    </Modal>
  </Page>
</template>
