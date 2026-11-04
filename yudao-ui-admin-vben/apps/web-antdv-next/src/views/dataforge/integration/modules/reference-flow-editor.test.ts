import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ReferenceFlowEditor from './reference-flow-editor.vue';

vi.mock('./reference-clean-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./reference-pivot-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('./reference-subflow-editor.vue', () => ({ default: { render: () => null } }));
vi.mock('antdv-next', () => {
  const Field = defineComponent({props:{value:[String,Number],disabled:Boolean},emits:['update:value'],setup:(props,{emit}) => () => h('input',{value:props.value,disabled:props.disabled,onInput:(event:Event) => emit('update:value',(event.target as HTMLInputElement).value)})});
  return {
    AutoComplete:Field, Input:Field, InputNumber:Field, Select:Field,
    Checkbox:defineComponent({props:{checked:Boolean,disabled:Boolean},emits:['update:checked'],setup:(props,{emit}) => () => h('input',{type:'checkbox',checked:props.checked,disabled:props.disabled,onChange:(event:Event) => emit('update:checked',(event.target as HTMLInputElement).checked)})}),
    Alert:defineComponent({props:{message:String},setup:(props) => () => h('div',props.message)}),
    Button:defineComponent({props:{disabled:Boolean},setup:(props,{slots}) => () => h('button',{disabled:props.disabled},slots.default?.())}),
  };
});
let app:App|undefined; let host:HTMLDivElement;
afterEach(() => {app?.unmount(); host?.remove();});
function mount(toolCode:string,config:Record<string,any>,disabled=false) {
  host=document.createElement('div'); document.body.append(host);
  const state=reactive({config,disabled});
  app=createApp({render:() => h(ReferenceFlowEditor,{...state,toolCode,inputFields:[{name:'id',dataType:'BIGINT'},{name:'name',dataType:'STRING'}],upstreamNodes:[{nodeNo:'a',name:'上游A',fields:[{name:'id',dataType:'BIGINT'}]},{nodeNo:'b',name:'上游B',fields:[{name:'other',dataType:'BIGINT'}]}],downstreamNodes:[{targetId:'sink',targetName:'输出'}]})});
  app.mount(host); return state;
}
async function click(text:string) { const button=[...host.querySelectorAll('button')].find((item) => item.textContent?.trim().endsWith(text)); expect(button).toBeTruthy(); button!.click(); await nextTick(); }
async function input(selector:string,value:string) {const field=host.querySelector<HTMLInputElement>(selector)!; expect(field).toBeTruthy(); field.value=value; field.dispatchEvent(new Event('input')); await nextTick();}
describe('集成流程的分步配置和真实兼容契约',() => {
  it('join三步按来源投影，不在仅浏览时补写历史投影',async() => {
    const config={joinType:'LEFT',maxOutputRows:30,joinConditions:[{leftField:'id',rightField:'other',vendor:7}],vendor:9};
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
    expect(state.config.vendor).toBe(9); expect(state.config.maxOutputRows).toBe(30);
  });
  it('条件4步将旧单条件提升为多条件并保留未知分支及输出投影',async() => {
    const state=mount('data-condition',{branches:[{id:'a',type:'IF',fieldName:'id',operator:'EQ',value:'1',targetNodeNo:'sink',vendor:7},{id:'b',type:'ELSE',targetNodeNo:'other'},{id:'legacy',type:'IF',fieldName:'name',operator:'EQ',value:'keep',targetNodeNo:'third'}],unmatchedPolicy:'ERROR'});
    expect(host.textContent).not.toContain('分支兼容配置');
    expect(host.querySelector('details')).toBeNull();
    const before=JSON.stringify(state.config);
    await click('分发条件配置'); expect(JSON.stringify(state.config)).toBe(before);
    await click('添加条件'); expect(state.config.branches[0].conditions).toEqual([{fieldName:'id',operator:'EQ',value:'1'},{fieldName:'',operator:'EQ',value:''}]);
    expect(state.config.branches[0].vendor).toBe(7); expect(state.config.branches[2].id).toBe('legacy');
    await click('True组件字段配置');
    const checkbox=host.querySelector<HTMLInputElement>('input[aria-label="输出字段name"]')!; checkbox.checked=false; checkbox.dispatchEvent(new Event('change')); await nextTick();
    expect(state.config.branches[0].fields).toEqual(['id']); expect(state.config.branches[1].fields).toBeUndefined();
    expect(state.config.unmatchedPolicy).toBe('ERROR');
  });
  it('只读浏览切换步骤或伪造输入均不改写节点',async() => {
    const state=mount('multi-join',{joinConditions:[{leftField:'id',rightField:'other'}],vendor:9},true);
    const before=JSON.stringify(state.config);
    await input('tbody td:first-child input','ignored'); await click('A组件字段配置');
    await input('tbody td:nth-child(3) input','ignored');
    expect(JSON.stringify(state.config)).toBe(before);
  });
});
