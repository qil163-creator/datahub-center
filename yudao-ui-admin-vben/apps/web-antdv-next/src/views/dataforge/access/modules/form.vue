<script lang="ts" setup>
import type { DataforgeAccessApi } from '#/api/dataforge/access';
import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { RunId } from '#/api/dataforge/execution/command';

import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { CodeEditor } from '@vben/plugins/code-editor';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  AutoComplete,
  Button,
  Checkbox,
  Descriptions,
  DescriptionsItem,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  Select,
  Steps,
  Switch,
  Table,
  TextArea,
  Transfer,
  TreeSelect,
} from 'antdv-next';

import {
  createAccess,
  getAccess,
  previewColumnMap,
  previewTableMap,
  updateAccess,
} from '#/api/dataforge/access';
import {
  getDatabaseSimpleList,
  getDatabaseTableList,
} from '#/api/dataforge/database';
import { getDeptList } from '#/api/system/dept';
import { CronTab } from '#/components/cron-tab';
import FormWizardSteps from '#/components/form-wizard-steps/index.vue';
import {
  defaultApiConfig,
  validateApiConfig,
} from '#/views/dataforge/dataapi/modules/api-config';
import ApiConfigEditor from '#/views/dataforge/dataapi/modules/api-config-editor.vue';

import {
  ACCESS_MODE_OPTIONS,
  CHANGE_DATA_SYNC_TYPE_OPTIONS,
  CONFIG_FORMAT_OPTIONS,
  ENGINE_TYPE_OPTIONS,
  INCLUDE_OR_EXCLUDE_OPTIONS,
  NAME_CASE_OPTIONS,
  optionLabel,
  savedNameCase,
  SOURCE_TYPE_OPTIONS,
  TASK_TYPE_OPTIONS,
} from '../data';
import FormShell from './form-shell.vue';
import TableMapping from './table-mapping.vue';
import TypeMappingDecisions from './type-mapping-decisions.vue';

defineOptions({ name: 'DataforgeAccessForm' });

const props = defineProps<{
  embedded?: boolean;
  initialData?: { engineType?: number; id?: RunId };
}>();
const emit = defineEmits(['success', 'close']);
const initializing = ref(false);
const access = useAccessStore();
const user = useUserStore();
let generation = 0;
let sourceRequest = 0;
let sqlRequest = 0;
let previewRequest = 0;
let mappingRequest = 0;
let opened = false;
let restoringSavedDeclarations = false;
const saving = ref(false);

/** 离线同步按基础信息、来源表、多表映射、任务属性四步配置。 */
type StepKey =
  | 'apiaccess'
  | 'apiconfig'
  | 'attributes'
  | 'basic'
  | 'confirm'
  | 'mapper'
  | 'seatunnel'
  | 'source';

const STEP_META: Record<
  StepKey,
  { desc: string; rail: string; title: string }
> = {
  basic: {
    title: '配置基础信息',
    rail: '任务与数据库',
    desc: '填写任务信息，选择来源数据库和目标数据库',
  },
  source: {
    title: '选择来源表',
    rail: '选择同步范围',
    desc: '将需要同步的来源表加入右侧列表，也可以使用自定义查询',
  },
  attributes: {
    title: '配置任务属性',
    rail: '调度与写入策略',
    desc: '设置运行周期、写入策略与批处理参数，保存后可在任务列表发布',
  },
  mapper: {
    title: '配置多表映射',
    rail: '目标表与建表配置',
    desc: '设置目标表名称和说明，入中台 Doris 库时可按表或批量配置分区、分桶与副本',
  },
  seatunnel: {
    title: 'Seatunnel 配置',
    rail: '作业配置怎么写',
    desc: '粘贴 Seatunnel 作业配置，提交到集群运行',
  },
  apiaccess: {
    title: '配置目标表与映射',
    rail: '目标表与字段映射',
    desc: '选择目标数据源，指定目标表并配置接口返回字段与目标字段的映射',
  },
  apiconfig: {
    title: '配置接口与试调',
    rail: '请求与返回字段',
    desc: '填写请求地址、参数和分页规则，试调接口并解析返回字段',
  },
  confirm: {
    title: '确认提交',
    rail: '核对整条链路',
    desc: '核对配置无误后保存，发布后开始调度',
  },
};

const DBSWITCH_STEPS: StepKey[] = ['basic', 'source', 'mapper', 'attributes'];
const SEATUNNEL_STEPS: StepKey[] = ['basic', 'seatunnel', 'confirm'];
const API_ACCESS_STEPS: StepKey[] = [
  'basic',
  'apiconfig',
  'apiaccess',
  'attributes',
];

const currentStep = ref(0);
const isSyncEngine = computed(
  () => model.engineType === 1 || model.engineType === 2,
);
const isHorizontalWizard = computed(() => props.embedded || isSyncEngine.value);

const stepKeys = computed(() => {
  if (model.engineType === 2) {
    return SEATUNNEL_STEPS;
  }
  if (model.engineType === 3) {
    return API_ACCESS_STEPS;
  }
  return DBSWITCH_STEPS;
});

const currentKey = computed<StepKey>(
  () => stepKeys.value[currentStep.value] ?? 'basic',
);

const railItems = computed(() =>
  stepKeys.value.map((key) => ({
    title: STEP_META[key].title,
    description: isSyncEngine.value ? undefined : STEP_META[key].rail,
    disabled: saving.value,
  })),
);

/** 调度类型选项：实时同步仅 Seatunnel 引擎可选 */
const taskTypeOptions = computed(() =>
  (model.engineType === 2
    ? TASK_TYPE_OPTIONS
    : TASK_TYPE_OPTIONS.filter((item) => item.value !== 1)
  ).map(({ label, value }) => ({ label, value })),
);

/** 切换引擎：非 Seatunnel 引擎不支持实时调度，回退为一次性全量 */
function handleEngineChange() {
  currentStep.value = 0;
  if (model.engineType !== 2 && model.taskType === 1) {
    model.taskType = 2;
  }
}

/** 点击流程轨跳转：往回直接跳，往前逐步校验 */
function handleStepChange(next: number) {
  if (saving.value) return;
  if (next <= currentStep.value) {
    currentStep.value = next;
    return;
  }
  for (let step = currentStep.value; step < next; step++) {
    if (!validateStep(step)) {
      currentStep.value = step;
      return;
    }
  }
  currentStep.value = next;
}

interface ApiMappingDraft extends DataforgeAccessApi.ApiAccessMapping {
  apiConfig: DataforgeDataApiApi.DataApi;
  mappingKey: string;
}

interface FormModel extends DataforgeAccessApi.DataAccess {
  apiMappings: ApiMappingDraft[];
  tableNameMapper: DataforgeAccessApi.PatternMapper[];
  columnNameMapper: DataforgeAccessApi.PatternMapper[];
  typeMappingAcknowledgements: DataforgeAccessApi.TypeMappingAcknowledgement[];
  targetTableNameCase: DataforgeAccessApi.NameCase;
  targetColumnNameCase: DataforgeAccessApi.NameCase;
}

function defaultModel(): FormModel {
  return {
    schemaVersion: 1,
    typeMappingAcknowledgements: [],
    cursorWatermarkContract: undefined,
    cursorBootstrapAllowed: false,
    identitySourceQuiesced: false,
    preserveIdentityValues: false,
    exclusiveTargetOwnership: false,
    tableReadMode: 'INCLUDE_DESCENDANTS',
    id: undefined,
    taskName: '',
    description: '',
    orgId: undefined,
    engineType: 1,
    taskType: 2,
    cron: '',
    sourceType: 1,
    sourceDatabaseId: undefined,
    includeOrExclude: 1,
    sourceSelectedTables: [],
    sourceSql: '',
    sourcePrimaryKeys: [],
    accessMode: 1,
    targetDatabaseId: undefined,
    targetTable: '',
    targetDropTable: false,
    targetTruncateData: false,
    targetSyncExit: true,
    targetOnlyCreate: false,
    targetIndexCreate: false,
    syncDefaultVal: false,
    // 旧版大小写开关已由下面两个转换方式取代，始终以关闭状态提交
    targetLowerCase: false,
    targetUpperCase: false,
    targetTableNameCase: 'NONE',
    targetColumnNameCase: 'NONE',
    targetAutoIncrement: false,
    targetDataSync: false,
    changeDataSyncType: '',
    increaseColumnName: '',
    batchSize: 5000,
    mapperType: 'ALL',
    tableNameMapper: [],
    columnNameMapper: [],
    seatunnelConfig: '',
    configFormat: 'json',
    apiMappings: [],
    remark: '',
  };
}

const model = reactive<FormModel>(defaultModel());
const isEdit = computed(() => !!model.id);

/** 引擎类型是否由入口页面锁定（如接口采集页固定为 API 接入） */
const lockEngine = ref(false);

const engineOptions = computed(() => {
  const options =
    lockEngine.value || model.engineType === 3
      ? ENGINE_TYPE_OPTIONS
      : ENGINE_TYPE_OPTIONS.filter((item) => item.value !== 3);
  return options.map(({ label, value }) => ({ label, value }));
});

