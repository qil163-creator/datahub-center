#!/usr/bin/env python3
"""通过平台 API 验证三引擎，使用独立 PostgreSQL 样例并严格比对实际落库值。"""
import argparse,json,time,os,subprocess,urllib.request,urllib.parse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--config',required=True,help='0600 私密 JSON；见 PLATFORM_API_VALIDATION.md')
args=parser.parse_args()
config=json.load(open(args.config))
def call(path,method='GET',body=None,query=None):
 url=config['apiBase']+path+('?' + urllib.parse.urlencode(query) if query else '')
 data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
 request=urllib.request.Request(url,data=data,headers={'Authorization':'Bearer '+config['apiToken'],'Content-Type':'application/json'},method=method)
 with urllib.request.urlopen(request,timeout=40) as response:value=json.load(response)
 if value.get('code')!=0:raise RuntimeError(json.dumps({'path':path,'code':value.get('code'),'message':value.get('msg')},ensure_ascii=False))
 return value.get('data')

def run(engine):
 if engine not in ('DATAX','SPARK','FLINK'): raise ValueError('仅允许受测原生引擎')
 count=subprocess.check_output(['docker','exec',config['pgContainer'],'psql','-U','postgres','-d',config['pgDatabase'],'-At','-c',"SELECT count(*) FROM public.native_target_"+engine.lower()],text=True).strip()
 if count!='0': raise RuntimeError('验收目标必须为空；请新建独立样例库，脚本不清空已有数据')
 category=next(c['id'] for c in call('/categories') if c.get('validFlag') is not False)
 task=call('/create','POST',{'mode':'OFFLINE','categoryId':category,'name':'native_DS_'+engine+'_API_'+str(int(time.time())), 'ownerId':1,'status':0,'engine':engine,'scheduler':'DOLPHIN_SCHEDULER','workerGroup':config['workerGroup'],'engineConfig':{}})
 def node(id,code,cfg,kind):return {'id':id,'type':'etl-node','x':100,'y':100,'properties':{'name':id,'toolCode':code,'toolType':kind,'nodeJson':cfg}}
 graph={'nodes':[node('read','read-db',{'databaseId':config['databaseId'],'tableName':config.get('sourceTable','native_source'),'schemaName':'public'},1),node('constant','add-constant',{'fields':[{'name':'label','fieldType':'String','value':"q'Data"}]},2),node('write','insert-db',{'databaseId':config['databaseId'],'tableName':'native_target_'+engine.lower(),'schemaName':'public'},3)],'edges':[{'id':'e1','sourceNodeId':'read','targetNodeId':'constant'},{'id':'e2','sourceNodeId':'constant','targetNodeId':'write'}]}
 if engine=='DATAX':
  graph['nodes']=[graph['nodes'][0],graph['nodes'][-1]];graph['edges']=[{'id':'e1','sourceNodeId':'read','targetNodeId':'write'}]
 flow={'id':task,'revision':0,'flowJson':json.dumps(graph),'draft':False}
 check=call('/check','POST',flow); print({'engine':engine,'task':task,'check':check},flush=True)
 assert check['valid'],check
 saved=call('/flow','PUT',flow);call('/status','PUT',{'id':task,'revision':saved['revision'],'status':1});record=call('/run','POST',query={'id':task});print({'record':record,'definition':saved['dsDefinitionCode']},flush=True)
 result={'taskId':task,'recordId':record,'definitionCode':saved['dsDefinitionCode']};running=False
 for _ in range(150):
  page=call('/records',query={'taskId':task,'pageNo':1,'pageSize':10});r=next((r for r in page['list'] if str(r['id'])==str(record)),None)
  if r and r['status']=='RUNNING':running=True
  if r and r['status']!='RUNNING':result.update(r);break
  time.sleep(2)
 else:raise RuntimeError('bounded native engine timeout')
 assert result['status']=='SUCCESS',result
 sql="SELECT id,name,label FROM public.native_target_"+engine.lower()+" ORDER BY id"
 rows=subprocess.check_output(['docker','exec',config['pgContainer'],'psql','-U','postgres','-d',config['pgDatabase'],'-At','-F','\t','-c',sql],text=True).splitlines()
 expected=["1\t  alice  \tq'Data","2\tBob\tq'Data","3\tq'Data\tq'Data"]
 if engine=='DATAX': expected=["1\t  alice  \t","2\tBob\t","3\tq'Data\t"]
 assert rows==expected,(rows,expected)
 result['observedRunning']=running;result['strictTargetRows']=rows;print({'engine':engine,'status':result['status'],'instance':result['dsInstanceId'],'observedRunning':running,'strictRows':rows},flush=True)
 p=config.get('reportPrefix','/tmp/datahub-integration-')+engine.lower()+'-evidence.json';open(p,'w').write(json.dumps(result,ensure_ascii=False,indent=2));os.chmod(p,0o600)
if __name__=='__main__':
 for engine in config.get('engines',['DATAX','SPARK','FLINK']):run(engine)
