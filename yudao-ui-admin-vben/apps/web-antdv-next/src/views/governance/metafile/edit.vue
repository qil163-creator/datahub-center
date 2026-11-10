<script setup lang="ts">
import type { GovernanceMetaFileApi as Api } from '#/api/governance/metafile';

import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Input, message, Select, TextArea } from 'antdv-next';

import { getMetaFile, updateMetaFileBiz } from '#/api/governance/metafile';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

import { metadataSize } from '../metadata-common/helpers';
import { metadataIcon } from '../metadata-common/icons';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaFileEdit' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const id = computed(() =>
  route.name === 'GovernanceMetaFileEdit' ? String(route.params.id ?? '') : '',
);
const detail = ref<Api.MetaFile>();
const form = reactive<Api.BizUpdate>({ id: '', bizRevision: 0 });
const users = ref<{ label: string; mobile?: string; value: Api.Id }[]>([]);
const ready = ref(false);
const saveFailed = ref(false);
const saving = ref(false);
const requests = useObjectRequest(
  () => id.value,
  () => {
    ready.value = false;
    saving.value = false;
    detail.value = undefined;
    users.value = [];
  },
);
const canSave = computed(
  () =>
    ready.value &&
    !saving.value &&
    hasAccessByCodes(['governance:meta-file:update']),
);
async function load() {
  if (!id.value) return;
  const current = requests.begin();
  ready.value = false;
  saveFailed.value = false;
  try {
    const [data, userList] = await Promise.all([
      getMetaFile(id.value),
      getSimpleUserList(),
    ]);
    if (!current()) return;
    detail.value = data;
    users.value = userList
      .filter((item) => item.id !== undefined)
      .map((item) => ({
        label: item.nickname,
        value: item.id!,
        mobile: item.mobile,
      }));
    Object.assign(form, {
      id: data.id,
      bizRevision: data.bizRevision,
      businessName: data.businessName,
      description: data.description,
      ownerUserId: data.ownerUserId,
      techLeaderUserId: data.techLeaderUserId,
      techLeaderPhone: data.techLeaderPhone,
      businessLeaderUserId: data.businessLeaderUserId ?? data.ownerUserId,
      businessLeaderPhone: data.businessLeaderPhone ?? data.ownerPhone,
    });
    ready.value = data.bizRevision !== undefined;
  } catch {
    if (current()) ready.value = false;
  }
}
function setLeader(kind: 'business' | 'tech', value: unknown) {
  const phone = users.value.find(
    (item) => String(item.value) === String(value),
  )?.mobile;
  if (kind === 'business') form.businessLeaderPhone = phone;
  else form.techLeaderPhone = phone;
}
async function save(exit: boolean) {
  if (!canSave.value) return;
  const current = requests.begin();
  saving.value = true;
  try {
    await updateMetaFileBiz({ ...form });
    if (!current()) return;
    saving.value = false;
    message.success('保存成功');
    await (exit
      ? router.push({
          path: String(route.meta.activePath || '/govern/metadata/metaobject'),
          query: { kind: 'file' },
        })
      : load());
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
        <Button @click="load">
          <template #icon>
            <IconifyIcon :icon="metadataIcon('refresh')" />
          </template>
          重新加载
        </Button>
      </template>
    </Alert>
    <section class="metadata-panel min-h-0 flex-1">
      <h3 class="metadata-section">基础信息</h3>
      <div class="metadata-form-grid">
        <div class="metadata-form-item metadata-form-item--required">
          <label>来源系统</label><Input :value="detail?.sourceSystemName" disabled />
        </div>
        <div class="metadata-form-item">
          <label>文件路径</label><Input :value="detail?.path" disabled />
        </div>
        <div class="metadata-form-item">
          <label>文件类型</label><Input :value="detail?.fileType" disabled />
        </div>
        <div class="metadata-form-item">
          <label>文件来源</label><Input :value="detail?.dbType" disabled />
        </div>
        <div class="metadata-form-item">
          <label>文件大小</label><Input :value="metadataSize(detail?.size)" disabled />
        </div>
      </div>
      <h3 class="metadata-section">技术信息</h3>
      <div class="metadata-form-grid">
        <div class="metadata-form-item metadata-form-item--required">
          <label>数据连接名称</label><Input :value="detail?.datasourceName" disabled />
        </div>
        <div class="metadata-form-item metadata-form-item--required">
          <label>数据库类型</label><Input :value="detail?.dbType" disabled />
        </div>
        <div class="metadata-form-item metadata-form-item--required">
          <label>IP</label><Input :value="detail?.host" disabled />
        </div>
        <div class="metadata-form-item metadata-form-item--required">
          <label>端口号</label><Input :value="detail?.port" disabled />
        </div>
        <div class="metadata-form-item metadata-form-item--required">
          <label>账号</label><Input :value="detail?.username" disabled />
        </div>
        <div class="metadata-form-item">
          <label>技术责任人</label><Select
            v-model:value="form.techLeaderUserId"
            :options="users"
            :disabled="!ready"
            allow-clear
            show-search
            option-filter-prop="label"
            placeholder="请选择技术责任人"
            @change="(value) => setLeader('tech', value)"
          />
        </div>
        <div class="metadata-form-item">
          <label>技术责任人电话</label><Input
            v-model:value="form.techLeaderPhone"
            :disabled="!ready"
            placeholder="请输入技术责任人电话"
          />
        </div>
      </div>
      <h3 class="metadata-section">业务信息</h3>
      <div class="metadata-form-grid">
        <div class="metadata-form-item">
          <label>业务责任人</label><Select
            v-model:value="form.businessLeaderUserId"
            :options="users"
            :disabled="!ready"
            allow-clear
            show-search
            option-filter-prop="label"
            :placeholder="
              detail?.businessLeaderName ||
              detail?.ownerName ||
              '请选择业务责任人'
            "
            @change="(value) => setLeader('business', value)"
          />
        </div>
        <div class="metadata-form-item">
          <label>业务责任人电话</label><Input v-model:value="form.businessLeaderPhone" :disabled="!ready" />
        </div>
        <div class="metadata-form-item metadata-form-item--wide">
          <label>描述</label><TextArea
            v-model:value="form.description"
            :disabled="!ready"
            :maxlength="256"
            :rows="4"
            show-count
          />
        </div>
      </div>
      <div class="flex justify-end gap-3 px-4 py-4">
        <Button :disabled="!canSave" :loading="saving" @click="save(false)">
          暂存
</Button><Button
          @click="
            router.push({
              path: String(
                route.meta.activePath || '/govern/metadata/metaobject',
              ),
              query: { kind: 'file' },
            })
          "
        >
          返回列表
</Button><Button
          type="primary"
          :disabled="!canSave"
          :loading="saving"
          @click="save(true)"
        >
          确认并退出
        </Button>
      </div>
    </section>
  </Page>
</template>