/** 数据源 / 单位选项 */
const databaseList = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const sourceProduct = computed(
  () =>
    databaseList.value.find(
      (item) => String(item.id) === String(model.sourceDatabaseId),
    )?.productId,
);
const targetProduct = computed(
  () =>
    databaseList.value.find(
      (item) => String(item.id) === String(model.targetDatabaseId),
    )?.productId,
);
const dorisTarget = computed(
  () => model.accessMode === 1 && targetProduct.value === 'DORIS',
);
// Display help only. Product admission comes exclusively from the backend registry snapshot.
const CURSOR_PRODUCT_HINTS: readonly {
  label: string;
  prerequisite: string;
  product: string;
}[] = [
  {
    product: 'CLICKHOUSE',
    label: 'ClickHouse',
    prerequisite:
      'ClickHouse：读取按固定上界 keyset 分页，管理员须配置源库部署代次；无快照，窗口漂移会使本轮失败。目标使用已实现的原生更新及回读校验；仅登记的产品方向可提交，具体键类型仍须预检。',
  },
  {
    product: 'TDENGINE',
    label: 'TDengine',
    prerequisite:
      'TDengine：仅已验证的叶表时间戳键路径，管理员须配置源库部署代次；无快照，按固定上界读取并保留时间单位。向 PostgreSQL 写入时须使用匹配的整数时间载体，不能推断时区或时间单位。',
  },
  {
    product: 'POSTGRESQL',
    label: 'PostgreSQL',
    prerequisite:
      'PostgreSQL 16：物理表须有非空唯一主键，水位使用精确数值或日期时间类型。管理员须配置绑定该源库的部署代次；恢复备份、克隆或更换源库后须轮换代次。',
  },
  {
    product: 'MYSQL',
    label: 'MySQL',
    prerequisite:
      'MySQL 8：使用原生 InnoDB 实体表，并授予运行账号读取物理表身份所需的目录权限。管理员须配置源库部署代次；恢复或替换源库后须轮换代次。',
  },
  {
    product: 'MARIADB',
    label: 'MariaDB',
    prerequisite:
      'MariaDB：已验证 11.4 与原生 Connector/J 3.5.10，使用 InnoDB 实体表和可见的物理表目录。须使用管理员批准的直连配置及部署代次，禁用自动重放和连接重定向；恢复或替换源库后须轮换代次。',
  },
  {
    product: 'TIDB',
    label: 'TiDB',
    prerequisite:
      'TiDB：已验证 8.5.6 / TiKV。管理员须先初始化受管源代次并配置对应部署绑定；读取固定快照时还会独立核对当前代次，源连接池至少需要 2 个连接。恢复或替换前须停机隔离并轮换代次。',
  },
  {
    product: 'SQLITE3',
    label: 'SQLite',
    prerequisite:
      'SQLite：数据源须指向服务器上的绝对本地文件。管理员须显式初始化该文件的源代次标记并配置部署绑定；页面不会创建标记。水位须为非空 INTEGER 且实际值均为整数，唯一键须非空并按 BINARY 升序比较；不接受隐式 rowid。恢复文件后须停机隔离并轮换标记和部署代次。',
  },
  {
    product: 'DM',
    label: '达梦 DM',
    prerequisite:
      '达梦 DM 8：源表水位和主键须为非空精确数值类型。管理员须配置直连端点、固定驱动和保存在库外的源部署代次，并授予读取物理表目录和持有表锁的权限。恢复、克隆或更换源库前须停止旧任务、完成隔离并轮换部署代次。',
  },
  {
    product: 'SQLSERVER',
    label: 'SQL Server',
    prerequisite:
      'SQL Server：已验证 2022（16.x）。源库须由 DBA 预先开启 ALLOW_SNAPSHOT_ISOLATION，读取在原生 SNAPSHOT 隔离内进行且不会修改数据库设置；水位与主键须为非空精确数值、日期时间或已验证排序规则的文本类型。管理员须先用管理命令在源库初始化受管源代次并配置部署绑定，缺少部署代次或主键顺序变化会在读取前失败；源表 DDL 会使当前快照失效。恢复、还原或克隆源库前须 fence 并轮换代次。不包含 SQL Server 2000。',
  },
  {
    product: 'SQLSERVER2000',
    label: 'SQL Server 2000',
    prerequisite:
      'SQL Server 2000：已验证原版 8.00.760，仅普通主键表，水位与主键须为非空精确数值。读取持有表锁并阻塞并发写入；目标按表串行更新或插入。管理员须在源库与目标库初始化受管部署代次并配置绑定；恢复、清空或更换任一端前须停机排空、隔离并轮换代次，再显式重新引导。仅同产品方向已验证。',
  },
  {
    product: 'OSCAR',
    label: '神通 OSCAR',
    prerequisite:
      '神通 OSCAR：已验证 V8.0.251217，仅普通主键表和大写普通物理名，水位与主键须为非空精确数值。读取持有表锁并阻塞并发写入；目标按表串行合并写入。管理员须初始化受管源部署代次并配置绑定；恢复、清空或更换源库前须停机排空、隔离并轮换代次。仅同产品方向已验证。',
  },
  {
    product: 'OPENGAUSS',
    label: 'openGauss',
    prerequisite:
      'openGauss：已验证 7.0 RC3（A 兼容模式）。源表水位与主键须为非空数值类型；读取使用 REPEATABLE READ 只读快照并持有 ACCESS SHARE 锁，允许并发 DML 但阻止 TRUNCATE。管理员须配置与物理表身份绑定的源部署代次，恢复或更换源库后须轮换。目标为 openGauss 时按显式数值主键做事务性 MERGE，提交确认为 JDBC 提交返回，不是服务器事务见证。',
  },
  {
    product: 'OCEANBASE',
    label: 'OceanBase',
    prerequisite:
      'OceanBase：已验证 CE 4.3.5.6 MySQL 模式与 4.2.1.7 Oracle 模式，使用 Connector/J 2.4.12。水位与主键须符合当前租户模式的精确类型契约；两种模式不能互相推断兼容。Oracle 模式目标合并写入按表串行，MySQL 模式按完整非空唯一索引重放。管理员须配置绑定实际租户身份的源部署代次；恢复、克隆或切换源库后须停机排空并轮换代次（无库内 fence）。',
  },
  {
    product: 'MONGODB',
    label: 'MongoDB',
    prerequisite:
      'MongoDB：已验证 7.0 standalone 与 mongodb-jdbc-driver 4.10.2，仅普通集合与简单排序规则。水位字段全集须为单一 BSON 类型族（int32/int64/decimal128/date），文本、二进制、数组、ObjectId、double、null 与缺失字段在读取前拒绝；读取无事务快照，窗口内并发 DML 会使本轮失败关闭。管理员须配置绑定主机/版本/线协议的部署代次；恢复或克隆后须轮换代次。目标按 _id 单唯一键做 majority+j 原生 upsert。',
  },
  {
    product: 'INFLUXDB3',
    label: 'InfluxDB3',
    prerequisite:
      'InfluxDB3：已验证 Core（Apache DataFusion 51.0.0）与 flight-sql-jdbc-driver 18.1.0。水位为测量内 (tagset, time) 纳秒 keyset，无快照；tag 值须可无损表示为 SQL 字面量（非空、无控制字符、≤1024 字符）。管理员须配置绑定引擎 version() 的部署代次；恢复或升级后须轮换代次。目标按整批 line-protocol 写入（accept_partial=false），回执丢失保持未知、不推进水位。',
  },
  {
    product: 'ORACLE',
    label: 'Oracle',
    prerequisite:
      'Oracle：已验证 AI Database 26ai Free 23.26.3（FREEPDB1）与 ojdbc11 23.26.3。读取使用 SERIALIZABLE 首读快照并持有 ROW SHARE 锁（阻断 TRUNCATE/DROP、放行并发 DML）；水位与主键须为非空精确数值或日期时间。管理员须配置绑定 PDB 身份（CON_NAME/CON_DBID/CON_ID）的部署代次；恢复、克隆或更换源库后须轮换代次（无库内 fence）。目标按单一唯一键做原生 MERGE。仅 Oracle→Oracle 已验证。',
  },
  {
    product: 'GREENPLUM',
    label: 'Greenplum',
    prerequisite:
      'Greenplum：已验证 7.1.0（协调器 + primary segments）heap/hash 分布。读取使用 REPEATABLE READ 快照并持有 ACCESS SHARE 锁；水位与主键须为非空数值。管理员须配置绑定物理表身份的部署代次；恢复或更换源库后须轮换代次。目标按仲裁键含分布键的唯一约束做 ON CONFLICT；AO/REPLICATED/RANDOMLY 分布与触发器拒绝。',
  },
  {
    product: 'DORIS',
    label: 'Doris',
    prerequisite:
      'Doris：已验证 4.0.8 唯一键模型。无快照隔离，读取按固定上界 keyset 并逐页复核关系身份，窗口内并发 DML 失败关闭；水位须为精确数值或日期时间（文本仅同产品同契约）。管理员须配置绑定 ClusterId/DbId 的部署代次；恢复或更换源库后须轮换代次。目标按唯一键模型全行替换写入。',
  },
  {
    product: 'STARROCKS',
    label: 'StarRocks',
    prerequisite:
      'StarRocks：已验证 3.5.21 主键模型。无快照隔离，读取按固定上界 keyset 并逐页复核关系身份，窗口内并发 DML 失败关闭；水位须为精确数值或日期时间（文本仅同产品同契约）。管理员须配置绑定 ClusterId/DbId 的部署代次；恢复或更换源库后须轮换代次。目标按主键模型全行替换写入。',
  },
  {
    product: 'ELASTICSEARCH',
    label: 'Elasticsearch',
    prerequisite:
      'Elasticsearch：已验证 8.15.3（安全关闭、单节点）。水位字段须为映射明确的排序类型（keyword/date_nanos/unsigned_long），按 search_after 分页；text/boolean/float 与 _id 水位拒绝；无快照，窗口漂移以 refresh+count 失败关闭。管理员须配置绑定 cluster+index UUID 与 8.x build 身份的部署代次；快照/克隆保留 UUID 不可观测，须轮换代次。目标按 _id 单键 index upsert（refreshed bulk 回执）。',
  },
  {
    product: 'KINGBASE',
    label: 'KingbaseES',
    prerequisite:
      'KingbaseES：已验证 V008R006C009B0014（oracle 模式、ci 排序、UTF8）。读取快照与锁行为以服务端实测为准；水位限精确数值/日期时间，文本键仅同产品同 ci 档案；空文本与 NUL 写入前拒绝。管理员须配置部署代次；恢复或更换源库后须轮换。目标按主键做 ON CONFLICT 原生 upsert，回执为 JDBC 提交返回。',
  },
  {
    product: 'GBASE8A',
    label: 'GBase 8a',
    prerequisite:
      'GBase 8a：已验证 8.6.2.43 Express。无快照隔离，读取按固定上界 keyset 并逐页复核代次与关系证据；水位限精确数值/日期时间。目标合并写入在排他表锁下重新验证非空键的实际唯一性，不依赖主键声明。管理员须配置源部署代次，并为 GBase 目标初始化受管部署代次；恢复、清空或更换前须停机排空、隔离并轮换代次，再显式重新引导。',
  },
  {
    product: 'HIGHGO',
    label: 'HighGo',
    prerequisite:
      'HighGo：已验证 6.0.4.2-cluster（server_version 12.7、UTF8）。读取使用 REPEATABLE READ 快照并持有 ACCESS SHARE 锁（阻断 TRUNCATE/ALTER）；水位限精确数值/日期时间，TIME 24:00:00 读取拒绝。管理员须配置部署代次；恢复或更换源库后须轮换。目标按唯一键做 ON CONFLICT 原生 upsert，回执为 JDBC 提交返回。',
  },
  {
    product: 'SYBASE',
    label: 'Sybase ASE',
    prerequisite:
      'Sybase ASE：已验证 16.0 SP02 PL02。读取使用隔离级 3（阻塞并发写）；水位限精确数值/日期时间；含引号/括号的物理名写入前拒绝。管理员须配置部署代次；恢复或更换源库后须轮换。目标按主键做原生 MERGE，回执为 JDBC 提交返回。',
  },
  {
    product: 'DB2',
    label: 'DB2',
    prerequisite:
      'DB2：已验证 Community v12.1.5.0。读取使用 RR 隔离（SET CURRENT ISOLATION 生效并可回读）；水位限精确数值/日期时间（文本键仅同产品同代码页）。管理员须配置部署代次；恢复或更换源库后须轮换。目标按主键做原生 MERGE（每批目标表独占锁），回执为 JDBC 提交返回。',
  },
  {
    product: 'HIVE',
    label: 'Hive',
    prerequisite:
      'Hive：已验证 4.1.0 standalone。读取按每查询文件快照与固定上界 keyset；水位限精确数值/日期时间（TIMESTAMP 字面量须含秒）。管理员须配置绑定目录身份的部署代次；同秒重建/原地重载不可观测，须轮换代次。目标 UPSERT 仅限预建的全 ACID ORC 表（平台自动建表非 ACID，预检拒绝）。',
  },
  {
    product: 'UXDB',
    label: 'UXDB',
    prerequisite:
      'UXDB：已验证 UXsinoDB 2.1.0.3（ux_catalog、PG10 目录形状）。读取使用 REPEATABLE READ 快照并持有 ACCESS SHARE 锁；水位限精确数值/日期时间，TIME 24:00:00 读取拒绝，文本键仅同产品同排序规则。管理员须配置部署代次；恢复或更换源库后须轮换。目标按唯一键做 ON CONFLICT 原生 upsert，回执为 JDBC 提交返回。',
  },
];
const cursorTargets = computed<readonly string[]>(() => {
  const targets = databaseList.value.find(
    (item) => String(item.id) === String(model.sourceDatabaseId),
  )?.cursorTargets;
  if (
    !Array.isArray(targets) ||
    targets.some((target) => typeof target !== 'string')
  )
    return [];
  return [...new Set(targets)];
});
const cursorSource = computed(() => {
  if (!sourceProduct.value || cursorTargets.value.length === 0)
    return undefined;
  return (
    CURSOR_PRODUCT_HINTS.find(
      (item) => item.product === sourceProduct.value,
    ) ?? {
      product: sourceProduct.value,
      label: sourceProduct.value,
      prerequisite:
        '管理员须配置源库部署代次；实际驱动版本、表结构、水位和唯一键语义将在执行前校验。',
    }
  );
});
const cursorPairAvailable = computed(() =>
  Boolean(
    targetProduct.value && cursorTargets.value.includes(targetProduct.value),
  ),
);
const cursorPairHint = computed(() => {
  if (!cursorSource.value)
    return '当前源端尚未声明可恢复游标迁移方向，请选择已配置该能力的数据源。';
  const targets = cursorTargets.value
    .map(
      (product) =>
        CURSOR_PRODUCT_HINTS.find((item) => item.product === product)?.label ??
        product,
    )
    .join('、');
  return `${cursorSource.value.label} 源端当前声明的目标：${targets}。执行前仍须验证驱动、结构和键语义。`;
});
function clearCursorDeclarations() {
  model.cursorWatermarkContract = undefined;
  model.cursorBootstrapAllowed = false;
}
const identityTransferAvailable = computed(
  () =>
    model.engineType === 1 &&
    model.sourceType === 1 &&
    !model.targetDataSync &&
    !model.targetOnlyCreate &&
    sourceProduct.value === targetProduct.value &&
    ['POSTGRESQL', 'SQLSERVER'].includes(sourceProduct.value ?? ''),
);
const sqlServerIdentityTransfer = computed(
  () => identityTransferAvailable.value && sourceProduct.value === 'SQLSERVER',
);

function clearIdentityDeclarations() {
  model.preserveIdentityValues = false;
  model.exclusiveTargetOwnership = false;
  model.identitySourceQuiesced = false;
}
const deptTree = ref<any[]>([]);
const sourceProductFilter = ref<string>();
const targetProductFilter = ref<string>();
const databaseTypeOptions = computed(() => [
  ...new Map(
    databaseList.value
      .filter((item) => item.productId)
      .map((item) => [
        item.productId!,
        {
          label: item.databaseTypeName || item.productId!,
          value: item.productId!,
        },
      ]),
  ).values(),
]);

const databaseOptions = computed(() =>
  databaseList.value.map((item) => ({
    label: `${item.name}${item.usageType === 2 ? '（中台库）' : ''}`,
    value: item.id,
  })),
);
const sourceDatabaseOptions = computed(() =>
  databaseOptions.value.filter(
    (option) =>
      !sourceProductFilter.value ||
      databaseList.value.some(
        (item) =>
          String(item.id) === String(option.value) &&
          item.productId === sourceProductFilter.value,
      ),
  ),
);

/** 入中台库模式下目标端只能选中台库 */
const targetDatabaseOptions = computed(() => {
  const list =
    model.accessMode === 1
      ? databaseList.value.filter((item) => item.usageType === 2)
      : databaseList.value;
  return list
    .filter(
      (item) =>
        !targetProductFilter.value ||
        item.productId === targetProductFilter.value,
    )
    .map((item) => ({
      label: `${item.name}${item.usageType === 2 ? '（中台库）' : ''}`,
      value: item.id,
    }));
});

