import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ReferenceFlowEditor from './reference-flow-editor.vue';

vi.mock('./reference-clean-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./reference-pivot-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./reference-subflow-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('antdv-next', () => {
  const Field = defineComponent({props:{value:[String,Number],disabled:Boolean},emits:['update:value','blur','pressEnter'],setup:(props,{emit}) => () => h('input',{value:props.value,disabled:props.disabled,onInput:(event:Event) => emit('update:value',(event.target as HTMLInputElement).value),onBlur:() => emit('blur'),onKeydown:(event:KeyboardEvent) => { if (event.key==='Enter') emit('pressEnter'); }})});
  return {
    AutoComplete:Field, Input:Field, InputNumber:Field, Select:Field,
    Checkbox:defineComponent({props:{checked:Boolean,disabled:Boolean},emits:['update:checked'],setup:(props,{emit}) => () => h('input',{type:'checkbox',checked:props.checked,disabled:props.disabled,onChange:(event:Event) => emit('update:checked',(event.target as HTMLInputElement).checked)})}),
    Alert:defineComponent({props:{message:String},setup:(props) => () => h('div',props.message)}),
    Button:defineComponent({props:{disabled:Boolean},setup:(props,{slots}) => () => h('button',{disabled:props.disabled},slots.default?.())}),
  };
});
let app:App|undefined;
let host:HTMLDivElement;
const editor = ref<{ stepNav?: { last: number; next: () => void; prev: () => void; step: number }; validate: () => boolean }>();
afterEach(() => {app?.unmount(); host?.remove();});
function mount(toolCode:string,config:Record<string,any>,disabled=false) {
  host=document.createElement('div'); document.body.append(host);
  const state=reactive({config,disabled});
  app=createApp({render:() => h(ReferenceFlowEditor,{ref:editor,...state,toolCode,inputFields:[{name:'id',dataType:'BIGINT'},{name:'name',dataType:'STRING'}],upstreamNodes:[{nodeNo:'a',name:'上游A',fields:[{name:'id',dataType:'BIGINT'}]},{nodeNo:'b',name:'上游B',fields:[{name:'other',dataType:'BIGINT'}]}],downstreamNodes:[{targetId:'sink',targetName:'输出'}]})});
  app.mount(host); return state;
}
async function click(text:string) { const button=[...host.querySelectorAll('button')].find((item) => item.textContent?.trim().endsWith(text)); expect(button).toBeTruthy(); button!.click(); await nextTick(); }
async function input(selector:string,value:string) {const field=host.querySelector<HTMLInputElement>(selector)!; expect(field).toBeTruthy(); field.value=value; field.dispatchEvent(new Event('input')); await nextTick();}
describe('集成流程的分步配置和真实兼容契约', () => {
  it('join三步按来源投影，不在仅浏览时补写历史投影', async () => {
    const config = {
      joinType:'LEFT',
      conditionRelation: 'OR',
      maxOutputRows:30,
      joinConditions:[{leftField:'id',rightField:'other',vendor:7}],
      vendor:9,
    };
    const state=mount('multi-join',config);
    expect(host.textContent).not.toContain('连接关系与执行预算');
    expect(host.querySelector('details')).toBeNull();
    expect([...host.querySelectorAll('th')].map((cell) => cell.textContent)).toEqual(['A组件字段','运算符','B组件字段','操作']);
    await input('tbody td:first-child input','new_id');
    expect(state.config.joinConditions[0]).toEqual({leftField:'new_id',rightField:'other',vendor:7});
    await click('A组件字段配置');
    expect(state.config.leftFields).toBeUndefined();
    expect(host.querySelector('tbody td:nth-child(2) input')?.getAttribute('value')).toBe('id');
    await input('tbody td:nth-child(3) input','a_id');
    expect(state.config.leftFields).toEqual([{name:'id',alias:'a_id',type:'BIGINT',length:undefined,precision:undefined}]);
    expect(state.config.vendor).toBe(9);
    expect(state.config.maxOutputRows).toBe(30);
    expect(state.config.conditionRelation).toBe('OR');
    expect(editor.value!.validate()).toBe(true);
    state.config.joinConditions[0].rightField = '';
    expect(editor.value!.validate()).toBe(false);
    await nextTick();
    expect(host.textContent).toContain('请填写完整连接条件');
  });
  it('条件4步将旧单条件提升为多条件并保留未知分支及输出投影',async() => {
    const state=mount('data-condition',{branches:[{id:'a',type:'IF',fieldName:'id',operator:'EQ',value:'1',targetNodeNo:'sink',vendor:7},{id:'b',type:'ELSE',targetNodeNo:'other'},{id:'legacy',type:'IF',fieldName:'name',operator:'EQ',value:'keep',targetNodeNo:'third'}],unmatchedPolicy:'ERROR'});
    expect(host.textContent).not.toContain('分支兼容配置');
    expect(host.querySelector('details')).toBeNull();
    expect(host.textContent).not.toContain('添加条件分支');
    expect(host.textContent).not.toContain('删除分支');
    expect(host.querySelector('[placeholder="请选择True组件"]')).not.toBeNull();
    expect(host.querySelector('[placeholder="请选择False组件"]')).not.toBeNull();
    const legacy = JSON.stringify(state.config.branches[2]);
    const before=JSON.stringify(state.config);
    expect(editor.value!.stepNav).toMatchObject({ step: 0, last: 3 });
    editor.value!.stepNav!.next(); await nextTick(); expect(JSON.stringify(state.config)).toBe(before);
    expect(editor.value!.stepNav!.step).toBe(1);
    expect(host.textContent).toContain('条件说明');
    expect(host.textContent).not.toContain('未配置条件时');
    await click('添加条件'); expect(state.config.branches[0].conditions).toEqual([{fieldName:'id',operator:'EQ',value:'1'},{fieldName:'',operator:'',value:''}]);
    expect(editor.value!.validate()).toBe(false);
    await nextTick(); expect(host.textContent).toContain('请完善分发条件的字段名称和判断条件');
    state.config.branches[0].conditions.pop();
    expect(state.config.branches[0].vendor).toBe(7); expect(state.config.branches[2].id).toBe('legacy');
    await click('True组件字段配置');
    expect([...host.querySelectorAll('tbody tr')]).toHaveLength(2);
    expect(state.config.branches[0].fields).toBeUndefined();
    const removeRow=[...host.querySelectorAll('tbody tr')][1]!.querySelector('button')!; removeRow.click(); await nextTick();
    expect(state.config.branches[0].fields).toEqual(['id']); expect(state.config.branches[1].fields).toBeUndefined();
    await click('添加字段');
    expect(state.config.branches[0].fields).toEqual(['id','name']);
    expect(host.querySelector('.qi-flow-add-row button')!.hasAttribute('disabled')).toBe(true);
    expect(host.textContent).toContain('条件判断结果为True 时，流转至该组件。');
    await click('False组件字段配置');
    expect([...host.querySelectorAll('tbody tr')]).toHaveLength(2);
    expect(host.textContent).toContain('条件判断结果为False 时，流转至该组件。');
    expect(JSON.stringify(state.config.branches[2])).toBe(legacy);
    expect(state.config.unmatchedPolicy).toBe('ERROR');
    expect(editor.value!.validate()).toBe(true);
    state.config.branches[2].targetNodeNo = '';
    expect(editor.value!.validate()).toBe(false);
    await nextTick();
    expect(host.textContent).toContain('请选择分支下游组件');
  });
  it('条件值以标签展示并可清除，空值运算符不需要比较值，历史运算符保留不可再选', async () => {
    const state=mount('data-condition',{branches:[{id:'a',type:'IF',conditions:[{fieldName:'id',operator:'GT',value:'3'},{fieldName:'name',operator:'IS_NULL',value:''},{fieldName:'name',operator:'EQ',value:'x'},{fieldName:'id',operator:'NE',value:''}],targetNodeNo:'sink'}]});
    editor.value!.stepNav!.next(); await nextTick();
    const operatorSelect=host.querySelector<HTMLInputElement>('[aria-label="第1行判断条件"]')!;
    expect(operatorSelect).toBeTruthy();
    const tags=[...host.querySelectorAll('.qi-flow-value-tag')].map((tag) => tag.firstElementChild?.textContent);
    expect(tags).toEqual(['3','x']);
    expect(host.querySelectorAll('.qi-flow-value-add')).toHaveLength(1);
    expect(host.querySelectorAll('.qi-flow-value-cell')).toHaveLength(3);
    (host.querySelectorAll('.qi-flow-value-tag button')[1] as HTMLButtonElement).click(); await nextTick();
    expect(state.config.branches[0].conditions[2].value).toBe('');
    expect(editor.value!.validate()).toBe(false);
    await nextTick(); expect(host.textContent).toContain('请填写分发条件的比较值');
    (host.querySelector('.qi-flow-value-add') as HTMLButtonElement).click(); await nextTick();
    await input('[aria-label="第3行比较值"]','y');
    host.querySelector<HTMLInputElement>('[aria-label="第3行比较值"]')!.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter'}));
    await nextTick();
    expect(state.config.branches[0].conditions[2].value).toBe('y');
    expect(host.querySelector('[aria-label="第3行比较值"]')).toBeNull();
    expect(editor.value!.validate()).toBe(false);
    state.config.branches[0].conditions[3].value='z';
    expect(editor.value!.validate()).toBe(true);
  });
  it('只读浏览切换步骤或伪造输入均不改写节点',async() => {
    const state=mount('multi-join',{joinConditions:[{leftField:'id',rightField:'other'}],vendor:9},true);
    const before=JSON.stringify(state.config);
    await input('tbody td:first-child input','ignored'); await click('A组件字段配置');
    await input('tbody td:nth-child(3) input','ignored');
    expect(JSON.stringify(state.config)).toBe(before);
  });
});
