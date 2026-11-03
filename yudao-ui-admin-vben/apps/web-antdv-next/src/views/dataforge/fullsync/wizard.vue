<script setup lang="ts">
import type {
  DatabaseOption,
  DatabaseTable,
  FullSyncConfig,
  ResponsibleUser,
  Wizard,
} from '#/api/dataforge/fullsync';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Checkbox,
  ConfigProvider,
  Input,
  message,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
  theme,
} from 'antdv-next';

import {
  createDefaultConfig,
  createWizard,
  getDatabaseOptions,
  getDatabaseTables,
  getResponsibleUsers,
  getWizard,
  LIST_PATH,
  saveWizardStep,
} from '#/api/dataforge/fullsync';
import { getCronPreview } from '#/components/cron-tab/cron-preview';
import { useObjectRequest } from '#/hooks/use-object-request';

import MappingStep from './modules/mapping-step.vue';
import PropertiesStep from './modules/properties-step.vue';
import ReferenceIcon from './modules/reference-icon.vue';
import { reconcileTableSelection } from './selection';

import './wizard.css';

defineOptions({ name: 'DataforgeFullSyncWizard' });
const referenceTheme = {
  algorithm: theme.defaultAlgorithm,
  inherit: false,
  token: {
    colorPrimary: '#2663ff',
    colorText: '#333333',
    colorTextSecondary: '#909399',
    colorTextDisabled: '#a8abb2',
    colorTextPlaceholder: '#a8abb2',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    colorBgContainerDisabled: '#f5f7fa',
    colorBorder: '#dcdfe6',
    borderRadius: 2,
    controlHeight: 32,
  },
};
const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const active = ref(true);
onActivated(() => {
  active.value = true;
});
onDeactivated(() => {
  active.value = false;
});
const { hasAccessByCodes } = useAccess();
const routeId = computed(() =>
  typeof route.params.id === 'string' && /^\d+$/.test(route.params.id)
    ? route.params.id
    : undefined,
);
const editing = computed(() => !!routeId.value);
const allowed = computed(() =>
  hasAccessByCodes([
    editing.value ? 'dataforge:full-sync:update' : 'dataforge:full-sync:create',
  ]),
);
const sessionScope = computed(() =>
  JSON.stringify([access.accessToken, access.refreshToken, access.accessCodes]),
);
const scope = computed(() =>
  JSON.stringify([
    route.name,
    route.params.id,
    sessionScope.value,
    active.value,
  ]),
);
let preservedRouteId = '';
let preservedSession = '';
const config = ref<FullSyncConfig>(createDefaultConfig());
const loader = useObjectRequest(() => scope.value);
const tableLoader = useObjectRequest(
  () => `${scope.value}/${config.value.sourceDatabaseId ?? ''}`,
);
const saver = useObjectRequest(() => scope.value);
const existing = ref<Wizard>();
const databases = ref<DatabaseOption[]>([]);
const users = ref<ResponsibleUser[]>([]);
const tables = ref<DatabaseTable[]>([]);
const step = ref(0);
const loading = ref(false);
const tableLoading = ref(false);
const tableError = ref('');
const saving = ref(false);
const failed = ref(false);
const error = ref('');
const optionError = ref('');
const sourceType = ref<string>();
const targetType = ref<string>();
const leftSearch = ref('');
const rightSearch = ref('');
const sourceChecked = ref<string[]>([]);
const targetChecked = ref<string[]>([]);
const scrollArea = ref<HTMLElement>();
const mappingStep = ref<InstanceType<typeof MappingStep>>();
const steps = ['配置基础信息', '选择来源表', '配置多表映射', '配置属性'];
const databaseTypes = computed(() =>
  [...new Set(databases.value.map((item) => item.type))].map((type) => ({
    value: type,
    label: type,
  })),
);
const sourceOptions = computed(() =>
  databases.value
    .filter((item) => !sourceType.value || item.type === sourceType.value)
    .map((item) => ({ value: String(item.id), label: item.name })),
);
const targetOptions = computed(() =>
  databases.value
    .filter((item) => !targetType.value || item.type === targetType.value)
    .map((item) => ({ value: String(item.id), label: item.name })),
);
const userOptions = computed(() => {
  const options = users.value.map((user) => ({
    value: String(user.id),
    label: user.nickname,
  }));
  if (
    config.value.responsibleUserId !== undefined &&
    !options.some(
      (item) => item.value === String(config.value.responsibleUserId),
    )
  )
    options.push({
      value: String(config.value.responsibleUserId),
      label:
        config.value.responsibleName || String(config.value.responsibleUserId),
    });
  return options;
});
const selectedTables = computed(() =>
  config.value.sourceSelectedTables.map(
    (name) =>
      tables.value.find((table) => table.name === name) || {
        name,
        comment: config.value.tableProperties?.[name]?.sourceComment,
      },
  ),
);
const availableTables = computed(() =>
  tables.value.filter(
    (table) => !config.value.sourceSelectedTables.includes(table.name),
  ),
);
const filteredAvailable = computed(() =>
  availableTables.value.filter((table) =>
    tableLabel(table).toLowerCase().includes(leftSearch.value.toLowerCase()),
  ),
);
const filteredSelected = computed(() =>
  selectedTables.value.filter((table) =>
    tableLabel(table).toLowerCase().includes(rightSearch.value.toLowerCase()),
  ),
);
const busy = computed(() => loading.value || saving.value);
const writable = computed(() => allowed.value && !failed.value && !busy.value);
function databaseIcon(type?: string) {
  if (/mysql/i.test(type || '')) return 'type-mysql';
  if (/postgres/i.test(type || '')) return 'type-postgresql';
  if (/doris/i.test(type || '')) return 'type-doris';
  return '';
}
function tableLabel(table: DatabaseTable) {
  return table.comment ? `${table.name} / ${table.comment}` : table.name;
}
function replaceConfig(value: FullSyncConfig) {
  config.value = value;
}
function setSourceType(type: unknown) {
  if (typeof type !== 'string') return;
  sourceType.value = type;
  config.value.sourceDatabaseId = undefined;
  config.value.sourceSelectedTables = [];
  config.value.configMap = {};
  config.value.tableProperties = {};
  tables.value = [];
}
function setTargetType(type: unknown) {
  if (typeof type !== 'string') return;
  targetType.value = type;
  config.value.targetDatabaseId = undefined;
}
function setTargetDatabase(id: unknown) {
  if (typeof id !== 'string' && typeof id !== 'number') return;
  config.value.targetDatabaseId = id;
}
function setResponsible(id: unknown) {
  if (typeof id !== 'string' && typeof id !== 'number') return;
  const user = users.value.find((item) => String(item.id) === String(id));
  config.value.responsibleUserId = id;
  config.value.responsibleName = user?.nickname;
  config.value.responsiblePhone = user?.mobile || '';
}
function sourceChanged(id: unknown) {
  if (typeof id !== 'string' && typeof id !== 'number') return;
  config.value.sourceDatabaseId = id;
  config.value.sourceSelectedTables = [];
  config.value.configMap = {};
  config.value.tableProperties = {};
  sourceChecked.value = [];
  targetChecked.value = [];
  tables.value = [];
}
async function loadTables() {
  const id = config.value.sourceDatabaseId;
  if (id === undefined) return;
  const current = tableLoader.begin();
  tableLoading.value = true;
  tableError.value = '';
  try {
    const result = await getDatabaseTables(id);
    if (current()) tables.value = result;
  } catch {
    if (current()) tableError.value = '来源表加载失败，请重试';
  } finally {
    if (current()) tableLoading.value = false;
  }
}
async function load() {
  const current = loader.begin();
  loading.value = true;
  failed.value = false;
  error.value = '';
  optionError.value = '';
  tableError.value = '';
  sourceChecked.value = [];
  targetChecked.value = [];
  leftSearch.value = '';
  rightSearch.value = '';
  step.value = 0;
  existing.value = undefined;
  config.value = createDefaultConfig();
  tables.value = [];
  databases.value = [];
  users.value = [];
  sourceType.value = undefined;
  targetType.value = undefined;
  if (route.params.id && !routeId.value) {
    failed.value = true;
    error.value = '任务编号无效';
    loading.value = false;
    return;
  }
  if (!allowed.value) {
    loading.value = false;
    return;
  }
  try {
    const [databaseResult, userResult, detail] = await Promise.allSettled([
      getDatabaseOptions(),
      getResponsibleUsers(),
      routeId.value ? getWizard(routeId.value) : Promise.resolve(undefined),
    ]);
    if (!current()) return;
    if (databaseResult.status === 'fulfilled')
      databases.value = databaseResult.value;
    if (userResult.status === 'fulfilled') users.value = userResult.value;
    if (
      databaseResult.status === 'rejected' ||
      userResult.status === 'rejected'
    )
      optionError.value = '部分选项加载失败，请重试后继续配置。';
    if (detail.status === 'rejected') {
      failed.value = true;
      return;
    }
    if (detail.value) {
      existing.value = detail.value;
      config.value = { ...createDefaultConfig(), ...detail.value.config };
      sourceType.value = databases.value.find(
        (item) => String(item.id) === String(config.value.sourceDatabaseId),
      )?.type;
      targetType.value = databases.value.find(
        (item) => String(item.id) === String(config.value.targetDatabaseId),
      )?.type;
      const requestedStep = String(route.query.step || '1');
      if (/^[1-4]$/.test(requestedStep)) step.value = Number(requestedStep) - 1;
      if (step.value > 0) await loadTables();
    }
  } finally {
    if (current()) loading.value = false;
  }
}
function validation() {
  if (!config.value.taskName.trim()) return '请输入任务名称';
  if (config.value.taskName.trim().length > 100) return '任务名称最多100个字符';
  if (config.value.responsibleUserId === undefined) return '请选择责任人';
  if (!config.value.sourceDatabaseId) return '请选择源数据库';
  if (!config.value.targetDatabaseId) return '请选择目标数据库';
  if (step.value >= 1 && config.value.sourceSelectedTables.length === 0)
    return '请至少选择一张来源表';
  if (step.value === 2 && mappingStep.value && !mappingStep.value.validate())
    return '请完善目标表名称及追加字段的名称、数据类型';
  if (step.value >= 2) {
    if (
      (config.value.appendColumns ?? []).some(
        (column) => !column.name.trim() || !column.type,
      )
    )
      return '请完善追加字段的名称和数据类型';
    for (const table of config.value.sourceSelectedTables) {
      if (!config.value.configMap?.[table]?.targetTableName?.trim())
        return `请配置 ${table} 的目标物理表名称`;
    }
  }
  if (step.value === 3 && config.value.executionMethod === 'CRON') {
    if (!config.value.cron?.trim()) return '请配置调度周期';
    try {
      getCronPreview(config.value.cron, 'quartz');
    } catch (error) {
      return error instanceof Error ? error.message : '请检查 Cron 表达式';
    }
  }
  return '';
}
async function saveStep(final = false) {
  if (!writable.value || tableLoading.value || optionError.value) return;
  error.value = validation();
  if (error.value) return;
  const current = saver.begin();
  saving.value = true;
  const payload = JSON.parse(
    JSON.stringify({ ...config.value, taskName: config.value.taskName.trim() }),
  ) as FullSyncConfig;
  try {
    const result = existing.value
      ? await saveWizardStep(
          existing.value.id,
          step.value + 1,
          payload,
          existing.value.revision,
        )
      : await createWizard(payload);
    if (!current()) return;
    const created = !existing.value;
    existing.value = result;
    config.value = { ...createDefaultConfig(), ...result.config };
    if (final) {
      message.success('配置保存成功');
      saving.value = false;
      await router.push(LIST_PATH);
      return;
    }
    step.value++;
    sourceChecked.value = [];
    targetChecked.value = [];
    if (step.value === 1) await loadTables();
    await nextTick();
    scrollArea.value?.scrollTo({ top: 0 });
    saving.value = false;
    if (created) {
      preservedRouteId = String(result.id);
      preservedSession = sessionScope.value;
      await router.replace({
        name: 'DataforgeFullSyncWizard',
        params: { id: String(result.id) },
        query: { mode: 'edit', step: '2' },
      });
    }
  } catch {
    if (current()) error.value = '配置保存失败，请检查后重试';
  } finally {
    if (current()) saving.value = false;
  }
}
function previous() {
  if (!busy.value && step.value > 0) {
    step.value--;
    error.value = '';
    void nextTick(() => scrollArea.value?.scrollTo({ top: 0 }));
  }
}
function toggle(names: string[], name: string, checked: boolean) {
  return checked
    ? [...new Set([...names, name])]
    : names.filter((item) => item !== name);
}
function transfer(toSelected: boolean) {
  if (!writable.value) return;
  if (toSelected) {
    config.value = reconcileTableSelection(config.value, [
      ...config.value.sourceSelectedTables,
      ...sourceChecked.value,
    ]);
    sourceChecked.value = [];
  } else {
    config.value = reconcileTableSelection(
      config.value,
      config.value.sourceSelectedTables.filter(
        (name) => !targetChecked.value.includes(name),
      ),
    );
    targetChecked.value = [];
  }
}
watch(
  scope,
  () => {
    if (!active.value || route.name !== 'DataforgeFullSyncWizard') return;
    if (
      preservedRouteId === routeId.value &&
      preservedSession === sessionScope.value
    ) {
      preservedRouteId = '';
      return;
    }
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <ConfigProvider :theme="referenceTheme">
    <Page auto-content-height content-class="fws-page">
      <div class="fws-wizard">
        <div class="fws-step-panel">
          <nav class="fws-steps" aria-label="整库同步配置步骤">
            <div
              v-for="(name, index) in steps"
              :key="name"
              class="fws-step"
              :class="{ active: step === index, done: step > index }"
              :aria-current="step === index ? 'step' : undefined"
            >
              <span>{{ index + 1 }}</span>{{ name }}
            </div>
          </nav>
        </div>
        <div class="fws-main-panel">
          <Alert v-if="!allowed" type="error" title="没有整库同步配置权限" />
          <Alert v-if="failed" type="error" title="配置加载失败，请重试">
            <template #action>
              <Button @click="load">重试</Button>
            </template>
          </Alert>
          <Alert v-if="optionError" type="warning" :title="optionError">
            <template #action>
              <Button @click="load">重试</Button>
            </template>
          </Alert>
          <Alert
            v-if="error"
            type="error"
            :title="error"
            show-icon
            class="fws-error"
          />
          <Spin :spinning="loading" class="fws-body-spin">
            <fieldset ref="scrollArea" class="fws-scroll" :disabled="!writable">
              <template v-if="step === 0">
                <h2 class="fws-section-title">基础信息</h2>
                <div class="fws-basic-grid">
                  <div class="fws-field">
                    <label for="fws-task-name" class="fws-required">任务名称</label><Input
                      id="fws-task-name"
                      v-model:value="config.taskName"
                      :maxlength="100"
                      placeholder="建议格式：业务域_源库到目标层_表名/主题_同步策略(全量/增量)_调度周期"
                    />
                  </div>
                  <div class="fws-field">
                    <label for="fws-responsible" class="fws-required">责任人</label><Select
                      id="fws-responsible"
                      :value="
                        config.responsibleUserId === undefined
                          ? undefined
                          : String(config.responsibleUserId)
                      "
                      :options="userOptions"
                      show-search
                      option-filter-prop="label"
                      placeholder="请选择该任务的责任人，负责任务的日常维护、告警处理及生命周期管理"
                      @update:value="setResponsible"
                    />
                  </div>
                  <div class="fws-field">
                    <label for="fws-phone" class="fws-required">责任人电话</label><Input
                      id="fws-phone"
                      :value="config.responsiblePhone"
                      disabled
                      placeholder="请输入责任人电话"
                    />
                  </div>
                  <div class="fws-field">
                    <label>任务状态</label><RadioGroup v-model:value="config.taskStatus">
                      <Radio :value="0">未上线</Radio><Radio :value="1">已上线</Radio>
                    </RadioGroup>
                  </div>
                  <div class="fws-field fws-full-field">
                    <label for="fws-description">描述</label><TextArea
                      id="fws-description"
                      v-model:value="config.description"
                      :maxlength="256"
                      :rows="4"
                      show-count
                      placeholder="请输入描述"
                    />
                  </div>
                </div>
                <h2 class="fws-section-title fws-database-heading">
                  数据库信息
                </h2>
                <div class="fws-basic-grid">
                  <div class="fws-field">
                    <label for="fws-source-type" class="fws-required">源库类型</label><Select
                      id="fws-source-type"
                      :value="sourceType"
                      :options="databaseTypes"
                      placeholder="请选择源库类型"
                      @update:value="setSourceType"
                    >
                      <template v-if="databaseIcon(sourceType)" #prefix>
                        <ReferenceIcon :name="databaseIcon(sourceType)" />
                      </template>
                    </Select>
                  </div>
                  <div class="fws-field">
                    <label for="fws-source" class="fws-required">源数据库</label><Select
                      id="fws-source"
                      :value="
                        config.sourceDatabaseId === undefined
                          ? undefined
                          : String(config.sourceDatabaseId)
                      "
                      :options="sourceOptions"
                      :disabled="!sourceType"
                      show-search
                      option-filter-prop="label"
                      placeholder="请选择源数据库"
                      @update:value="sourceChanged"
                    />
                  </div>
                  <div class="fws-field">
                    <label for="fws-target-type" class="fws-required">目标库类型</label><Select
                      id="fws-target-type"
                      :value="targetType"
                      :options="databaseTypes"
                      placeholder="请选择目标库类型"
                      @update:value="setTargetType"
                    >
                      <template v-if="databaseIcon(targetType)" #prefix>
                        <ReferenceIcon :name="databaseIcon(targetType)" />
                      </template>
                    </Select>
                  </div>
                  <div class="fws-field">
                    <label for="fws-target" class="fws-required">目标数据库</label><Select
                      id="fws-target"
                      :value="
                        config.targetDatabaseId === undefined
                          ? undefined
                          : String(config.targetDatabaseId)
                      "
                      @update:value="setTargetDatabase"
                      :options="targetOptions"
                      :disabled="!targetType"
                      show-search
                      option-filter-prop="label"
                      placeholder="请选择目标数据库"
                    />
                  </div>
                </div>
              </template>
              <template v-else-if="step === 1">
                <h2 class="fws-section-title">选择来源表</h2>
                <p class="fws-section-description">
                  选择来源表:
                  用户可以点击中间向右的箭头按钮，将左侧已勾选的源端待同步表移动到待同步表队列中(任务执行时将对这些表执行同步传输)
                </p>
                <Alert v-if="tableError" type="error" :title="tableError">
                  <template #action>
                    <Button @click="loadTables">重试</Button>
                  </template>
                </Alert>
                <Spin :spinning="tableLoading">
                  <div class="fws-transfer">
                    <section class="fws-transfer-panel">
                      <header>
                        <Checkbox
                          :checked="
                            filteredAvailable.length > 0 &&
                            filteredAvailable.every((item) =>
                              sourceChecked.includes(item.name),
                            )
                          "
                          :indeterminate="
                            sourceChecked.length > 0 &&
                            !filteredAvailable.every((item) =>
                              sourceChecked.includes(item.name),
                            )
                          "
                          @update:checked="
                            sourceChecked = $event
                              ? filteredAvailable.map((item) => item.name)
                              : []
                          "
                        >
                          来源表
</Checkbox><span>{{ sourceChecked.length }}/{{
                            availableTables.length
                          }}</span>
                      </header>
                      <div class="fws-transfer-search">
                        <Input
                          v-model:value="leftSearch"
                          placeholder="请输入物理表名称"
                          allow-clear
                        /><span
                          class="fws-search-symbol"
                          aria-hidden="true"
                        ></span>
                      </div>
                      <div class="fws-transfer-list">
                        <label
                          v-for="table in filteredAvailable"
                          :key="table.name"
                          :title="tableLabel(table)"
                          ><Checkbox
                            :checked="sourceChecked.includes(table.name)"
                            @update:checked="
                              sourceChecked = toggle(
                                sourceChecked,
                                table.name,
                                $event,
                              )
                            "
                            >{{ tableLabel(table) }}</Checkbox></label>
                        <p v-if="!filteredAvailable.length" class="fws-empty">
                          暂无来源表
                        </p>
                      </div>
                    </section>
                    <div class="fws-transfer-controls">
                      <Button
                        type="primary"
                        :disabled="targetChecked.length === 0"
                        aria-label="移除已选来源表"
                        @click="transfer(false)"
                      >
                        ‹
</Button><Button
                        type="primary"
                        :disabled="sourceChecked.length === 0"
                        aria-label="添加来源表"
                        @click="transfer(true)"
                      >
                        ›
                      </Button>
                    </div>
                    <section class="fws-transfer-panel">
                      <header>
                        <Checkbox
                          :checked="
                            filteredSelected.length > 0 &&
                            filteredSelected.every((item) =>
                              targetChecked.includes(item.name),
                            )
                          "
                          :indeterminate="
                            targetChecked.length > 0 &&
                            !filteredSelected.every((item) =>
                              targetChecked.includes(item.name),
                            )
                          "
                          @update:checked="
                            targetChecked = $event
                              ? filteredSelected.map((item) => item.name)
                              : []
                          "
                        >
                          已选来源表
</Checkbox><span>{{ targetChecked.length }}/{{
                            selectedTables.length
                          }}</span>
                      </header>
                      <div class="fws-transfer-search">
                        <Input
                          v-model:value="rightSearch"
                          placeholder="请输入物理表名称"
                          allow-clear
                        /><span
                          class="fws-search-symbol"
                          aria-hidden="true"
                        ></span>
                      </div>
                      <div class="fws-transfer-list">
                        <label
                          v-for="table in filteredSelected"
                          :key="table.name"
                          :title="tableLabel(table)"
                          ><Checkbox
                            :checked="targetChecked.includes(table.name)"
                            @update:checked="
                              targetChecked = toggle(
                                targetChecked,
                                table.name,
                                $event,
                              )
                            "
                            >{{ tableLabel(table) }}</Checkbox></label>
                        <p v-if="!filteredSelected.length" class="fws-empty">
                          暂无已选来源表
                        </p>
                      </div>
                    </section>
                  </div>
                </Spin>
              </template>
              <MappingStep
                ref="mappingStep"
                v-else-if="step === 2"
                :config="config"
                :tables="tables"
                @update:config="replaceConfig"
              />
              <PropertiesStep
                v-else
                :config="config"
                @update:config="replaceConfig"
              />
            </fieldset>
          </Spin>
          <footer class="fws-footer">
            <Button
              type="primary"
              :disabled="saving"
              @click="router.push(LIST_PATH)"
            >
              <ReferenceIcon name="back" />返回列表
</Button><Button
              v-if="step > 0"
              class="fws-light-button"
              :disabled="busy"
              @click="previous"
            >
              上一步
</Button><Button
              v-if="step < 3"
              class="fws-light-button"
              :loading="saving"
              :disabled="!writable || tableLoading || !!optionError"
              @click="saveStep()"
            >
              下一步
</Button><Button
              v-else
              type="primary"
              :loading="saving"
              :disabled="!writable"
              @click="saveStep(true)"
            >
              保存
            </Button>
          </footer>
        </div>
      </div>
    </Page>
  </ConfigProvider>
</template>