function changeSourceProduct() {
  if (
    !sourceDatabaseOptions.value.some(
      (item) => String(item.value) === String(model.sourceDatabaseId),
    )
  ) {
    model.sourceDatabaseId = undefined;
    handleSourceDatabaseChange();
  }
}

function changeTargetProduct() {
  if (
    !targetDatabaseOptions.value.some(
      (item) => String(item.value) === String(model.targetDatabaseId),
    )
  ) {
    model.targetDatabaseId = undefined;
  }
}

/** 源端表清单 */
const sourceTables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const sourceTablesLoading = ref(false);
const sourceTablesError = ref('');
const tableScope = ref<'ALL' | 'EXCLUDE' | 'SELECTED'>('SELECTED');

const sourceTableOptions = computed(() =>
  sourceTables.value
    .filter((item) => !item.viewTable)
    .map((item) => ({
      label: item.remarks
        ? `${item.tableName}（${item.remarks}）`
        : item.tableName,
      value: item.tableName,
    })),
);

const sourceTransferItems = computed(() => {
  const items = sourceTableOptions.value.map((item) => ({
    key: item.value,
    title: item.label,
  }));
  for (const name of model.sourceSelectedTables ?? []) {
    if (!items.some((item) => item.key === name)) {
      items.push({ key: name, title: `${name}（当前未找到）` });
    }
  }
  return items;
});

function changeTableScope() {
  model.includeOrExclude = tableScope.value === 'EXCLUDE' ? 0 : 1;
  if (tableScope.value === 'ALL') model.sourceSelectedTables = [];
}

function changeSelectedTables(keys: (number | string)[]) {
  model.sourceSelectedTables = keys.map(String);
}

function removeSourceTable(name: string) {
  if (tableScope.value === 'SELECTED') {
    model.sourceSelectedTables = (model.sourceSelectedTables ?? []).filter(
      (table) => table !== name,
    );
    if (model.sourceSelectedTables.length === 0)
      currentStep.value = stepKeys.value.indexOf('source');
  } else {
    tableScope.value = 'EXCLUDE';
    model.includeOrExclude = 0;
    model.sourceSelectedTables = [
      ...new Set([...(model.sourceSelectedTables ?? []), name]),
    ];
  }
}

async function loadSourceTables() {
  const request = ++sourceRequest;
  const sourceId = model.sourceDatabaseId;
  sourceTables.value = [];
  sourceTablesLoading.value = false;
  sourceTablesError.value = '';
  if (!model.sourceDatabaseId) {
    sourceTables.value = [];
    return;
  }
  sourceTablesLoading.value = true;
  try {
    const tables = await getDatabaseTableList(model.sourceDatabaseId);
    if (
      request === sourceRequest &&
      opened &&
      String(sourceId) === String(model.sourceDatabaseId)
    )
      sourceTables.value = tables;
  } catch {
    if (request === sourceRequest && opened) {
      sourceTables.value = [];
      sourceTablesError.value = '来源表加载失败，请重试';
    }
  } finally {
    if (request === sourceRequest) sourceTablesLoading.value = false;
  }
}

function handleSourceDatabaseChange() {
  model.sourceSelectedTables = [];
  model.configMap = undefined;
  tableScope.value = 'SELECTED';
  model.includeOrExclude = 1;
  previewColumnTable.value = undefined;
  sqlColumns.value = [];
  loadSourceTables();
}

/** 解析 SQL：调用字段映射预览接口获取 SQL 结果字段，供主键/增量字段选择 */
const sqlColumns = ref<string[]>([]);
const sqlParsing = ref(false);

const sqlColumnOptions = computed(() =>
  sqlColumns.value.map((name) => ({ label: name, value: name })),
);

async function handleParseSql() {
  if (!model.sourceDatabaseId) {
    message.warning('请先选择源端数据源');
    return;
  }
  if (!model.sourceSql?.trim()) {
    message.warning('请先填写源端 SQL（单条 SELECT）');
    return;
  }
  const request = ++sqlRequest;
  sqlParsing.value = true;
  try {
    const columns = await previewColumnMap({
      sourceDatabaseId: model.sourceDatabaseId,
      sourceType: 2,
      sourceSql: model.sourceSql,
      mapperType: 'ALL',
      columnNameMapper: [],
    });
    if (request !== sqlRequest || !opened) return;
    sqlColumns.value = columns.map((item) => item.originalName);
    message.success(`解析成功，共 ${sqlColumns.value.length} 个字段`);
  } finally {
    if (request === sqlRequest) sqlParsing.value = false;
  }
}

function handleAccessModeChange() {
  targetProductFilter.value = undefined;
  // 切换接入方式后目标数据源可能不在可选范围内，清空重选
  if (
    model.targetDatabaseId &&
    !targetDatabaseOptions.value.some(
      (item) => String(item.value) === String(model.targetDatabaseId),
    )
  ) {
    model.targetDatabaseId = undefined;
  }
}

/** 接口定义随采集任务保存，页面标识只用于保持编辑器实例稳定。 */
function createApiMapping(
  saved: DataforgeAccessApi.ApiAccessMapping = {},
): ApiMappingDraft {
  return {
    ...saved,
    mappingKey: crypto.randomUUID(),
    apiConfig: defaultApiConfig(
      saved.apiConfig ?? {
        apiName: `接口 ${model.apiMappings.length + 1}`,
      },
    ),
    columnMaps: (saved.columnMaps ?? []).map((column) => ({ ...column })),
  };
}

function addApiMapping() {
  model.apiMappings.push(createApiMapping());
}

function removeApiMapping(mappingKey: string) {
  model.apiMappings = model.apiMappings.filter(
    (mapping) => mapping.mappingKey !== mappingKey,
  );
  columnMapVisible.value = false;
  columnMapTarget.value = undefined;
}

/** 重新取字段时保留同名字段的映射与勾选，清除已移除的返回字段。 */
function syncApiColumnMaps(mapping: ApiMappingDraft) {
  const previous = new Map(
    (mapping.columnMaps ?? []).map((column) => [column.sourceColumn, column]),
  );
  mapping.sourceApiName = mapping.apiConfig.apiName;
  mapping.columnMaps = (mapping.apiConfig.dataColumns ?? []).map((column) => ({
    sourceColumn: column.fieldName,
    targetColumn: column.fieldName,
    importFlg: 1,
    ...previous.get(column.fieldName),
  }));
}

function handleApiIncrementChange(checked: boolean) {
  if (checked) model.targetDropTable = false;
}

watch(
  () => model.apiMappings.map((mapping) => mapping.apiConfig.dataColumns),
  () => model.apiMappings.forEach(syncApiColumnMaps),
  { deep: true },
);

/** 字段映射编辑弹窗 */
const columnMapVisible = ref(false);
const columnMapTarget = ref<DataforgeAccessApi.ApiAccessMapping>();

function handleEditColumnMap(mapping: DataforgeAccessApi.ApiAccessMapping) {
  columnMapTarget.value = mapping;
  columnMapVisible.value = true;
}

function handleDataSyncChange(checked: boolean) {
  if (checked) {
    // 增量协议不接受删除重建、清空或 ONLY 读取。
    model.targetDropTable = false;
    model.targetTruncateData = false;
    model.tableReadMode = 'INCLUDE_DESCENDANTS';
    if (!model.changeDataSyncType) {
      model.changeDataSyncType = 'INCREASE_COLUMN';
    }
  } else {
    model.changeDataSyncType = '';
  }
}

/** 名称映射编辑 */
function addMapper(list: DataforgeAccessApi.PatternMapper[]) {
  list.push({ fromPattern: '', toValue: '' });
}

function removeMapper(list: DataforgeAccessApi.PatternMapper[], index: number) {
  list.splice(index, 1);
}

/** 映射预览 */
const previewVisible = ref(false);
const previewTitle = ref('');
const previewLoading = ref(false);
const previewData = ref<DataforgeAccessApi.PreviewNameMapper[]>([]);
const previewColumnTable = ref<string>();
const mappingRows = ref<DataforgeAccessApi.PreviewNameMapper[]>([]);
const tableMappingLoading = ref(false);
const tableMappingError = ref('');
const tableMapping = ref<{ validate: () => boolean }>();

/** 自定义接入不提交中台 Doris 物理布局；切回中台时仍可恢复本地草稿。 */
function effectiveConfigMap() {
  if (!model.configMap || (model.sourceType === 1 && dorisTarget.value))
    return model.configMap;
  return Object.fromEntries(
    Object.entries(model.configMap).map(([name, mapping]) => {
      const copy = { ...mapping };
      delete copy.dorisOptions;
      if (model.sourceType !== 1) {
        delete copy.targetTableName;
        delete copy.targetTableComment;
      }
      return [name, copy];
    }),
  );
}

const previewColumns = [
  { title: '原名称', dataIndex: 'originalName', key: 'originalName' },
  { title: '映射后名称', dataIndex: 'targetName', key: 'targetName' },
  { title: '注释', dataIndex: 'remarks', key: 'remarks' },
];

const previewTableCandidates = computed(() => {
  if (model.includeOrExclude === 1 && model.sourceSelectedTables?.length) {
    return model.sourceSelectedTables.map((name) => ({
      label: name,
      value: name,
    }));
  }
  return sourceTableOptions.value;
});

function buildPreviewReq(
  previewTableName?: string,
): DataforgeAccessApi.PreviewReq {
  return {
    schemaVersion: 1,
    sourceDatabaseId: model.sourceDatabaseId,
    targetDatabaseId: model.targetDatabaseId,
    sourcePrimaryKeys: model.sourcePrimaryKeys ?? [],
    syncDefaultVal: model.syncDefaultVal,
    sourceType: model.sourceType,
    sourceSql: model.sourceType === 2 ? model.sourceSql : undefined,
    includeOrExclude: model.includeOrExclude,
    sourceSelectedTables: model.sourceSelectedTables,
    accessMode: model.accessMode,
    mapperType: model.mapperType,
    tableNameMapper: cleanMappers(model.tableNameMapper),
    columnNameMapper: cleanMappers(model.columnNameMapper),
    configMap: effectiveConfigMap(),
    targetTableNameCase: model.targetTableNameCase,
    targetColumnNameCase: model.targetColumnNameCase,
    previewTableName,
  };
}

/** 默认名来自服务端，与旧任务正则/大小写规则一致，批量重置不会重新套用显式覆盖。 */
const mappingDefaultsRequest = computed(() => {
  const request = buildPreviewReq();
  request.configMap = Object.fromEntries(
    Object.entries(request.configMap ?? {}).flatMap(([name, mapping]) => {
      const legacy = { ...mapping };
      delete legacy.targetTableName;
      delete legacy.targetTableComment;
      delete legacy.dorisOptions;
      return Object.keys(legacy).length > 0 ? [[name, legacy]] : [];
    }),
  );
  return request;
});

async function loadTableMappings() {
  const request = ++mappingRequest;
  mappingRows.value = [];
  tableMappingError.value = '';
  tableMappingLoading.value = true;
  try {
    const rows = await previewTableMap(mappingDefaultsRequest.value);
    if (request === mappingRequest && opened) mappingRows.value = rows;
  } catch (error: unknown) {
    if (request === mappingRequest && opened) {
      const businessMessage =
        error !== null &&
        typeof error === 'object' &&
        'msg' in error &&
        typeof error.msg === 'string'
          ? error.msg.trim()
          : '';
      tableMappingError.value =
        businessMessage ||
        (error instanceof Error ? error.message.trim() : '') ||
        '目标表映射加载失败，请重试';
    }
  } finally {
    if (request === mappingRequest) tableMappingLoading.value = false;
  }
}

watch(
  [currentKey, () => JSON.stringify(mappingDefaultsRequest.value)],
  ([, signature], [, previousSignature]) => {
    if (signature !== previousSignature) {
      ++mappingRequest;
      mappingRows.value = [];
      tableMappingLoading.value = false;
      tableMappingError.value = '';
    }
    if (
      opened &&
      (currentKey.value === 'mapper' ||
        (currentKey.value === 'attributes' &&
          signature !== previousSignature)) &&
      model.engineType === 1 &&
      model.sourceType === 1
    ) {
      void loadTableMappings();
    }
  },
);

const typeMappingAvailable = computed(
  () =>
    model.engineType === 1 &&
    model.sourceType === 1 &&
    !!model.sourceDatabaseId &&
    !!model.targetDatabaseId &&
    !!sourceProduct.value &&
    !!targetProduct.value &&
    sourceProduct.value !== targetProduct.value &&
    !(model.targetDataSync && model.changeDataSyncType === 'INCREASE_COLUMN'),
);
const typeMappingRequest = computed<DataforgeAccessApi.TypeMappingPreviewReq>(
  () => ({
    ...buildPreviewReq(),
  }),
);
watch(
  () =>
    JSON.stringify([
      typeMappingRequest.value,
      model.engineType,
      model.targetDataSync,
      model.changeDataSyncType,
      sourceProduct.value,
      targetProduct.value,
    ]),
  () => {
    if (!restoringSavedDeclarations) model.typeMappingAcknowledgements = [];
  },
  { flush: 'sync' },
);

