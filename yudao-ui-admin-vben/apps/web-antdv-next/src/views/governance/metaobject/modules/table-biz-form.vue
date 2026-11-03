<script lang="ts" setup>
import type { GovernanceMetaObjectApi } from '#/api/governance/metaobject';

import { computed, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Input,
  message,
  RadioGroup,
  Select,
  TextArea,
} from 'antdv-next';

import {
  getMetaTableDetail,
  updateMetaTableBiz,
} from '#/api/governance/metaobject';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

defineOptions({ name: 'GovernanceMetaObjectTableBizForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<GovernanceMetaObjectApi.TableBizUpdate>({
  id: 0,
  bizRevision: 0,
});
const tableName = ref('');
const identity = ref('closed');
const ready = ref(false);
const loadFailed = ref(false);
const optionsReady = ref(false);
const access = useAccessStore();
const request = useObjectRequest(
  () => identity.value,
  () => {
    ready.value = false;
    loadFailed.value = false;
    optionsReady.value = false;
    domains.value = [];
    users.value = [];
    tableName.value = '';
  },
);

const LAYER_OPTIONS = ['ODS', 'DWD', 'DWS', 'ADS', 'DIM'].map((value) => ({
  label: value,
  value,
}));
const FLAG_OPTIONS = [
  { label: '否', value: 0 },
  { label: '是', value: 1 },
];

/** 下拉数据 */
const domains = ref<{ label: string; value: GovernanceMetaObjectApi.Id }[]>([]);
const users = ref<
  { label: string; mobile?: string; value: GovernanceMetaObjectApi.Id }[]
>([]);

function handleDomainChange(value: unknown) {
  model.subjectDomainName = domains.value.find(
    (item) => String(item.value) === String(value),
  )?.label;
}

function handleLeaderChange(field: 'business' | 'tech', value: unknown) {
  const user = users.value.find((item) => String(item.value) === String(value));
  if (!user?.mobile) {
    return;
  }
  if (field === 'tech') {
    model.techLeaderPhone = user.mobile;
  } else {
    model.businessLeaderPhone = user.mobile;
  }
}

async function loadDetail(id: GovernanceMetaObjectApi.Id) {
  const current = request.begin();
  ready.value = false;
  loadFailed.value = false;
  modalApi.setState({ loading: true, confirmDisabled: true });
  try {
    const detail = await getMetaTableDetail(id);
    if (!current()) return;
    tableName.value = detail.tableName ?? '';
    Object.assign(model, {
      id: detail.id!,
      bizRevision: detail.bizRevision!,
      customComment: detail.customComment,
      businessName: detail.businessName,
      storageType: detail.storageType,
      businessDefinition: detail.businessDefinition,
      layer: detail.layer,
      masterFlag: detail.masterFlag ?? 0,
      sensitiveFlag: detail.sensitiveFlag ?? 0,
      subjectDomainId: detail.subjectDomainId,
      subjectDomainName: detail.subjectDomainName,
      techLeaderUserId: detail.techLeaderUserId,
      techLeaderPhone: detail.techLeaderPhone,
      businessLeaderUserId: detail.businessLeaderUserId,
      businessLeaderPhone: detail.businessLeaderPhone,
      ownerDeptId: detail.ownerDeptId,
      remark: detail.remark,
    });
    ready.value = detail.bizRevision !== undefined;
    loadFailed.value = !ready.value;
    const [domainResult, userResult] = await Promise.allSettled([
      getSubjectDomainList({}),
      getSimpleUserList(),
    ]);
    if (!current()) return;
    optionsReady.value =
      domainResult.status === 'fulfilled' && userResult.status === 'fulfilled';
    if (domainResult.status === 'fulfilled')
      domains.value = domainResult.value
        .filter((item) => item.id !== undefined)
        .map((item) => ({
          label: item.name ?? `主题域#${item.id}`,
          value: item.id!,
        }));
    if (userResult.status === 'fulfilled')
      users.value = userResult.value
        .filter((item) => item.id !== undefined)
        .map((item) => ({
          label: item.nickname,
          mobile: item.mobile,
          value: item.id!,
        }));
  } catch {
    if (current()) loadFailed.value = true;
  } finally {
    if (current())
      modalApi.setState({ loading: false, confirmDisabled: !ready.value });
  }
}

