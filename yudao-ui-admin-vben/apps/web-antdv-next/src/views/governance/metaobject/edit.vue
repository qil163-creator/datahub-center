<script setup lang="ts">
import type { GovernanceMetaObjectApi as Api } from '#/api/governance/metaobject';

import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Input,
  message,
  RadioGroup,
  Select,
  Spin,
  TextArea,
} from 'antdv-next';

import {
  getMetaTableDetail,
  updateMetaTableBiz,
} from '#/api/governance/metaobject';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

import { displayComment } from './data';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaTableEdit' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const id = computed(() =>
  route.name === 'GovernanceMetaTableEdit' ? String(route.params.id ?? '') : '',
);
const detail = ref<Api.MetaTable>();
const model = reactive<Api.TableBizUpdate>({ id: '', bizRevision: 0 });
const users = ref<{ label: string; mobile?: string; value: Api.Id }[]>([]);
const loading = ref(false);
const saving = ref(false);
const saveFailed = ref(false);
const ready = ref(false);
const request = useObjectRequest(
  () => id.value,
  () => {
    detail.value = undefined;
    users.value = [];
    ready.value = false;
    loading.value = false;
    saving.value = false;
  },
);
const canSave = computed(
  () =>
    ready.value &&
    detail.value?.publishStatus !== 1 &&
    hasAccessByCodes(['governance:meta-table:update']),
);
const flags = [
  { label: '否', value: 0 },
  { label: '是', value: 1 },
];
const layers = ['ODS', 'DWD', 'DIM', 'DWS', 'ADS'].map((value) => ({
  label: value,
  value,
}));
async function load() {
  if (!id.value) return;
  const current = request.begin();
  loading.value = true;
  saveFailed.value = false;
  ready.value = false;
  try {
    const [data, people] = await Promise.all([
      getMetaTableDetail(id.value),
      getSimpleUserList(),
    ]);
    if (!current()) return;
    detail.value = data;
    users.value = people
      .filter((person) => person.id !== undefined)
      .map((person) => ({
        label: person.nickname,
        mobile: person.mobile,
        value: person.id!,
      }));
    Object.assign(model, {
      id: data.id,
      bizRevision: data.bizRevision,
      layer: data.layer,
      customComment: data.customComment,
      businessName: data.businessName,
      storageType: data.storageType,
      businessDefinition:
        data.businessDefinition ??
        data.customComment ??
        data.sourceComment ??
        '',
      masterFlag: data.masterFlag ?? 0,
      temporaryFlag: data.temporaryFlag ?? 0,
      sensitiveFlag: data.sensitiveFlag,
      subjectDomainId: data.subjectDomainId,
      subjectDomainName: data.subjectDomainName,
      ownerDeptId: data.ownerDeptId,
      remark: data.remark,
      techLeaderUserId: data.techLeaderUserId,
      techLeaderPhone: data.techLeaderPhone,
      businessLeaderUserId: data.businessLeaderUserId,
      businessLeaderPhone: data.businessLeaderPhone,
    });
    ready.value = data.id !== undefined && data.bizRevision !== undefined;
  } catch {
    if (current()) ready.value = false;
  } finally {
    if (current()) loading.value = false;
  }
}
function setLeader(kind: 'business' | 'tech', value: unknown) {
  const phone = users.value.find(
    (person) => String(person.value) === String(value),
  )?.mobile;
  model[kind === 'tech' ? 'techLeaderPhone' : 'businessLeaderPhone'] = phone;
}
function back() {
  return router.push(
    String(route.meta.activePath || '/govern/metadata/metaobject'),
  );
}
async function save(exit: boolean) {
  if (!canSave.value || saving.value) return;
  const current = request.begin();
  saving.value = true;
  try {
    await updateMetaTableBiz({ ...model });
    if (!current()) return;
    message.success('保存成功');
    saving.value = false;
    await (exit ? back() : load());
  } catch {
    if (current()) {
      saveFailed.value = true;
      ready.value = false;
    }
  } finally {
    if (current()) saving.value = false;
  }
}
watch(id, load, { immediate: true });
</script>