function cleanMappers(list: DataforgeAccessApi.PatternMapper[]) {
  return list.filter((item) => item.fromPattern);
}

async function handlePreviewTables() {
  if (!model.sourceDatabaseId) {
    message.warning('请先选择源端数据源');
    return;
  }
  previewTitle.value = '表名映射预览';
  previewVisible.value = true;
  const request = ++previewRequest;
  previewData.value = [];
  previewLoading.value = true;
  try {
    const data = await previewTableMap(buildPreviewReq());
    if (request === previewRequest && previewVisible.value)
      previewData.value = data;
  } finally {
    if (request === previewRequest) previewLoading.value = false;
  }
}

async function handlePreviewColumns() {
  if (!model.sourceDatabaseId) {
    message.warning('请先选择源端数据源');
    return;
  }
  if (model.sourceType === 1 && !previewColumnTable.value) {
    message.warning('请先选择要预览字段的表');
    return;
  }
  if (model.sourceType === 2 && !model.sourceSql) {
    message.warning('请先填写源端 SQL');
    return;
  }
  previewTitle.value = '字段名映射预览';
  previewVisible.value = true;
  const request = ++previewRequest;
  previewData.value = [];
  previewLoading.value = true;
  try {
    const data = await previewColumnMap(
      buildPreviewReq(previewColumnTable.value),
    );
    if (request === previewRequest && previewVisible.value)
      previewData.value = data;
  } finally {
    if (request === previewRequest) previewLoading.value = false;
  }
}

/** 分步校验 */
function validateStep(step: number): boolean {
  const key = stepKeys.value[step];
  switch (key) {
    case 'apiaccess': {
      if (!model.targetDatabaseId) {
        message.warning('请选择目的端数据源');
        return false;
      }
      if (model.apiMappings.length === 0) {
        message.warning('请至少配置一个接口');
        return false;
      }
      if (model.targetDataSync && model.targetDropTable) {
        message.warning('按主键增量更新不能同时删除重建目标表');
        return false;
      }
      for (const mapping of model.apiMappings) {
        if (!mapping.targetTable?.trim()) {
          message.warning(`API「${mapping.sourceApiName}」未指定目标表`);
          return false;
        }
        const hasImport = mapping.columnMaps?.some(
          (column) => column.importFlg === 1 && column.targetColumn?.trim(),
        );
        if (!hasImport) {
          message.warning(
            `API「${mapping.sourceApiName}」至少需要一个导入字段并指定目标字段`,
          );
          return false;
        }
        if (model.targetDataSync) {
          const primaryKeys = (mapping.apiConfig.dataColumns ?? []).filter(
            (column) => column.pk,
          );
          if (
            primaryKeys.length === 0 ||
            primaryKeys.some(
              (keyColumn) =>
                !mapping.columnMaps?.some(
                  (column) =>
                    column.sourceColumn === keyColumn.fieldName &&
                    column.importFlg === 1 &&
                    column.targetColumn?.trim(),
                ),
            )
          ) {
            message.warning(
              `接口「${mapping.apiConfig.apiName}」按主键增量更新时必须标记并导入全部主键字段`,
            );
            return false;
          }
        }
      }
      return true;
    }
    case 'apiconfig': {
      if (model.apiMappings.length === 0) {
        message.warning('请至少配置一个接口');
        return false;
      }
      for (const mapping of model.apiMappings) {
        const error = validateApiConfig(mapping.apiConfig, {
          requireName: true,
        });
        if (error) {
          message.warning(error);
          return false;
        }
        syncApiColumnMaps(mapping);
      }
      return true;
    }
    case 'attributes': {
      if (model.taskType === 3 && !model.cron?.trim()) {
        message.warning('周期任务必须填写 cron 表达式');
        return false;
      }
      if (model.engineType === 3) return true;
      if (!model.targetDatabaseId) {
        message.warning(
          model.accessMode === 1
            ? '请选择目标中台库（若无可选项，请先在数据源中心将某个数据源的库用途设为中台库）'
            : '请选择目的端数据源',
        );
        return false;
      }
      if (model.sourceType === 2 && !model.targetTable?.trim()) {
        message.warning('自定义 SQL 接入必须填写目标表名');
        return false;
      }
      if (
        (model.preserveIdentityValues ||
          model.exclusiveTargetOwnership ||
          model.identitySourceQuiesced) &&
        !identityTransferAvailable.value
      ) {
        message.warning(
          'Identity 值保留仅支持同产品 PostgreSQL 或 SQL Server 实体表全量搬数，请重新确认同步选项',
        );
        return false;
      }
      if (model.preserveIdentityValues) {
        if (!model.exclusiveTargetOwnership) {
          message.warning(
            '保留 Identity 值前，请确认目标表及发号会话由本任务独占',
          );
          return false;
        }
        if (sqlServerIdentityTransfer.value && !model.identitySourceQuiesced) {
          message.warning(
            'SQL Server Identity 搬数前，请确认源端已停止写入、DDL 和发号，并保持至任务结束',
          );
          return false;
        }
        if (model.targetAutoIncrement) {
          message.warning('保留原有 Identity 定义时，请关闭“建表时主键自增”');
          return false;
        }
      } else if (
        model.exclusiveTargetOwnership ||
        model.identitySourceQuiesced
      ) {
        message.warning('请先选择保留 Identity 值，或清除相关独占与暂停声明');
        return false;
      }
      if (
        model.targetDataSync &&
        model.changeDataSyncType === 'INCREASE_COLUMN' &&
        !model.increaseColumnName?.trim()
      ) {
        message.warning('增量字段模式必须填写增量字段名');
        return false;
      }
      if (
        model.targetDataSync &&
        (model.targetDropTable ||
          model.targetTruncateData ||
          model.tableReadMode === 'ONLY')
      ) {
        message.warning('增量同步不能删除重建、清空目标表或使用 ONLY 读取');
        return false;
      }
      if (
        model.targetDataSync &&
        model.changeDataSyncType === 'INCREASE_COLUMN'
      ) {
        if (model.sourceType !== 1) {
          message.warning('游标增量必须选择源端实体表，不能使用自定义 SQL');
          return false;
        }
        if (!cursorPairAvailable.value) {
          message.warning(cursorPairHint.value);
          return false;
        }
        if (model.targetOnlyCreate) {
          message.warning('游标增量需要写入已有目标表，请关闭“只创建表结构”');
          return false;
        }
        if (model.cursorWatermarkContract !== 'MONOTONIC_WATERMARK_V1') {
          message.warning('请确认源端每次新增和更新都会推进水位');
          return false;
        }
      }
      return true;
    }
    case 'basic': {
      if (!model.taskName?.trim()) {
        message.warning('请填写任务名称');
        return false;
      }
      if (
        model.engineType === 1 &&
        (!model.sourceDatabaseId || !model.targetDatabaseId)
      ) {
        message.warning('请选择来源数据库和目标数据库');
        return false;
      }
      if (
        model.engineType === 2 &&
        model.taskType === 3 &&
        !model.cron?.trim()
      ) {
        message.warning('周期任务必须填写 cron 表达式');
        return false;
      }
      return true;
    }
    case 'mapper': {
      if (model.sourceType === 1) {
        if (tableMappingLoading.value || tableMappingError.value) {
          message.warning(
            tableMappingError.value || '请等待目标表映射加载完成',
          );
          return false;
        }
        if (tableMapping.value && !tableMapping.value.validate()) return false;
      }
      if (model.sourceType === 2 && !model.targetTable?.trim()) {
        message.warning('自定义 SQL 接入必须填写目标表名');
        return false;
      }
      if (model.sourceType === 2 && model.mapperType !== 'ALL') {
        message.warning(
          '自定义 SQL 接入仅支持全局字段映射，请重新选择来源类型',
        );
        return false;
      }
      if (
        model.sourceType === 2 &&
        cleanMappers(model.columnNameMapper).some((item) => !item.toValue)
      ) {
        message.warning('自定义 SQL 接入不允许字段映射为空');
        return false;
      }
      return true;
    }
    case 'seatunnel': {
      if (!model.seatunnelConfig?.trim()) {
        message.warning('请填写 Seatunnel 作业配置');
        return false;
      }
      if (model.configFormat === 'json') {
        try {
          JSON.parse(model.seatunnelConfig);
        } catch {
          message.warning(
            '作业配置不是合法的 JSON，请检查（或切换为 hocon 格式）',
          );
          return false;
        }
      }
      return true;
    }
    case 'source': {
      if (!model.sourceDatabaseId) {
        message.warning('请选择源端数据源');
        return false;
      }
      if (model.sourceType === 2 && !model.sourceSql?.trim()) {
        message.warning('自定义 SQL 接入必须填写源端 SQL');
        return false;
      }
      if (
        model.sourceType === 1 &&
        tableScope.value === 'SELECTED' &&
        !model.sourceSelectedTables?.length
      ) {
        message.warning('请至少选择一张来源表，或切换为同步全部表');
        return false;
      }
      return true;
    }
    default: {
      return true;
    }
  }
}

function nextStep() {
  if (!validateStep(currentStep.value)) {
    return;
  }
  currentStep.value = Math.min(
    currentStep.value + 1,
    stepKeys.value.length - 1,
  );
}

function prevStep() {
  currentStep.value = Math.max(currentStep.value - 1, 0);
}

/** 提交 */
async function handleSubmit() {
  if (!opened || saving.value) return;
  for (let step = 0; step < stepKeys.value.length; step++) {
    if (!validateStep(step)) {
      currentStep.value = step;
      return;
    }
  }
  const started = generation;
  saving.value = true;
  modalApi.lock();
  try {
    const data: DataforgeAccessApi.DataAccess = {
      ...model,
      apiMappings: model.apiMappings.map((mapping) => ({
        apiConfig: defaultApiConfig(mapping.apiConfig),
        sourceApiName: mapping.apiConfig.apiName,
        targetTable: mapping.targetTable,
        columnMaps: (mapping.columnMaps ?? []).map((column) => ({ ...column })),
      })),
      configMap: effectiveConfigMap(),
      tableNameMapper: cleanMappers(model.tableNameMapper),
      columnNameMapper: cleanMappers(model.columnNameMapper),
    };
    await (isEdit.value ? updateAccess(data) : createAccess(data));
    if (started !== generation || !opened) return;
    message.success(isEdit.value ? '修改已保存' : '任务已创建');
    emit('success');
    await closeForm();
  } finally {
    if (started === generation) {
      saving.value = false;
      modalApi.unlock();
    }
  }
}

const getTitle = computed(() => {
  const taskName = model.engineType === 3 ? '接口采集任务' : '同步任务';
  return `${isEdit.value ? '编辑' : '新建'}${taskName}`;
});

function reset() {
  ++generation;
  ++sourceRequest;
  ++sqlRequest;
  ++previewRequest;
  ++mappingRequest;
  opened = false;
  const defaults = defaultModel();
  for (const key of Object.keys(model)) {
    if (!(key in defaults)) Reflect.deleteProperty(model, key);
  }
  Object.assign(model, defaults);
  currentStep.value = 0;
  lockEngine.value = false;
  databaseList.value = [];
  deptTree.value = [];
  sourceTables.value = [];
  sourceTablesLoading.value = false;
  sourceTablesError.value = '';
  tableScope.value = 'SELECTED';
  sourceProductFilter.value = undefined;
  targetProductFilter.value = undefined;
  mappingRows.value = [];
  tableMappingLoading.value = false;
  tableMappingError.value = '';
  sqlColumns.value = [];
  sqlParsing.value = false;
  previewColumnTable.value = undefined;
  previewVisible.value = false;
  previewData.value = [];
  previewLoading.value = false;
  previewTitle.value = '';
  columnMapVisible.value = false;
  columnMapTarget.value = undefined;
  saving.value = false;
  modalApi.unlock();
}

watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    reset();
    void closeForm();
  },
  { flush: 'sync' },
);
watch(
  () => model.sourceDatabaseId,
  () => {
    ++sourceRequest;
    sourceTables.value = [];
    sourceTablesLoading.value = false;
  },
  { flush: 'sync' },
);
watch(
  () => model.sourceType,
  (sourceType) => {
    if (sourceType === 2 && !restoringSavedDeclarations)
      model.mapperType = 'ALL';
  },
  { flush: 'sync' },
);
// Declarations belong to this exact source/target and mode; a changed context needs a new confirmation.
watch(
  () => [
    model.sourceDatabaseId,
    model.targetDatabaseId,
    model.sourceType,
    model.engineType,
    model.targetDataSync,
    model.targetOnlyCreate,
    sourceProduct.value,
    targetProduct.value,
  ],
  () => {
    if (!restoringSavedDeclarations) clearIdentityDeclarations();
  },
  { flush: 'sync' },
);
watch(
  () => model.preserveIdentityValues,
  (preserve) => {
    if (!preserve && !restoringSavedDeclarations) {
      model.exclusiveTargetOwnership = false;
      model.identitySourceQuiesced = false;
    }
  },
  { flush: 'sync' },
);
watch(
  () => [
    model.sourceDatabaseId,
    model.targetDatabaseId,
    model.sourceType,
    model.engineType,
    model.targetDataSync,
    model.changeDataSyncType,
    model.targetOnlyCreate,
    model.increaseColumnName,
    sourceProduct.value,
    targetProduct.value,
  ],
  () => {
    if (!restoringSavedDeclarations) clearCursorDeclarations();
  },
  { flush: 'sync' },
);
watch(
  () => [
    model.sourceDatabaseId,
    model.sourceSql,
    model.sourceType,
    model.engineType,
  ],
  () => {
    ++sqlRequest;
    sqlColumns.value = [];
    sqlParsing.value = false;
  },
  { flush: 'sync' },
);
watch(
  () => JSON.stringify(buildPreviewReq(previewColumnTable.value)),
  () => {
    ++previewRequest;
    previewData.value = [];
    previewLoading.value = false;
  },
  { flush: 'sync' },
);
watch(
  previewVisible,
  (visible) => {
    if (!visible) {
      ++previewRequest;
      previewData.value = [];
      previewLoading.value = false;
    }
  },
  { flush: 'sync' },
);
watch(
  columnMapVisible,
  (visible) => {
    if (!visible) columnMapTarget.value = undefined;
  },
  { flush: 'sync' },
);
onBeforeUnmount(reset);

const [ModalComponent, modalApi] = useVbenModal({
  showCancelButton: false,
  showConfirmButton: false,
  fullscreenButton: true,
  class: 'w-[min(1480px,96vw)]',
  contentClass: 'p-0',
  onOpenChange: (isOpen: boolean) => initializeForm(isOpen),
});

async function closeForm() {
  if (props.embedded) {
    reset();
    emit('close');
  } else await modalApi.close();
}

async function initializeForm(
  isOpen: boolean,
  initialData = props.initialData,
) {
  reset();
  if (!isOpen) {
    return;
  }
  opened = true;
  const started = generation;
  const data =
    initialData ?? (modalApi.getData() as { engineType?: number; id?: RunId });
  if (data?.engineType) {
    model.engineType = data.engineType;
    lockEngine.value = true;
  }
  if (!data?.id && model.engineType === 3) addApiMapping();
  initializing.value = true;
  modalApi.lock();
  try {
    const [databases, depts] = await Promise.all([
      getDatabaseSimpleList(),
      getDeptList(),
    ]);
    if (started !== generation || !opened) return;
    databaseList.value = databases;
    deptTree.value = handleTree(depts);
    if (data?.id) {
      const detail = await getAccess(data.id);
      if (started !== generation || !opened) return;
      restoringSavedDeclarations = true;
      try {
        Object.assign(model, defaultModel(), detail, {
          tableNameMapper: detail.tableNameMapper ?? [],
          columnNameMapper: detail.columnNameMapper ?? [],
          sourceSelectedTables: detail.sourceSelectedTables ?? [],
          sourcePrimaryKeys: detail.sourcePrimaryKeys ?? [],
          typeMappingAcknowledgements: detail.typeMappingAcknowledgements ?? [],
          apiMappings: (detail.apiMappings ?? []).map((mapping) =>
            createApiMapping(mapping),
          ),
          batchSize: detail.batchSize ?? 5000,
          mapperType: detail.mapperType || 'ALL',
          configFormat: detail.configFormat || 'json',
          targetLowerCase: false,
          targetUpperCase: false,
          targetTableNameCase: savedNameCase(
            detail.targetTableNameCase,
            detail.targetLowerCase,
            detail.targetUpperCase,
          ),
          targetColumnNameCase: savedNameCase(
            detail.targetColumnNameCase,
            detail.targetLowerCase,
            detail.targetUpperCase,
          ),
        });
      } finally {
        restoringSavedDeclarations = false;
      }
      if (model.includeOrExclude === 0) tableScope.value = 'EXCLUDE';
      else
        tableScope.value = model.sourceSelectedTables?.length
          ? 'SELECTED'
          : 'ALL';
      sourceProductFilter.value = sourceProduct.value;
      targetProductFilter.value = targetProduct.value;
      if (!identityTransferAvailable.value) clearIdentityDeclarations();
      else if (!sqlServerIdentityTransfer.value)
        model.identitySourceQuiesced = false;
      if (model.engineType === 1 && model.sourceType === 1) {
        loadSourceTables();
      }
    }
  } catch {
    if (started === generation) await closeForm();
  } finally {
    if (started === generation) {
      initializing.value = false;
      modalApi.unlock();
    }
  }
}

onMounted(() => {
  if (props.embedded) void initializeForm(true, props.initialData);
});
</script>

