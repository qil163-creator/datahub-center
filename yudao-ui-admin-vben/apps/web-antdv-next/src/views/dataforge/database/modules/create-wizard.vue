<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import {
  computed,
  onBeforeUnmount,
  onDeactivated,
  ref,
  shallowRef,
  watch,
} from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, message, Spin } from 'antdv-next';

import { createDatabase } from '#/api/dataforge/database';

import {
  isDatabaseTypeSelectable,
  useDatabaseTypeSelect,
} from '../composables/use-database-type-select';
import ConnectionSteps from './connection-steps.vue';
import CreateConnectionDetails from './create-connection-details.vue';
import CreateConnectionReview from './create-connection-review.vue';
import TypeSelectPanel from './type-select-panel.vue';

const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const selector = useDatabaseTypeSelect({ navigation: 'anchor' });
const {
  activeCategory,
  categories,
  filteredTypes,
  loading,
  searchKeyword,
  selectedType,
  typeList,
} = selector;
const step = ref<0 | 1 | 2>(0);
const draft = shallowRef<Partial<FoundationDatabaseApi.Database>>({});
const connection = shallowRef<FoundationDatabaseApi.Database>();
const diagnosis = shallowRef<FoundationDatabaseApi.Diagnosis>();
const details = ref<InstanceType<typeof CreateConnectionDetails>>();
const saving = ref(false);
const moving = ref(false);
const testing = ref(false);
const loadError = ref('');
let revision = 0;

const steps = ['选择数据连接类型', '填写连接信息', '测试连接并保存'];
const hints = [
  '第一步：请选择要接入的数据连接类型。您可以通过左侧分类浏览或在搜索框中输入关键词快速查找。',
  '第二步：填写数据连接的访问信息，确认主机、账号及数据库等配置。',
  '第三步：核对连接信息并测试连接，确认后保存。',
];
const nextDisabled = computed(
  () =>
    moving.value ||
    saving.value ||
    (step.value === 0 && (!selectedType.value || loading.value)),
);

function reset() {
  revision += 1;
  step.value = 0;
  draft.value = {};
  connection.value = undefined;
  diagnosis.value = undefined;
  testing.value = false;
  saving.value = false;
  moving.value = false;
  loadError.value = '';
  selector.reset();
}

async function loadTypes() {
  const current = revision;
  loadError.value = '';
  try {
    await selector.init();
  } catch {
    if (current === revision) loadError.value = '连接类型加载失败，请重试。';
  }
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
    if (open) void loadTypes();
  },
});

async function next() {
  if (moving.value || saving.value) return;
  if (step.value === 0) {
    if (!selectedType.value || !isDatabaseTypeSelectable(selectedType.value))
      return;
    step.value = 1;
    return;
  }
  if (step.value !== 1 || !details.value) return;
  const current = revision;
  moving.value = true;
  try {
    const values = await details.value.readValues();
    if (current !== revision || !values) return;
    draft.value = { ...values };
    connection.value = { ...values };
    diagnosis.value = undefined;
    step.value = 2;
  } finally {
    if (current === revision) moving.value = false;
  }
}

async function previous() {
  if (saving.value || moving.value) return;
  if (step.value === 2) {
    step.value = 1;
    connection.value = undefined;
    diagnosis.value = undefined;
    testing.value = false;
    return;
  }
  if (step.value !== 1 || !details.value) return;
  const current = revision;
  moving.value = true;
  try {
    const values = await details.value.getValues();
    if (current !== revision) return;
    draft.value = { ...values };
    step.value = 0;
  } finally {
    if (current === revision) moving.value = false;
  }
}

