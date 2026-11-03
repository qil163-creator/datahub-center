import type { Component } from 'vue';

import AddConstant from './add-constant.vue';
import Calculator from './calculator.vue';
import CartesianProduct from './cartesian-product.vue';
import ColumnMap from './column-map.vue';
import DataClean from './data-clean.vue';
import DataCondition from './data-condition.vue';
import DataDecrypt from './data-decrypt.vue';
import DataDesensitize from './data-desensitize.vue';
import DataEncrypt from './data-encrypt.vue';
import DataFilter from './data-filter.vue';
import DataMerge from './data-merge.vue';
import FieldDerive from './field-derive.vue';
import FieldSplit from './field-split.vue';
import FlattenRow from './flatten-row.vue';
import GenerateRecords from './generate-records.vue';
import IdGenerator from './id-generator.vue';
import InsertDb from './insert-db.vue';
import JavaScript from './java-script.vue';
import JsScript from './js-script.vue';
import ManagedDbOutput from './managed-db-output.vue';
import MultiJoin from './multi-join.vue';
import NumberRange from './number-range.vue';
import PythonScript from './python-script.vue';
import QualityCheck from './quality-check.vue';
import RandomRecords from './random-records.vue';
import ReadCsv from './read-csv.vue';
import ReadDb from './read-db.vue';
import ReadExcel from './read-excel.vue';
import ReadGzipCsv from './read-gzip-csv.vue';
import ReadJson from './read-json.vue';
import ReadKafka from './read-kafka.vue';
import { defaultRestApiNodeConfig } from './rest-api-config';
import RestApi from './rest-api.vue';
import RowColumn from './row-column.vue';
import ShellScript from './shell-script.vue';
import SortMerge from './sort-merge.vue';
import SortRecords from './sort-records.vue';
import SplitToRows from './split-to-rows.vue';
import SqlScript from './sql-script.vue';
import SubPipeline from './sub-pipeline.vue';
import SubflowBoundary from './subflow-boundary.vue';
import ValueMap from './value-map.vue';
import WriteCsv from './write-csv.vue';
import WriteExcel from './write-excel.vue';
import WriteJson from './write-json.vue';
import WriteKafka from './write-kafka.vue';
import WriteText from './write-text.vue';

/** toolCode → 节点配置编辑器组件 */
const editors: Record<string, Component> = {
  'sub-pipeline': SubPipeline,
  'boundary-input': SubflowBoundary,
  'boundary-output': SubflowBoundary,
  'read-db': ReadDb,
  'read-kafka': ReadKafka,
  'rest-api': RestApi,
  'read-excel': ReadExcel,
  'read-csv': ReadCsv,
  'read-gzip-csv': ReadGzipCsv,
  'read-json': ReadJson,
  'insert-db': InsertDb,
  'managed-db-output': ManagedDbOutput,
  'write-kafka': WriteKafka,
  'write-excel': WriteExcel,
  'write-csv': WriteCsv,
  'write-json': WriteJson,
  'write-text': WriteText,
  'column-map': ColumnMap,
  'data-filter': DataFilter,
  'data-clean': DataClean,
  'data-encrypt': DataEncrypt,
  'data-decrypt': DataDecrypt,
  'data-merge': DataMerge,
  'sort-merge': SortMerge,
  'multi-join': MultiJoin,
  'cartesian-product': CartesianProduct,
  'flatten-row': FlattenRow,
  'data-desensitize': DataDesensitize,
  'data-condition': DataCondition,
  'field-derive': FieldDerive,
  'add-constant': AddConstant,
  'row-column': RowColumn,
  'sort-records': SortRecords,
  'field-split': FieldSplit,
  'id-generator': IdGenerator,
  calculator: Calculator,
  'value-map': ValueMap,
  'quality-check': QualityCheck,
  'number-range': NumberRange,
  'split-to-rows': SplitToRows,
  'generate-records': GenerateRecords,
  'random-records': RandomRecords,
  'shell-script': ShellScript,
  'python-script': PythonScript,
  'sql-script': SqlScript,
  'java-script': JavaScript,
  'js-script': JsScript,
};

export function getNodeEditor(toolCode?: string): Component | undefined {
  return toolCode ? editors[toolCode] : undefined;
}

export function isNodeSupported(toolCode?: string): boolean {
  return !!toolCode && !!editors[toolCode];
}

export function getDefaultNodeConfig(toolCode?: string, nodeName?: string) {
  return toolCode === 'rest-api' ? defaultRestApiNodeConfig(nodeName) : {};
}