const getTitle = computed(() => `编辑业务属性 - ${tableName.value}`);

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    if (!ready.value) return;
    const current = request.begin();
    modalApi.lock();
    try {
      await updateMetaTableBiz({ ...model });
      if (!current()) return;
      message.success('保存成功（已记录手工变更明细）');
      emit('success');
      await modalApi.close();
    } catch {
      if (current()) {
        ready.value = false;
        loadFailed.value = true;
        modalApi.setState({ confirmDisabled: true });
      }
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      identity.value = 'closed';
      modalApi.unlock();
      return;
    }
    const data = modalApi.getData() as GovernanceMetaObjectApi.MetaTable;
    if (data?.id === undefined) return;
    identity.value = String(data.id);
    model.id = data.id;
    await loadDetail(data.id);
  },
});
watch(
  () => [access.accessToken, access.refreshToken],
  () => modalApi.close(),
);
</script>

<template>
  <ModalComponent :title="getTitle" class="w-[800px]">
    <Alert
      v-if="loadFailed"
      class="mb-3"
      message="详情读取失败或保存发生冲突，请重新加载后编辑。"
      show-icon
      type="error"
    >
      <template #action>
        <Button size="small" @click="loadDetail(model.id)">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          重新加载
        </Button>
      </template>
    </Alert>
    <Alert
      v-else-if="ready && !optionsReady"
      class="mb-3"
      message="主题域或用户选项未能读取，暂时保留原关联；其他属性仍可编辑。"
      show-icon
      type="warning"
    />
    <div
      class="table-biz-form governance-modal-form governance-form-item-horizontal"
    >
      <div class="form-item">
        <label class="form-item__label">平台注释</label>
        <Input v-model:value="model.customComment" :maxlength="500" />
        <div class="form-item__tip">不覆盖源库注释，展示优先</div>
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">业务名称</label>
          <Input v-model:value="model.businessName" :maxlength="200" />
        </div>
        <div class="form-item">
          <label class="form-item__label">所属分层</label>
          <Select
            v-model:value="model.layer"
            :options="LAYER_OPTIONS"
            allow-clear
            class="w-full"
            placeholder="请选择分层"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">是否主表</label>
          <RadioGroup
            v-model:value="model.masterFlag"
            :options="FLAG_OPTIONS"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">是否敏感表</label>
          <RadioGroup
            v-model:value="model.sensitiveFlag"
            :options="FLAG_OPTIONS"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">主题域</label>
          <Select
            v-model:value="model.subjectDomainId"
            :disabled="!optionsReady"
            :options="domains"
            allow-clear
            class="w-full"
            option-filter-prop="label"
            placeholder="请选择主题域"
            show-search
            @change="handleDomainChange"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">技术负责人</label>
          <div class="flex gap-2">
            <Select
              v-model:value="model.techLeaderUserId"
              :disabled="!optionsReady"
              :options="users"
              allow-clear
              class="flex-1"
              option-filter-prop="label"
              placeholder="请选择"
              show-search
              @change="(value: unknown) => handleLeaderChange('tech', value)"
            />
            <Input
              v-model:value="model.techLeaderPhone"
              class="w-36"
              placeholder="电话"
            />
          </div>
        </div>
        <div class="form-item">
          <label class="form-item__label">业务负责人</label>
          <div class="flex gap-2">
            <Select
              v-model:value="model.businessLeaderUserId"
              :disabled="!optionsReady"
              :options="users"
              allow-clear
              class="flex-1"
              option-filter-prop="label"
              placeholder="请选择"
              show-search
              @change="
                (value: unknown) => handleLeaderChange('business', value)
              "
            />
            <Input
              v-model:value="model.businessLeaderPhone"
              class="w-36"
              placeholder="电话"
            />
          </div>
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">业务定义</label>
        <TextArea
          v-model:value="model.businessDefinition"
          :maxlength="1000"
          :rows="2"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">备注</label>
        <TextArea v-model:value="model.remark" :maxlength="500" :rows="2" />
      </div>
    </div>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';
</style>
