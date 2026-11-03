import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
const out = 'C:/Users/LZQ/.codex/visualizations/2026/10/13/01a13330-8ba5-71c3-bf2c-358944d1711b';
await mkdir(out, {recursive:true});
const browser = await chromium.launch({headless:true, channel:'msedge'});
const page = await browser.newPage({viewport:{width:1660,height:1100}});
const errors = [];
page.on('pageerror', e => errors.push(e.message));
const now = Date.now();
const rule = {id:'9007199254740993',databaseId:9,name:'连接状态监控',description:'数据库连接断开时记录告警',metric:'CONNECTION_STATUS',operator:'EQ',threshold:'0',level:'URGENT',status:0,owner:'测试负责人',creator:'测试用户',createTime:now,updateTime:now};
const alarm = {id:'9007199254740995',databaseId:9,ruleId:rule.id,ruleName:rule.name,description:rule.description,metric:rule.metric,operator:'EQ',threshold:'0',currentValue:'0',level:'URGENT',status:'PENDING',firstTime:now,lastTime:now,occurrences:2};
await page.route('**/admin-api/**',async route => {
 const u=new URL(route.request().url()); let data = {};
 if(u.pathname.endsWith('/database/get')) data={id:9,name:'示例业务库',databaseTypeName:'PostgreSQL',typeCode:'POSTGRESQL',databaseIp:'database.example.invalid',databasePort:'5432',databaseName:'sample',databaseSchema:'public',status:0,userName:'preview_account',orgName:'数据管理部门',creatorName:'测试用户',updaterName:'测试用户',createTime:now,updateTime:now,remark:'数据库详情页浏览器验证数据',usageType:1};
 else if(u.pathname.endsWith('/overview') || u.pathname.endsWith('/collect')) data={connectionStatus:1,lastCollectedTime:now,collectionStatus:'SUCCESS',responseTimeMs:59,todayOutputRows:'12045',todayInputRows:'8430',totalTransferRows:'20475',pendingAlarmCount:1,averageResponseTimeMs:63,readSpeed:1420,writeSpeed:910,throughputUnit:'rows/s',capacityUsed:'2147483648',capacityTotal:null,supportedMetrics:['CONNECTION_STATUS','RESPONSE_TIME'],logCoverage:'统计平台同步任务及 SQL 执行记录，不含数据库外部操作。'};
 else if(u.pathname.endsWith('/trend')) {const days=Number(u.searchParams.get('days')||7);data={days,supportedMetrics:[],points:Array.from({length:days},(_,i)=>({date:`2026-10-${String(i+1).padStart(2,'0')}`,inputRows:'100',outputRows:'200',readSpeed:800+i*20+(i%3)*100,writeSpeed:600+i*20+(i%2)*200,responseTimeMs:40+(i%3)*12,onlineRate:100}))};}
 else if(u.pathname.endsWith('/log/page')) data={list:[{id:'ACCESS:1',recordId:1,taskName:'用户数据同步',description:'同步任务执行记录',sourceModule:'ACCESS',outputRows:'12045',inputRows:null,status:'SUCCESS',createTime:now,startTime:now-15000,endTime:now,durationMs:15000},{id:'DEV:2',recordId:2,taskName:'订单数据查询',description:'SQL 执行记录',sourceModule:'DEV',outputRows:null,inputRows:null,status:'SUCCESS',createTime:now,startTime:now-900,endTime:now,durationMs:900}],total:2};
 else if(u.pathname.endsWith('/rule/page')) data={list:[rule],total:1};
 else if(u.pathname.endsWith('/rule/get')) data=rule;
 else if(u.pathname.endsWith('/alarm/page')) data={list:[alarm],total:1};
 else if(u.pathname.endsWith('/alarm/get')) data=alarm;
 else if(u.pathname.endsWith('/alarm/handle')) {alarm.status='HANDLED';data=true;}
 else if(u.pathname.endsWith('/simple-list')) data=[];
 await route.fulfill({contentType:'application/json',body:JSON.stringify({code:0,data,msg:''})});
});
await page.goto('http://127.0.0.1:6099/database-detail-preview.html',{waitUntil:'networkidle',timeout:120000});
await page.getByText('示例业务库',{exact:true}).waitFor({timeout:60000});
await page.screenshot({path:`${out}/database-detail-monitor.png`,fullPage:true});
for(const [tab,name] of [['读写日志','logs'],['告警记录','alarms'],['告警规则','rules'],['基本信息','basic']]) {
 await page.getByRole('tab',{name:tab,exact:true}).click();
 await page.waitForTimeout(700);
 await page.screenshot({path:`${out}/database-detail-${name}.png`,fullPage:true});
}
await page.getByRole('tab',{name:'告警规则',exact:true}).click();
await page.getByRole('button',{name:'新增',exact:true}).click();
await page.waitForTimeout(500);
await page.screenshot({path:`${out}/database-detail-rule-form.png`,fullPage:true});
console.log(JSON.stringify({errors,overflow:await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)}));
await browser.close();

