import type { App } from 'vue';

import { createApp,defineComponent,h,nextTick,reactive } from 'vue';

import {afterEach,describe,expect,it,vi} from 'vitest';

import TransformNodeEditor from './transform-node-editor.vue';
vi.mock('./reference-field-merge-dialog.vue',() => ({default:{render:()=>null}}));
vi.mock('antdv-next',() => {
  const Field=defineComponent({props:{value:[String,Number,Array],disabled:Boolean,options:Array},emits:['update:value'],setup:(props,{emit}) => () => h('input',{disabled:props.disabled,value:props.value,onInput:(event:Event) => { const value=(event.target as HTMLInputElement).value; emit('update:value',(props.options as undefined|{value:unknown}[])?.some((option) => typeof option.value==='number') ? Number(value) : value); }})});
  return {AutoComplete:Field,Input:Field,InputNumber:Field,Select:Field,TextArea:Field,Alert:{render:()=>null},Checkbox:{render:()=>null},Modal:{confirm:vi.fn()},Button:defineComponent({setup:(_,{slots})=>()=>h('button',slots.default?.())})};
});
let app:App|undefined,host:HTMLDivElement;
afterEach(()=>{app?.unmount();host?.remove();});
function mount(config:Record<string,any>,disabled=false) { host=document.createElement('div');document.body.append(host);const state=reactive({config,disabled});app=createApp({render:()=>h(TransformNodeEditor,{...state,toolCode:'dedup-records',inputFields:[{name:'name',dataType:'STRING'},{name:'city',dataType:'VARCHAR'},{name:'id',dataType:'BIGINT'}]})});app.mount(host);return state; }
async function input(selector:string,value:string) {const field=host.querySelector<HTMLInputElement>(selector)!;field.value=value;field.dispatchEvent(new Event('input'));await nextTick();}
describe('逐字段大小写去重契约',()=>{
  it('首次逐字段修改从旧global初始化，保留其他字符串键与未知参数',async()=>{
    const state=mount({keyFields:['name','city','id'],ignoreCase:true,vendor:7});
    await input('[aria-label="name忽略大小写"]','0');
    expect(state.config.ignoreCaseFields).toEqual(['city']);expect(state.config.ignoreCase).toBe(true);expect(state.config.vendor).toBe(7);
    expect(host.querySelector<HTMLInputElement>('[aria-label="id忽略大小写"]')?.disabled).toBe(true);
  });
  it('显式空数组覆盖历史global，替换字段清除无效大小写引用',async()=>{
    const state=mount({keyFields:['name','city'],ignoreCase:true,ignoreCaseFields:[]});
    expect(host.querySelector<HTMLInputElement>('[aria-label="name忽略大小写"]')?.value).toBe('0');
    await input('[aria-label="name忽略大小写"]','1');expect(state.config.ignoreCaseFields).toEqual(['name']);
    await input('tbody tr:first-child td:nth-child(2) input','id');expect(state.config.ignoreCaseFields).toEqual([]);
  });
  it('只读事件不能开启逐字段配置',async()=>{
    const state=mount({keyFields:['name'],ignoreCase:true,vendor:9},true);const before=JSON.stringify(state.config);
    await input('[aria-label="name忽略大小写"]','0');expect(JSON.stringify(state.config)).toBe(before);
  });
});