async function save() {
  if (saving.value || testing.value || !connection.value || step.value !== 2)
    return;
  const current = revision;
  const values: Partial<FoundationDatabaseApi.Database> & {
    secretKey?: unknown;
  } = { ...connection.value };
  // 新增只提交连接配置；诊断结果不伪造成已持久化的在线状态。
  delete values.id;
  delete values.secretKey;
  delete values.connectionStatus;
  delete values.lastTestTime;
  delete values.lastTestMsg;
  saving.value = true;
  modalApi.lock();
  try {
    await createDatabase(values as FoundationDatabaseApi.Database);
    if (current !== revision) return;
    saving.value = false;
    modalApi.unlock();
    emit('success');
    message.success('连接信息已保存');
    await modalApi.close();
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
  <Modal title="新增连接信息" class="w-[1240px] max-w-[calc(100vw-32px)]">
    <div class="connection-wizard" data-testid="connection-wizard">
      <ConnectionSteps :step="step" :steps="steps" label="新增数据连接步骤" />
      <p class="connection-wizard__hint">{{ hints[step] }}</p>
      <section
        v-show="step === 0"
        class="connection-wizard__body"
        data-testid="connection-wizard-step-1"
      >
        <div v-if="loading" class="connection-wizard__loading">
          <Spin />正在加载连接类型…
        </div>
        <Alert
          v-else-if="loadError"
          :message="loadError"
          type="error"
          show-icon
        >
          <template #action
            ><Button @click="loadTypes"
              ><template #icon
                ><IconifyIcon icon="lucide:refresh-cw" /></template
              >重试</Button
            ></template
          >
        </Alert>
        <TypeSelectPanel
          v-else
          v-model:search-keyword="searchKeyword"
          :active-category="activeCategory"
          :all-types="typeList"
          :categories="categories"
          :selected-type="selectedType"
          :types="filteredTypes"
          @navigate="selector.selectCategory"
          @active-change="activeCategory = $event"
          @select="selector.selectType"
        />
      </section>
      <section
        v-if="selectedType"
        v-show="step === 1"
        class="connection-wizard__form"
        data-testid="connection-wizard-step-2"
      >
        <CreateConnectionDetails
          :key="String(selectedType.id)"
          ref="details"
          :selected-type="selectedType"
          :draft="draft"
        />
      </section>
      <section
        v-if="step === 2 && connection"
        class="connection-wizard__form"
        data-testid="connection-wizard-step-3"
      >
        <CreateConnectionReview
          :connection="connection"
          :selected-type="selectedType"
          @testing="testing = $event"
          @result="diagnosis = $event"
        />
      </section>
    </div>
    <template #footer>
      <div class="connection-wizard__footer">
        <span class="connection-wizard__summary">{{
          step === 0
            ? `共 ${typeList.length} 种数据连接类型`
            : selectedType?.typeName
        }}</span>
        <div class="connection-wizard__actions">
          <Button :disabled="saving" @click="modalApi.close()">取消</Button>
          <Button
            v-if="step > 0"
            :disabled="saving || moving"
            @click="previous"
          >
            上一步
          </Button>
          <Button
            v-if="step < 2"
            type="primary"
            :disabled="nextDisabled"
            :loading="moving"
            @click="next"
          >
            下一步
          </Button>
          <Button
            v-else
            type="primary"
            :loading="saving"
            :disabled="saving || testing"
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
.connection-wizard {
  --connection-border: #e8ebf1;
  --connection-selected: #f2f4ff;
  --connection-inactive: #f5f6f8;

  display: flex;
  flex-direction: column;
  gap: 18px;
  height: 600px;
  max-height: calc(100dvh - 190px);
  padding: 20px 24px 0;
  color: hsl(var(--foreground));
  background: hsl(var(--background));
}

:global(.dark .connection-wizard) {
  --connection-border: hsl(var(--border));
  --connection-selected: rgb(73 100 255 / 16%);
  --connection-inactive: hsl(var(--muted));
}

.connection-wizard__hint {
  flex-shrink: 0;
  margin: 0;
  font-size: 14px;
  line-height: 22px;
}

.connection-wizard__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.connection-wizard__form {
  flex: 1;
  min-height: 0;
  padding: 0 0 24px;
  overflow-y: auto;
}

.connection-wizard__loading {
  display: flex;
  flex: 1;
  gap: 12px;
  align-items: center;
  justify-content: center;
  color: hsl(var(--muted-foreground));
}

.connection-wizard__footer {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.connection-wizard__summary {
  color: hsl(var(--muted-foreground));
}

.connection-wizard__actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

@media (max-width: 640px) {
  .connection-wizard {
    gap: 12px;
    max-height: calc(100dvh - 180px);
    padding: 12px 12px 0;
  }

  .connection-wizard__hint {
    font-size: 14px;
    line-height: 20px;
  }

  .connection-wizard__footer {
    gap: 10px;
  }

  .connection-wizard__summary {
    font-size: 14px;
  }

  .connection-wizard__actions {
    gap: 6px;
  }
}
</style>