<template>
  <Page auto-content-height content-class="metadata-page">
    <Alert
      v-if="saveFailed"
      type="error"
      message="保存失败或内容已被其他人修改，请重新加载后保存。"
    >
      <template #action>
        <Button @click="load">重新加载</Button>
      </template>
    </Alert>
    <Alert
      v-if="detail?.publishStatus === 1"
      type="warning"
      message="已发布元数据需要先取消发布后维护。"
    />
    <Spin :spinning="loading" :classes="{ root: 'metadata-edit-spin' }">
      <section class="metadata-panel metadata-table-edit">
        <h3 class="metadata-section">基础信息</h3>
        <div class="metadata-form-grid">
          <div class="metadata-form-item">
            <label>所属库名</label><Input :value="detail?.dbName" disabled />
          </div>
          <div class="metadata-form-item">
            <label>来源系统</label><Input :value="detail?.sourceSystemName" disabled />
          </div>
          <div class="metadata-form-item metadata-form-item--required">
            <label>表名称</label><Input :value="detail?.tableName" disabled />
          </div>
          <div class="metadata-form-item metadata-form-item--required">
            <label>表注释</label><Input :value="detail ? displayComment(detail) : ''" disabled />
          </div>
          <div class="metadata-form-item">
            <label>所属分层</label><Select
              v-model:value="model.layer"
              :options="layers"
              :disabled="!canSave"
              allow-clear
              placeholder="请选择所属分层"
            />
          </div>
          <div class="metadata-form-item">
            <label>所属系统</label><Input :value="detail?.datasourceName" disabled />
          </div>
        </div>
        <h3 class="metadata-section">技术信息</h3>
        <div class="metadata-form-grid">
          <div class="metadata-form-item">
            <label>数据连接名称</label><Input :value="detail?.datasourceName" disabled />
          </div>
          <div class="metadata-form-item">
            <label>数据库类型</label><Input :value="detail?.dbType" disabled />
          </div>
          <div class="metadata-form-item">
            <label>IP</label><Input :value="detail?.host" disabled />
          </div>
          <div class="metadata-form-item">
            <label>端口号</label><Input :value="detail?.port" disabled />
          </div>
          <div class="metadata-form-item">
            <label>账号</label><Input :value="detail?.username" disabled />
          </div>
          <div class="metadata-form-item">
            <label>存储类型</label><Input
              v-model:value="model.storageType"
              :disabled="!canSave"
              :maxlength="128"
              placeholder="请输入存储类型"
            />
          </div>
          <div class="metadata-form-item">
            <label>技术责任人</label><Select
              v-model:value="model.techLeaderUserId"
              :options="users"
              :disabled="!canSave"
              allow-clear
              show-search
              option-filter-prop="label"
              placeholder="请选择技术责任人"
              @change="(value) => setLeader('tech', value)"
            />
          </div>
          <div class="metadata-form-item">
            <label>技术责任人电话</label><Input
              v-model:value="model.techLeaderPhone"
              :disabled="!canSave"
              placeholder="请输入技术责任人电话"
            />
          </div>
        </div>
        <h3 class="metadata-section">业务信息</h3>
        <div class="metadata-form-grid">
          <div class="metadata-form-item">
            <label>业务责任人</label><Select
              v-model:value="model.businessLeaderUserId"
              :options="users"
              :disabled="!canSave"
              allow-clear
              show-search
              option-filter-prop="label"
              :placeholder="detail?.businessLeaderName || '请选择业务责任人'"
              @change="(value) => setLeader('business', value)"
            />
          </div>
          <div class="metadata-form-item">
            <label>业务责任人电话</label><Input
              v-model:value="model.businessLeaderPhone"
              :disabled="!canSave"
            />
          </div>
          <div class="metadata-form-item">
            <label>是否主表</label><RadioGroup
              v-model:value="model.masterFlag"
              :options="flags"
              :disabled="!canSave"
            />
          </div>
          <div class="metadata-form-item">
            <label>是否临时表</label><RadioGroup
              v-model:value="model.temporaryFlag"
              :options="flags"
              :disabled="!canSave"
            />
          </div>
          <div class="metadata-form-item metadata-form-item--wide">
            <label>描述</label><TextArea
              v-model:value="model.businessDefinition"
              :disabled="!canSave"
              :maxlength="1000"
              :rows="4"
              show-count
            />
          </div>
        </div>
        <div class="metadata-edit-actions">
          <Button :disabled="!canSave" :loading="saving" @click="save(false)">
            暂存
</Button><Button @click="back">返回列表</Button><Button
            type="primary"
            :disabled="!canSave"
            :loading="saving"
            @click="save(true)"
          >
            确认并退出
          </Button>
        </div>
      </section>
    </Spin>
  </Page>
</template>

<style scoped>
.metadata-edit-spin {
  flex: 1;
  min-height: 0;
}

.metadata-edit-spin :deep(.ant-spin-container) {
  height: 100%;
}

.metadata-table-edit {
  min-height: 100%;
  padding: 16px 30px;
}

.metadata-table-edit .metadata-section:not(:first-child) {
  margin-top: 34px;
}

.metadata-edit-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  padding: 40px 0 18px;
}

@media (max-width: 768px) {
  .metadata-table-edit {
    padding: 16px;
  }

  .metadata-edit-actions {
    flex-wrap: wrap;
  }
}
</style>