<template>
  <component
    :is="embedded ? FormShell : ModalComponent"
    :title="getTitle"
    :busy="initializing || saving"
  >
    <div
      class="wizard"
      :class="{
        'wizard--sync': isHorizontalWizard,
        'wizard--embedded': embedded,
      }"
    >
      <!-- 配置步骤 -->
      <aside class="wizard__rail">
        <FormWizardSteps
          v-if="isHorizontalWizard"
          :current="currentStep"
          :steps="stepKeys.map((key) => ({ title: STEP_META[key].title }))"
          :busy="saving || initializing"
          :aria-label="
            model.engineType === 3 ? '接口采集任务配置步骤' : '同步任务配置步骤'
          "
          @change="handleStepChange"
        />
        <Steps
          v-else
          :current="currentStep"
          :items="railItems"
          direction="vertical"
          size="small"
          @change="handleStepChange"
        />
      </aside>

      <!-- 右侧内容区 -->
      <div class="wizard__main">
        <header class="wizard__header">
          <div class="wizard__title">{{ STEP_META[currentKey].title }}</div>
          <div class="wizard__desc">
            {{
              currentKey === 'basic'
                ? model.engineType === 3
                  ? '填写任务名称、所属单位和采集用途'
                  : model.engineType === 2
                    ? '填写任务信息并选择作业调度方式'
                    : STEP_META[currentKey].desc
                : STEP_META[currentKey].desc
            }}
          </div>
        </header>

        <div class="wizard__content">
          <!-- 基本信息 -->
          <div
            v-show="currentKey === 'basic'"
            class="form-grid form-grid--basic"
          >
            <div
              v-if="isHorizontalWizard"
              class="form-item form-item--span2 section-heading"
            >
              基础信息
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                任务名称
              </label>
              <Input
                v-model:value="model.taskName"
                allow-clear
                placeholder="例如：公安人口库每日同步"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">所属单位</label>
              <TreeSelect
                v-model:value="model.orgId"
                :tree-data="deptTree"
                :field-names="{
                  children: 'children',
                  label: 'name',
                  value: 'id',
                }"
                allow-clear
                show-search
                tree-default-expand-all
                tree-node-filter-prop="name"
                placeholder="请选择所属单位"
                class="w-full"
              />
            </div>
            <div class="form-item form-item--span2">
              <label class="form-item__label">描述</label>
              <TextArea
                :rows="3"
                v-model:value="model.description"
                allow-clear
                placeholder="这个任务同步什么数据、给谁用"
              />
            </div>
            <div v-if="model.engineType !== 3" class="form-item">
              <label class="form-item__label">同步类型</label>
              <RadioGroup
                v-model:value="model.engineType"
                :options="engineOptions"
                :disabled="isEdit || lockEngine"
                @change="handleEngineChange"
              />
              <div class="form-item__tip">
                离线同步走 DBSwitch 库表迁移；实时同步提交 Seatunnel 作业
              </div>
            </div>
            <template v-if="model.engineType === 1">
              <div class="form-item form-item--span2 section-heading">
                数据库信息
              </div>
              <div class="form-item form-item--span2">
                <label class="form-item__label form-item__label--required">
                  接入方式
                </label>
                <RadioGroup
                  v-model:value="model.accessMode"
                  :options="ACCESS_MODE_OPTIONS"
                  @change="handleAccessModeChange"
                />
                <div class="form-item__tip">
                  入中台库：选择中台数据源，默认目标表添加 ods_
                  前缀；可在多表映射中修改最终表名
                </div>
              </div>
              <div class="form-item">
                <label class="form-item__label">源库类型</label>
                <Select
                  v-model:value="sourceProductFilter"
                  :options="databaseTypeOptions"
                  allow-clear
                  placeholder="全部数据库类型"
                  @change="changeSourceProduct"
                />
              </div>
              <div class="form-item">
                <label class="form-item__label form-item__label--required">
                  来源数据库
                </label>
                <Select
                  v-model:value="model.sourceDatabaseId"
                  :options="sourceDatabaseOptions"
                  show-search
                  option-filter-prop="label"
                  allow-clear
                  placeholder="请选择来源数据库"
                  class="w-full"
                  @change="handleSourceDatabaseChange"
                />
              </div>
              <div class="form-item">
                <label class="form-item__label">目标库类型</label>
                <Select
                  v-model:value="targetProductFilter"
                  :options="databaseTypeOptions"
                  allow-clear
                  placeholder="全部数据库类型"
                  @change="changeTargetProduct"
                />
              </div>
              <div class="form-item">
                <label class="form-item__label form-item__label--required">
                  {{ model.accessMode === 1 ? '目标中台库' : '目的端数据源' }}
                </label>
                <Select
                  v-model:value="model.targetDatabaseId"
                  :options="targetDatabaseOptions"
                  show-search
                  option-filter-prop="label"
                  allow-clear
                  placeholder="请选择目的端数据源"
                  class="w-full"
                />
              </div>
            </template>
            <template v-if="model.engineType === 2">
              <div class="form-item">
                <label class="form-item__label form-item__label--required">
                  调度类型
                </label>
                <RadioGroup
                  v-model:value="model.taskType"
                  :options="taskTypeOptions"
                />
                <div class="form-item__tip">
                  {{
                    model.engineType === 2
                      ? '实时任务发布后提交集群常驻运行；一次性任务发布后立即执行；周期任务按 cron 调度'
                      : '一次性任务发布后立即执行；周期任务按 cron 表达式调度'
                  }}
                </div>
              </div>
              <div
                v-if="model.taskType === 3"
                class="form-item form-item--span2"
              >
                <label class="form-item__label form-item__label--required">
                  cron 表达式
                </label>
                <CronTab
                  v-model="model.cron"
                  placeholder="例如：0 0 2 * * ?（每天凌晨 2 点）"
                />
                <div class="form-item__tip">
                  可直接输入 6 位 cron（秒 分 时 日 月
                  周），或在右侧选择常用周期、用「自定义」打开可视化生成器
                </div>
              </div>
            </template>
            <div
              v-if="model.engineType === 2"
              class="form-item form-item--span2"
            >
              <label class="form-item__label">备注</label>
              <TextArea
                v-model:value="model.remark"
                :rows="2"
                placeholder="备注"
              />
            </div>
          </div>

          <!-- Seatunnel 作业配置 -->
          <div v-if="currentKey === 'seatunnel'" class="form-grid">
            <div class="form-item">
              <label class="form-item__label">配置格式</label>
              <RadioGroup
                v-model:value="model.configFormat"
                :options="CONFIG_FORMAT_OPTIONS"
              />
            </div>
            <div class="form-item form-item--span2">
              <label class="form-item__label form-item__label--required">
                作业配置
              </label>
              <div class="seatunnel-editor">
                <CodeEditor
                  :key="model.configFormat"
                  v-model:value="model.seatunnelConfig"
                  :mode="
                    model.configFormat === 'json'
                      ? 'application/json'
                      : 'text/plain'
                  "
                  :auto-format="false"
                  bordered
                />
              </div>
              <div class="form-item__tip">
                填写包含 env、source、transform、sink 的完整 SeaTunnel
                作业配置。 实时任务的 job.mode 使用 STREAMING，批作业使用
                BATCH；按所选格式保留并提交原文。
              </div>
            </div>
          </div>

          <!-- 接口配置与试调 -->
          <div
            v-if="model.engineType === 3"
            v-show="currentKey === 'apiconfig'"
            class="api-config-list"
          >
            <div class="flex items-center justify-between">
              <span class="text-muted-foreground text-sm"
                >每个接口独立配置请求和返回字段，配置随当前采集任务保存。</span
              >
              <Button :disabled="initializing || saving" @click="addApiMapping"
                ><template #icon><IconifyIcon icon="lucide:plus" /></template
                >添加接口</Button
              >
            </div>
            <section
              v-for="mapping in model.apiMappings"
              :key="mapping.mappingKey"
              class="advanced-mapping"
            >
              <div class="mb-4 flex items-center gap-3">
                <label class="form-item__label shrink-0">接口名称</label>
                <Input
                  v-model:value="mapping.apiConfig.apiName"
                  :disabled="initializing || saving"
                  placeholder="例如：人口基础信息接口"
                />
                <Button
                  danger
                  :disabled="initializing || saving"
                  @click="removeApiMapping(mapping.mappingKey)"
                  ><template #icon
                    ><IconifyIcon icon="lucide:trash-2" /></template
                  >移除</Button
                >
              </div>
              <ApiConfigEditor
                v-model="mapping.apiConfig"
                :disabled="initializing || saving"
                @columns-change="syncApiColumnMaps(mapping)"
              />
            </section>
          </div>

          <!-- 目标表与字段映射 -->
          <div v-show="currentKey === 'apiaccess'" class="form-grid">
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                目的端数据源
              </label>
              <Select
                v-model:value="model.targetDatabaseId"
                :options="databaseOptions"
                show-search
                option-filter-prop="label"
                allow-clear
                placeholder="API 数据写入的库"
                class="w-full"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">写入选项</label>
              <div class="flex flex-wrap gap-4 pt-1">
                <span class="flex items-center gap-2 text-[13px]">
                  <Switch v-model:checked="model.targetSyncExit" size="small" />
                  同步已存在的表
                </span>
                <span class="flex items-center gap-2 text-[13px]">
                  <Switch
                    v-model:checked="model.targetDropTable"
                    :disabled="model.targetDataSync"
                    size="small"
                  />
                  同步前删除重建
                </span>
                <span class="flex items-center gap-2 text-[13px]">
                  <Switch
                    v-model:checked="model.targetDataSync"
                    size="small"
                    @change="handleApiIncrementChange"
                  />
                  按主键增量更新
                </span>
              </div>
              <div class="form-item__tip">
                不开增量时每次全量覆盖（清空后写入）；开增量需 API
                字段定义中标记主键
              </div>
            </div>
            <div
              v-if="model.apiMappings?.length"
              class="form-item form-item--span2"
            >
              <label class="form-item__label">目标表与字段映射</label>
              <Table
                :scroll="{ x: 'max-content' }"
                :columns="[
                  { title: 'API', dataIndex: 'sourceApiName', key: 'api' },
                  { title: '目标表', key: 'targetTable' },
                  { title: '导入字段', key: 'columns', width: 100 },
                  {
                    title: '操作',
                    key: 'actions',
                    width: 120,
                    fixed: 'right' as const,
                  },
                ]"
                :data-source="model.apiMappings"
                :pagination="false"
                row-key="mappingKey"
                size="small"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'targetTable'">
                    <Input
                      v-model:value="record.targetTable"
                      placeholder="如 ods_population"
                      size="small"
                    />
                  </template>
                  <template v-else-if="column.key === 'columns'">
                    {{
                      record.columnMaps?.filter((c: any) => c.importFlg === 1)
                        .length ?? 0
                    }}
                    /
                    {{ record.columnMaps?.length ?? 0 }}
                  </template>
                  <template v-else-if="column.key === 'actions'">
                    <Button
                      size="small"
                      type="link"
                      @click="handleEditColumnMap(record as any)"
                    >
                      <template #icon
                        ><IconifyIcon icon="lucide:columns-3"
                      /></template>
                      字段映射
                    </Button>
                  </template>
                </template>
              </Table>
            </div>
          </div>

          <!-- 源端配置 -->
          <div v-show="currentKey === 'source'" class="form-grid">
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                源端类型
              </label>
              <RadioGroup
                v-model:value="model.sourceType"
                :options="SOURCE_TYPE_OPTIONS"
              />
            </div>

            <template v-if="model.sourceType === 1">
              <div
                v-if="sourceProduct === 'POSTGRESQL'"
                class="form-item form-item--span2"
              >
                <label class="form-item__label">子表读取</label>
                <Select
                  v-model:value="model.tableReadMode"
                  class="w-full"
                  :disabled="model.targetDataSync"
                  :options="[
                    {
                      label: '包含分区和继承子表，自动去重',
                      value: 'INCLUDE_DESCENDANTS',
                    },
                    { label: '只读所选表自身（ONLY）', value: 'ONLY' },
                  ]"
                />
              </div>
              <div class="form-item form-item--span2">
                <label class="form-item__label">同步范围</label>
                <RadioGroup
                  v-model:value="tableScope"
                  :options="[
                    { label: '选择指定表', value: 'SELECTED' },
                    { label: '同步全部表', value: 'ALL' },
                    { label: '排除指定表', value: 'EXCLUDE' },
                  ]"
                  @change="changeTableScope"
                />
                <div v-if="tableScope === 'ALL'" class="form-item__tip">
                  每次执行同步来源库的全部物理表，新建来源表也会被包含。
                </div>
              </div>
              <div class="form-item form-item--span2">
                <div class="flex items-center justify-between">
                  <span class="form-item__label">{{
                    tableScope === 'ALL' ? '来源表' : '选择来源表'
                  }}</span>
                  <Button
                    :loading="sourceTablesLoading"
                    size="small"
                    @click="loadSourceTables"
                  >
                    <template #icon
                      ><IconifyIcon icon="lucide:refresh-cw"
                    /></template>
                    刷新来源表
                  </Button>
                </div>
                <Alert
                  v-if="sourceTablesError"
                  type="error"
                  show-icon
                  :message="sourceTablesError"
                />
                <Transfer
                  v-else-if="tableScope !== 'ALL'"
                  :data-source="sourceTransferItems"
                  :target-keys="model.sourceSelectedTables ?? []"
                  :titles="[
                    '来源表',
                    tableScope === 'EXCLUDE' ? '已排除来源表' : '已选来源表',
                  ]"
                  :render="(item) => item.title"
                  :filter-option="
                    (input, item) =>
                      item.title.toLowerCase().includes(input.toLowerCase())
                  "
                  :list-style="{ width: '45%', height: '360px' }"
                  :disabled="sourceTablesLoading || saving"
                  show-search
                  class="source-transfer"
                  @change="changeSelectedTables"
                />
                <Table
                  v-else
                  :columns="[
                    { title: '来源物理表', dataIndex: 'tableName' },
                    { title: '来源表说明', dataIndex: 'remarks' },
                  ]"
                  :data-source="sourceTables.filter((item) => !item.viewTable)"
                  :loading="sourceTablesLoading"
                  row-key="tableName"
                  size="small"
                  :pagination="{ pageSize: 10 }"
                />
              </div>
            </template>

            <template v-else>
              <div class="form-item form-item--span2">
                <div class="flex items-center justify-between">
                  <label class="form-item__label form-item__label--required">
                    源端 SQL
                  </label>
                  <Button
                    size="small"
                    type="primary"
                    ghost
                    :loading="sqlParsing"
                    @click="handleParseSql"
                  >
                    <template #icon
                      ><IconifyIcon icon="lucide:code"
                    /></template>
                    解析 SQL
                  </Button>
                </div>
                <TextArea
                  v-model:value="model.sourceSql"
                  :rows="6"
                  placeholder="单条 SELECT 语句，例如：select id, name, update_time from t_user where status = 1"
                />
                <div class="form-item__tip">
                  点击「解析 SQL」获取结果字段列表，便于选择唯一键和增量字段
                </div>
              </div>
              <div class="form-item form-item--span2">
                <label class="form-item__label">唯一键</label>
                <Select
                  v-model:value="model.sourcePrimaryKeys"
                  :options="sqlColumnOptions"
                  mode="tags"
                  allow-clear
                  placeholder="选择或输入唯一键字段，可多个（用于建表主键与增量比对）"
                  class="w-full"
                />
                <div class="form-item__tip">
                  按主键比对增量同步时，所选主键必须与目标表主键一致；目标表不存在时将以所选字段建主键
                </div>
              </div>
            </template>
          </div>

          <!-- 接口采集调度与确认 -->
          <div
            v-if="model.engineType === 3 && currentKey === 'attributes'"
            class="form-grid"
          >
            <div class="form-item form-item--span2 section-heading">
              调度周期
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required"
                >调度类型</label
              >
              <RadioGroup
                v-model:value="model.taskType"
                :options="taskTypeOptions"
              />
              <div class="form-item__tip">
                一次性任务发布后立即执行；周期任务按 cron 表达式调度
              </div>
            </div>
            <div v-if="model.taskType === 3" class="form-item form-item--span2">
              <label class="form-item__label form-item__label--required"
                >cron 表达式</label
              >
              <CronTab
                v-model="model.cron"
                placeholder="例如：0 0 2 * * ?（每天凌晨 2 点）"
              />
            </div>
            <div class="form-item form-item--span2">
              <label class="form-item__label">备注</label>
              <TextArea
                v-model:value="model.remark"
                :rows="2"
                placeholder="备注"
              />
            </div>
            <div class="form-item form-item--span2 section-heading">
              确认任务配置
            </div>
            <Descriptions
              :column="2"
              bordered
              size="small"
              class="form-item--span2"
            >
              <DescriptionsItem label="任务名称">{{
                model.taskName || '-'
              }}</DescriptionsItem>
              <DescriptionsItem label="调度类型">{{
                optionLabel(TASK_TYPE_OPTIONS, model.taskType)
              }}</DescriptionsItem>
              <DescriptionsItem label="目的端数据源">{{
                databaseOptions.find(
                  (option) =>
                    String(option.value) === String(model.targetDatabaseId),
                )?.label || '-'
              }}</DescriptionsItem>
              <DescriptionsItem label="写入方式">{{
                model.targetDataSync ? '按主键增量更新' : '全量覆盖'
              }}</DescriptionsItem>
              <DescriptionsItem label="接口与目标表" :span="2">
                <div
                  v-for="mapping in model.apiMappings"
                  :key="mapping.mappingKey"
                >
                  {{ mapping.apiConfig.apiName }} →
                  {{ mapping.targetTable }}（{{
                    mapping.columnMaps?.filter(
                      (column) => column.importFlg === 1,
                    ).length ?? 0
                  }}
                  个字段）
                </div>
              </DescriptionsItem>
            </Descriptions>
          </div>

          <!-- 数据同步任务属性 -->
          <div
            v-if="model.engineType === 1"
            v-show="currentKey === 'attributes'"
            class="form-grid"
          >
            <div class="form-item form-item--span2 section-heading">
              调度周期
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                调度类型
              </label>
              <RadioGroup
                v-model:value="model.taskType"
                :options="taskTypeOptions"
              />
              <div class="form-item__tip">
                一次性任务发布后立即执行；周期任务按 cron 表达式调度
              </div>
            </div>
            <div v-if="model.taskType === 3" class="form-item form-item--span2">
              <label class="form-item__label form-item__label--required">
                cron 表达式
              </label>
              <CronTab
                v-model="model.cron"
                placeholder="例如：0 0 2 * * ?（每天凌晨 2 点）"
              />
              <div class="form-item__tip">
                可直接输入 6 位 cron（秒 分 时 日 月
                周），或在右侧选择常用周期、用「自定义」打开可视化生成器
              </div>
            </div>
            <div class="form-item form-item--span2 section-heading">
              写入与执行参数
            </div>
            <div v-if="model.sourceType !== 2" class="form-item">
              <label class="form-item__label">批处理量</label>
              <InputNumber
                v-model:value="model.batchSize"
                :min="100"
                :max="100000"
                :step="1000"
                class="w-full"
              />
              <div class="form-item__tip">每批读取/写入的行数，默认 5000</div>
            </div>

            <div class="form-item form-item--span2">
              <label class="form-item__label">同步选项</label>
              <div class="switch-grid">
                <div class="switch-grid__item">
                  <Switch
                    v-model:checked="model.targetDropTable"
                    size="small"
                  />
                  <span>同步前删除重建目标表</span>
                </div>
                <div class="switch-grid__item">
                  <Switch
                    v-model:checked="model.targetTruncateData"
                    size="small"
                  />
                  <span>同步前清空目标表数据</span>
                </div>
                <div class="switch-grid__item">
                  <Switch v-model:checked="model.targetSyncExit" size="small" />
                  <span>同步已存在的表</span>
                </div>
                <div class="switch-grid__item">
                  <Switch
                    v-model:checked="model.targetOnlyCreate"
                    size="small"
                  />
                  <span>只创建表结构不同步数据</span>
                </div>
                <div class="switch-grid__item">
                  <Switch
                    v-model:checked="model.targetIndexCreate"
                    size="small"
                  />
                  <span>同步索引</span>
                </div>
                <div class="switch-grid__item">
                  <Switch v-model:checked="model.syncDefaultVal" size="small" />
                  <span>同步字段默认值</span>
                </div>
                <div class="switch-grid__item">
                  <Switch
                    v-model:checked="model.targetAutoIncrement"
                    size="small"
                  />
                  <span>建表时主键自增</span>
                </div>
                <div class="switch-grid__item">
                  <Switch
                    v-model:checked="model.targetDataSync"
                    size="small"
                    @change="(checked: any) => handleDataSyncChange(!!checked)"
                  />
                  <span>启用增量变更同步</span>
                </div>
              </div>
            </div>

            <div
              v-if="identityTransferAvailable"
              class="form-item form-item--span2"
            >
              <label class="form-item__label">Identity 值保留</label>
              <Checkbox
                v-model:checked="model.preserveIdentityValues"
                data-testid="identity-preserve"
              >
                保留源表 Identity 值，并校准目标后续发号
              </Checkbox>
              <div class="form-item__tip">
                适用于当前同产品实体表全量搬数。只创建结构时保留原生定义，不执行数据与发号位置对齐。
              </div>
              <template v-if="model.preserveIdentityValues">
                <div class="mt-3">
                  <Checkbox
                    v-model:checked="model.exclusiveTargetOwnership"
                    data-testid="identity-exclusive"
                  >
                    我已确保其他目标写入及持有发号缓存的会话退出，并由本任务独占至结束
                  </Checkbox>
                </div>
                <div v-if="sqlServerIdentityTransfer" class="mt-3">
                  <Checkbox
                    v-model:checked="model.identitySourceQuiesced"
                    data-testid="identity-source-quiesced"
                  >
                    我已暂停源端写入、DDL 和 Identity 发号，并会保持至任务结束
                  </Checkbox>
                </div>
                <div class="form-item__tip">
                  这些选项记录您的运行保障声明，系统不会自动停止或锁住源端业务。删除重建与清空目标数据仍由上方同步选项单独决定。
                </div>
              </template>
            </div>

            <template v-if="model.targetDataSync">
              <div class="form-item">
                <label class="form-item__label">增量变更类型</label>
                <Select
                  v-model:value="model.changeDataSyncType"
                  :options="CHANGE_DATA_SYNC_TYPE_OPTIONS"
                  placeholder="请选择增量变更类型"
                  class="w-full"
                />
                <div class="form-item__tip">
                  主键排序比对：按主键比对增删改；增量字段：按时间戳/自增列抽取（抓不到删除）
                </div>
              </div>
              <div
                v-if="model.changeDataSyncType === 'INCREASE_COLUMN'"
                class="form-item"
              >
                <label class="form-item__label form-item__label--required">
                  增量字段
                </label>
                <AutoComplete
                  v-model:value="model.increaseColumnName"
                  :options="sqlColumnOptions"
                  allow-clear
                  filter-option
                  :placeholder="
                    sourceProduct === 'DM'
                      ? '例如：revision（非空精确数值类型）'
                      : '例如：update_time（指定表模式下所有表需有同名字段）'
                  "
                  class="w-full"
                />
                <div class="form-item__tip" data-testid="cursor-pair-hint">
                  {{ cursorPairHint }}
                </div>
                <div
                  v-if="cursorSource"
                  class="form-item__tip"
                  data-testid="cursor-source-prerequisite"
                >
                  {{ cursorSource.prerequisite }}
                </div>
                <div class="form-item__tip">
                  请先由管理员完成双方的驱动与源代次部署。目标表须已存在，具有可重放写入的非空唯一键。服务端会验证实际版本、键、类型、权限和部署绑定；保存表单不表示运行条件已满足。
                </div>
                <div
                  v-if="cursorPairAvailable && sourceProduct !== targetProduct"
                  class="form-item__tip"
                  data-testid="cursor-cross-product-hint"
                >
                  跨产品同步的匹配键仅支持精确数值类型，目标键的完整取值范围和精度须覆盖源键；水位类型也须通过兼容性校验。
                </div>
                <div
                  v-if="
                    cursorPairAvailable &&
                    (sourceProduct === 'DM' || targetProduct === 'DM')
                  "
                  class="form-item__tip"
                  data-testid="cursor-dm-hint"
                >
                  DM 端当前限原生兼容模式、UTF-8
                  编码的普通永久表，使用已验证的非空数值主键；不包含
                  identity、自增列、触发器、行级策略或 DEFAULT ON
                  NULL。源读取期间会持有表锁以阻止表结构变更，但允许并发数据写入。字段类型和精度须在运行前通过服务端校验。
                </div>
                <div class="form-item__tip">
                  新增和更新必须推进水位；不支持硬删除、回填更旧水位或水位不变的更新。源代次由管理员管理，不能用页面确认代替恢复隔离和代次轮换。
                </div>
                <div class="mt-3 flex items-center gap-2">
                  <Switch
                    data-testid="cursor-watermark-contract"
                    :checked="
                      model.cursorWatermarkContract === 'MONOTONIC_WATERMARK_V1'
                    "
                    @change="
                      (checked: any) =>
                        (model.cursorWatermarkContract = checked
                          ? 'MONOTONIC_WATERMARK_V1'
                          : undefined)
                    "
                  />
                  <span>已确认源端新增和更新都会推进水位</span>
                </div>
                <div class="mt-3 flex items-center gap-2">
                  <Switch
                    v-model:checked="model.cursorBootstrapAllowed"
                    data-testid="cursor-bootstrap"
                  />
                  <span
                    >仅有旧字符串水位时，允许从头读取源表并重新建立检查点</span
                  >
                </div>
              </div>
            </template>

            <div v-if="model.sourceType === 2" class="form-item">
              <label class="form-item__label">批处理量</label>
              <InputNumber
                v-model:value="model.batchSize"
                :min="100"
                :max="100000"
                :step="1000"
                class="w-full"
              />
              <div class="form-item__tip">每批读取/写入的行数，默认 5000</div>
            </div>
            <div class="form-item form-item--span2">
              <label class="form-item__label">备注</label>
              <TextArea
                v-model:value="model.remark"
                :rows="2"
                placeholder="备注"
              />
            </div>
            <details class="form-item--span2 advanced-mapping">
              <summary>任务配置摘要</summary>

              <div class="summary-flow">
                <div class="summary-flow__node">
                  <div class="summary-flow__label">源端</div>
                  <div class="summary-flow__name">
                    {{
                      databaseOptions.find(
                        (o) => o.value === model.sourceDatabaseId,
                      )?.label || '未选择'
                    }}
                  </div>
                </div>
                <div class="summary-flow__arrow">
                  <span>{{
                    optionLabel(TASK_TYPE_OPTIONS, model.taskType)
                  }}</span>
                  <div class="summary-flow__line"></div>
                </div>
                <div class="summary-flow__node">
                  <div class="summary-flow__label">目标端</div>
                  <div class="summary-flow__name">
                    {{
                      targetDatabaseOptions.find(
                        (o) => o.value === model.targetDatabaseId,
                      )?.label || '未选择'
                    }}
                  </div>
                </div>
              </div>

              <Descriptions :column="2" bordered size="small">
                <DescriptionsItem label="任务名称">
                  {{ model.taskName || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="同步类型">
                  {{ optionLabel(ENGINE_TYPE_OPTIONS, model.engineType) }}
                </DescriptionsItem>
                <DescriptionsItem label="调度类型">
                  {{ optionLabel(TASK_TYPE_OPTIONS, model.taskType) }}
                </DescriptionsItem>
                <DescriptionsItem label="cron 表达式">
                  {{ model.cron || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="源端类型">
                  {{ optionLabel(SOURCE_TYPE_OPTIONS, model.sourceType) }}
                </DescriptionsItem>
                <DescriptionsItem label="接入方式">
                  {{ optionLabel(ACCESS_MODE_OPTIONS, model.accessMode) }}
                </DescriptionsItem>
                <DescriptionsItem
                  v-if="model.sourceType === 1"
                  label="源端表"
                  :span="2"
                >
                  {{
                    model.sourceSelectedTables?.length
                      ? `${optionLabel(INCLUDE_OR_EXCLUDE_OPTIONS, model.includeOrExclude)}：${model.sourceSelectedTables.join('、')}`
                      : '全部物理表'
                  }}
                </DescriptionsItem>
                <DescriptionsItem v-else label="源端 SQL" :span="2">
                  <pre class="summary-sql">{{ model.sourceSql }}</pre>
                </DescriptionsItem>
                <DescriptionsItem
                  v-if="model.sourceType === 2"
                  label="目标表名"
                >
                  {{ model.targetTable || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="同步前删除重建">
                  {{ model.targetDropTable ? '是' : '否' }}
                </DescriptionsItem>
                <DescriptionsItem label="同步前清空数据">
                  {{ model.targetTruncateData ? '是' : '否' }}
                </DescriptionsItem>
                <DescriptionsItem label="同步已存在的表">
                  {{ model.targetSyncExit ? '是' : '否' }}
                </DescriptionsItem>
                <DescriptionsItem label="只创建表结构">
                  {{ model.targetOnlyCreate ? '是' : '否' }}
                </DescriptionsItem>
                <DescriptionsItem
                  v-if="identityTransferAvailable"
                  label="Identity 值保留"
                  :span="2"
                >
                  <span data-testid="identity-summary">
                    {{
                      model.preserveIdentityValues
                        ? '保留原值并校准后续发号'
                        : '未启用'
                    }}； 目标独占：{{
                      model.exclusiveTargetOwnership ? '已声明' : '未声明'
                    }}
                    <template v-if="sqlServerIdentityTransfer">
                      ；源端暂停写入、DDL 和发号：{{
                        model.identitySourceQuiesced ? '已声明' : '未声明'
                      }}
                    </template>
                  </span>
                </DescriptionsItem>
                <DescriptionsItem label="增量变更同步">
                  {{
                    model.targetDataSync
                      ? optionLabel(
                          CHANGE_DATA_SYNC_TYPE_OPTIONS,
                          model.changeDataSyncType,
                        )
                      : '否'
                  }}
                </DescriptionsItem>
                <DescriptionsItem
                  v-if="model.changeDataSyncType === 'INCREASE_COLUMN'"
                  label="增量字段"
                >
                  {{ model.increaseColumnName || '-' }}
                </DescriptionsItem>
                <DescriptionsItem
                  v-if="
                    model.targetDataSync &&
                    model.changeDataSyncType === 'INCREASE_COLUMN'
                  "
                  label="游标前置条件"
                  :span="2"
                >
                  <span data-testid="cursor-summary">
                    {{ cursorPairHint }}
                    {{
                      model.cursorWatermarkContract === 'MONOTONIC_WATERMARK_V1'
                        ? '已声明新增和更新推进水位'
                        : '尚未确认水位契约'
                    }}；
                    {{
                      model.cursorBootstrapAllowed
                        ? '允许不沿用旧水位、重新读取源表'
                        : '未授权从旧水位重新建立检查点'
                    }}。 驱动、部署代次和表结构由服务端在运行前校验。
                  </span>
                </DescriptionsItem>
                <DescriptionsItem label="批处理量">
                  {{ model.batchSize }}
                </DescriptionsItem>
                <DescriptionsItem label="表名映射规则">
                  {{ cleanMappers(model.tableNameMapper).length }} 条
                </DescriptionsItem>
                <DescriptionsItem label="字段名映射规则">
                  {{ cleanMappers(model.columnNameMapper).length }} 条
                </DescriptionsItem>
                <DescriptionsItem label="表名转换">
                  <span data-testid="table-name-case-summary">
                    {{
                      optionLabel(NAME_CASE_OPTIONS, model.targetTableNameCase)
                    }}
                  </span>
                </DescriptionsItem>
                <DescriptionsItem label="字段名转换">
                  <span data-testid="column-name-case-summary">
                    {{
                      optionLabel(NAME_CASE_OPTIONS, model.targetColumnNameCase)
                    }}
                  </span>
                </DescriptionsItem>
              </Descriptions>
            </details>
          </div>

          <!-- 映射转换 -->
          <div v-show="currentKey === 'mapper'">
            <div v-if="model.sourceType === 1" class="mb-5">
              <Alert
                v-if="tableMappingError"
                type="error"
                show-icon
                :message="tableMappingError"
                class="mb-3"
              />
              <Button
                v-if="tableMappingError"
                class="mb-3"
                @click="loadTableMappings"
              >
                <template #icon
                  ><IconifyIcon icon="lucide:refresh-cw"
                /></template>
                重新加载映射
              </Button>
              <TableMapping
                ref="tableMapping"
                :rows="mappingRows"
                :model-value="model.configMap ?? {}"
                :doris="dorisTarget"
                :loading="tableMappingLoading"
                @select-sources="currentStep = stepKeys.indexOf('source')"
                @remove="removeSourceTable"
                @update:model-value="(value) => (model.configMap = value)"
              />
            </div>
            <div v-else class="form-grid mb-5">
              <div v-if="model.sourceType === 2" class="form-item">
                <label class="form-item__label form-item__label--required">
                  目标表名
                </label>
                <Input
                  v-model:value="model.targetTable"
                  allow-clear
                  placeholder="SQL 查询结果写入的目标表名"
                />
              </div>
            </div>
            <TypeMappingDecisions
              v-if="typeMappingAvailable"
              v-model="model.typeMappingAcknowledgements"
              :request="typeMappingRequest"
              :tables="previewTableCandidates"
              class="mb-5"
            />
            <details class="advanced-mapping" :open="model.sourceType === 2">
              <summary>高级名称与字段映射</summary>
              <div class="form-grid mb-4 mt-4">
                <div class="form-item">
                  <label class="form-item__label">表名转换</label>
                  <Select
                    v-model:value="model.targetTableNameCase"
                    :options="NAME_CASE_OPTIONS"
                    class="w-full"
                  />
                  <div class="form-item__tip">
                    在表名映射（入中台库时含 ods_ 前缀）之后执行；Hive
                    目标始终转为小写
                  </div>
                </div>
                <div class="form-item">
                  <label class="form-item__label">字段名转换</label>
                  <Select
                    v-model:value="model.targetColumnNameCase"
                    :options="NAME_CASE_OPTIONS"
                    class="w-full"
                  />
                  <div class="form-item__tip">
                    在字段映射之后执行，同时作用于主键和时间字段；Hive
                    目标始终转为小写
                  </div>
                </div>
              </div>
              <Alert
                type="info"
                show-icon
                message="以下规则决定默认名称；逐表填写的目标名称为最终名称，优先于表名规则和前缀。"
                class="mb-4"
              />

              <div v-if="model.sourceType === 1" class="mapper-block">
                <div class="mapper-block__header">
                  <span class="mapper-block__title">表名映射</span>
                  <div class="flex gap-2">
                    <Button
                      size="small"
                      @click="addMapper(model.tableNameMapper)"
                    >
                      <template #icon
                        ><IconifyIcon icon="lucide:plus"
                      /></template>
                      添加规则
                    </Button>
                    <Button
                      size="small"
                      type="primary"
                      ghost
                      @click="handlePreviewTables"
                    >
                      <template #icon
                        ><IconifyIcon icon="lucide:eye"
                      /></template>
                      预览表名映射
                    </Button>
                  </div>
                </div>
                <div
                  v-if="model.tableNameMapper.length === 0"
                  class="mapper-block__empty"
                >
                  未配置规则，目标表将保持原表名
                </div>
                <div
                  v-for="(item, index) in model.tableNameMapper"
                  :key="`t-${index}`"
                  class="mapper-block__row"
                >
                  <Input
                    v-model:value="item.fromPattern"
                    placeholder="匹配正则，如 ^t_(.*)$"
                  />
                  <span class="mapper-block__arrow">→</span>
                  <Input
                    v-model:value="item.toValue"
                    placeholder="替换为，如 biz_$1"
                  />
                  <Button
                    danger
                    size="small"
                    type="text"
                    @click="removeMapper(model.tableNameMapper, index)"
                  >
                    <template #icon
                      ><IconifyIcon icon="lucide:trash-2"
                    /></template>
                    删除
                  </Button>
                </div>
              </div>

              <div class="mapper-block">
                <div class="mapper-block__header">
                  <span class="mapper-block__title">字段名映射</span>
                  <div class="flex items-center gap-2">
                    <Select
                      v-if="model.sourceType === 1"
                      v-model:value="previewColumnTable"
                      :options="previewTableCandidates"
                      show-search
                      option-filter-prop="label"
                      allow-clear
                      size="small"
                      placeholder="选择预览的表"
                      class="w-52"
                    />
                    <Button
                      size="small"
                      @click="addMapper(model.columnNameMapper)"
                    >
                      <template #icon
                        ><IconifyIcon icon="lucide:plus"
                      /></template>
                      添加规则
                    </Button>
                    <Button
                      size="small"
                      type="primary"
                      ghost
                      @click="handlePreviewColumns"
                    >
                      <template #icon
                        ><IconifyIcon icon="lucide:eye"
                      /></template>
                      预览字段映射
                    </Button>
                  </div>
                </div>
                <div
                  v-if="model.columnNameMapper.length === 0"
                  class="mapper-block__empty"
                >
                  未配置规则，目标字段将保持原字段名
                </div>
                <div
                  v-for="(item, index) in model.columnNameMapper"
                  :key="`c-${index}`"
                  class="mapper-block__row"
                >
                  <Input
                    v-model:value="item.fromPattern"
                    placeholder="匹配正则，如 ^col_(.*)$"
                  />
                  <span class="mapper-block__arrow">→</span>
                  <Input
                    v-model:value="item.toValue"
                    :placeholder="
                      model.sourceType === 2
                        ? '替换为（SQL 接入不允许为空）'
                        : '替换为，留空表示不同步该字段'
                    "
                  />
                  <Button
                    danger
                    size="small"
                    type="text"
                    @click="removeMapper(model.columnNameMapper, index)"
                  >
                    <template #icon
                      ><IconifyIcon icon="lucide:trash-2"
                    /></template>
                    删除
                  </Button>
                </div>
              </div>
            </details>
          </div>

          <!-- 确认提交 -->
          <div v-show="currentKey === 'confirm'">
            <!-- Seatunnel 任务摘要 -->
            <template v-if="model.engineType === 2">
              <Descriptions :column="2" bordered size="small" class="mb-4">
                <DescriptionsItem label="任务名称">
                  {{ model.taskName || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="同步类型">
                  {{ optionLabel(ENGINE_TYPE_OPTIONS, model.engineType) }}
                </DescriptionsItem>
                <DescriptionsItem label="调度类型">
                  {{ optionLabel(TASK_TYPE_OPTIONS, model.taskType) }}
                </DescriptionsItem>
                <DescriptionsItem label="cron 表达式">
                  {{ model.cron || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="配置格式">
                  {{ model.configFormat }}
                </DescriptionsItem>
                <DescriptionsItem label="配置长度">
                  {{ model.seatunnelConfig?.length ?? 0 }} 字符
                </DescriptionsItem>
              </Descriptions>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- API 字段映射编辑弹窗 -->
    <Modal
      v-model:open="columnMapVisible"
      :footer="null"
      :title="`字段映射 - ${columnMapTarget?.sourceApiName ?? ''}`"
      width="680px"
    >
      <Table
        :columns="[
          { title: 'API 字段', dataIndex: 'sourceColumn', key: 'sourceColumn' },
          { title: '目标表字段', key: 'targetColumn' },
          { title: '导入', key: 'importFlg', width: 70 },
        ]"
        :data-source="columnMapTarget?.columnMaps ?? []"
        :pagination="false"
        :scroll="{ y: 400 }"
        row-key="sourceColumn"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'targetColumn'">
            <Input v-model:value="record.targetColumn" size="small" />
          </template>
          <template v-else-if="column.key === 'importFlg'">
            <Checkbox
              :checked="record.importFlg === 1"
              @change="
                (e: any) => (record.importFlg = e.target.checked ? 1 : 0)
              "
            />
          </template>
        </template>
      </Table>
    </Modal>

    <!-- 名称映射预览弹窗 -->
    <Modal
      v-model:open="previewVisible"
      :title="previewTitle"
      :footer="null"
      width="720px"
    >
      <Table
        :columns="previewColumns"
        :data-source="previewData"
        :loading="previewLoading"
        :pagination="{ pageSize: 10, showSizeChanger: false }"
        row-key="originalName"
        size="small"
      />
    </Modal>

    <template #footer>
      <div class="wizard__footer">
        <span class="wizard__progress">
          第 {{ currentStep + 1 }} / {{ stepKeys.length }} 步 ·
          {{ STEP_META[currentKey].title }}
        </span>
        <div class="flex gap-2">
          <Button :disabled="saving" @click="closeForm()">返回列表</Button>
          <Button v-if="currentStep > 0" :disabled="saving" @click="prevStep">
            上一步
          </Button>
          <Button
            v-if="currentStep < stepKeys.length - 1"
            type="primary"
            :disabled="
              saving || (currentKey === 'mapper' && tableMappingLoading)
            "
            @click="nextStep"
          >
            下一步
          </Button>
          <Button
            v-if="currentStep === stepKeys.length - 1"
            type="primary"
            :loading="saving"
            @click="handleSubmit"
          >
            {{ isEdit ? '保存修改' : '创建任务' }}
          </Button>
        </div>
      </div>
    </template>
  </component>
</template>

<style scoped>
.wizard {
  display: flex;
  height: 560px;
  overflow: hidden;
}

.api-config-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 离线与实时同步共用横向向导，按各自配置流程显示步骤。 */
.wizard--sync {
  flex-direction: column;
  height: min(720px, calc(100vh - 220px));
  min-height: 400px;
}

.wizard--sync .wizard__rail {
  width: 100%;
  padding: 22px 40px;
  overflow-y: visible;
  border-right: 0;
  border-bottom: 1px solid hsl(var(--border));
}

.wizard--sync .wizard__main {
  min-height: 0;
}

.section-heading {
  padding-left: 10px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.advanced-mapping {
  padding: 14px 16px;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.advanced-mapping > summary {
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.advanced-mapping > .summary-flow {
  margin-top: 16px;
}

.source-transfer {
  justify-content: space-between;
}

/* 页内布局沿用系统主题，步骤和表单分为两张卡片。 */
.wizard--embedded {
  flex: 1;
  gap: 16px;
  height: auto;
  min-height: 0;
}

.wizard--embedded .wizard__rail {
  padding: 26px max(32px, 8%);
  background: hsl(var(--background));
  border: 0;
}

.wizard--embedded .wizard__main {
  min-height: 0;
  background: hsl(var(--background));
}

.wizard--embedded .wizard__header {
  padding: 24px 32px 0;
  border: 0;
}

.wizard--embedded .wizard__title {
  padding-left: 10px;
  border-left: 3px solid hsl(var(--primary));
}

.wizard--embedded .wizard__desc {
  margin-top: 12px;
}

.wizard--embedded .wizard__content {
  padding: 24px 32px 40px;
}

.wizard--embedded .form-grid--basic .form-item {
  display: grid;
  grid-template-columns: 126px minmax(0, 1fr);
  gap: 8px 12px;
  align-content: start;
  align-items: start;
}

.wizard--embedded .form-grid--basic .form-item__label {
  padding-top: 5px;
  text-align: right;
}

.wizard--embedded .form-grid--basic .form-item__tip {
  grid-column: 2;
}

.wizard--embedded .section-heading {
  display: block;
  margin: 8px 0;
}

/* ===== 左侧流程轨 ===== */
.wizard__rail {
  flex-shrink: 0;
  width: 216px;
  padding: 24px 20px;
  overflow-y: auto;
  background: hsl(var(--muted) / 35%);
  border-right: 1px solid hsl(var(--border));
}

.wizard__rail :deep(.ant-steps-item-title) {
  font-size: 13px;
  font-weight: 500;
}

.wizard__rail :deep(.ant-steps-item-description) {
  font-size: 12px;
}

.wizard__rail :deep(.ant-steps-item) {
  cursor: pointer;
}

/* ===== 右侧内容区 ===== */
.wizard__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.wizard__header {
  flex-shrink: 0;
  padding: 20px 28px 14px;
  border-bottom: 1px solid hsl(var(--border) / 60%);
}

.wizard__title {
  font-size: 15px;
  font-weight: 600;
  color: hsl(var(--foreground));
}

.wizard__desc {
  margin-top: 2px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.wizard__content {
  flex: 1;
  min-height: 0;
  padding: 18px 28px 24px;
  overflow-y: auto;
}

/* ===== 表单网格 ===== */
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.form-item--span2 {
  grid-column: 1 / -1;
}

.form-item__label {
  font-size: 14px;
  font-weight: 500;
  color: hsl(var(--foreground));
}

.form-item__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}

.form-item__tip {
  font-size: 12px;
  line-height: 1.6;
  color: hsl(var(--muted-foreground));
}

/* ===== 同步选项开关组 ===== */
.switch-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  padding: 14px 16px;
  border: 1px solid hsl(var(--border) / 70%);
  border-radius: 8px;
}

.switch-grid__item {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

/* ===== 名称映射块 ===== */
.mapper-block {
  padding: 14px 16px;
  margin-bottom: 16px;
  border: 1px solid hsl(var(--border) / 70%);
  border-radius: 8px;
}

.mapper-block__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.mapper-block__title {
  font-size: 13px;
  font-weight: 600;
}

.mapper-block__empty {
  padding: 10px 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.mapper-block__row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.mapper-block__arrow {
  flex-shrink: 0;
  color: hsl(var(--muted-foreground));
}

/* ===== 确认页：链路示意 ===== */
.summary-flow {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 16px 20px;
  margin-bottom: 16px;
  border: 1px solid hsl(var(--border) / 70%);
  border-radius: 8px;
}

.summary-flow__node {
  min-width: 0;
}

.summary-flow__label {
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.summary-flow__name {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.summary-flow__arrow {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.summary-flow__line {
  position: relative;
  width: 100%;
  height: 1px;
  background: hsl(var(--border));
}

.summary-flow__line::after {
  position: absolute;
  top: -3px;
  right: 0;
  content: '';
  border-top: 3.5px solid transparent;
  border-bottom: 3.5px solid transparent;
  border-left: 6px solid hsl(var(--border));
}

.summary-sql {
  max-height: 120px;
  padding: 0;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
}

.summary-sql--tall {
  max-height: 240px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.seatunnel-editor {
  height: 360px;
  min-height: 240px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

/* ===== 底部 ===== */
.wizard__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.wizard__progress {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

/* ===== 小屏适配 ===== */
@media (max-width: 768px) {
  .wizard {
    height: auto;
  }

  .wizard__rail {
    display: none;
  }

  .wizard--embedded .wizard__rail {
    display: block;
    padding: 12px;
    overflow-x: auto;
  }

  .wizard--embedded .form-grid--basic .form-item {
    grid-template-columns: 1fr;
  }

  .wizard--embedded .form-grid--basic .form-item__label {
    text-align: left;
  }

  .wizard--embedded .form-grid--basic .form-item__tip {
    grid-column: 1;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }

  .form-item--span2 {
    grid-column: auto;
  }

  .switch-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
