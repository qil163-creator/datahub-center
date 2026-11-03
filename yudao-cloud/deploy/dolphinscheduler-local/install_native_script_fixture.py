#!/usr/bin/env python3
"""将1004固定launcher发布到DS Storage，按任务安装客户端引用并合并独立平台配置。"""
import argparse,hashlib,json,re,subprocess,urllib.request,uuid
from pathlib import Path
from smoke import Client

def run(*arguments):return subprocess.check_output(arguments,text=True).strip()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--fixture',type=Path,required=True);parser.add_argument('--binding',type=Path,required=True);parser.add_argument('--functions',type=Path,required=True);options=parser.parse_args()
    base=Path(__file__).resolve().parent;runtime=base/'runtime';private=json.loads(options.fixture.read_text());binding=json.loads(options.binding.read_text());functions=json.loads(options.functions.read_text());ds=json.loads((runtime/'components-ds.json').read_text());client=Client(ds['server']);client.token=ds['token'];container=ds.get('container','datahub-ds-local-standalone-1')
    jar=Path(binding['sparkLauncher']['path']);entries=client.request('GET','/resources/list',{'type':'FILE'})
    def find(items):
        for item in items:
            if item.get('name')==jar.name:return item
            value=find(item.get('children',[]))
            if value:return value
    resource=find(entries)
    if not resource:
        boundary='DataHub'+uuid.uuid4().hex;parts=[]
        for key,value in [('type','FILE'),('name',jar.name),('currentDir','/')]:parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+key+'"\r\n\r\n'+value+'\r\n').encode())
        parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+jar.name+'"\r\nContent-Type: application/java-archive\r\n\r\n').encode()+jar.read_bytes()+b'\r\n');parts.append(('--'+boundary+'--\r\n').encode())
        with urllib.request.urlopen(urllib.request.Request(ds['server']+'/resources',data=b''.join(parts),headers={'token':ds['token'],'Content-Type':'multipart/form-data; boundary='+boundary},method='POST'),timeout=30) as response:result=json.load(response)
        if result.get('code')!=0:raise RuntimeError('受管launcher上传失败')
        resource=find(client.request('GET','/resources/list',{'type':'FILE'}))
    if not resource or not resource.get('fullName'):raise RuntimeError('官方Storage未返回唯一资源位置')
    environment=json.loads((runtime/'components-script-original-environment.json').read_text());match=re.search(r'^export DATAHUB_ETL_SECRET_DIR=([^\s]+)$',environment['config'],re.M)
    if not match:raise RuntimeError('原验收环境未声明固定秘密目录')
    targetroot=match.group(1)
    for reference in private['workerReferences']:
        source=Path(private['secretDirectory'])/reference;target=targetroot+'/'+reference;digest=hashlib.sha256(source.read_bytes()).hexdigest()
        existing=subprocess.run(['docker','exec','-u','0',container,'sha256sum',target],text=True,capture_output=True)
        if existing.returncode==0:
            if existing.stdout.split()[0]!=digest:raise RuntimeError('现有任务证书引用不同，拒绝隐式轮换')
        else:
            run('docker','cp',str(source),container+':'+target);run('docker','exec','-u','0',container,'chown','10001:10001',target);run('docker','exec','-u','0',container,'chmod','0400',target)
    original_refs=re.search(r'^export DATAHUB_ETL_SECRET_REFS=([^\s]+)$',environment['config'],re.M).group(1).split(',');refs=','.join(dict.fromkeys(original_refs+private['workerReferences']))
    config=re.sub(r'^export DATAHUB_ETL_SECRET_REFS=[^\s]+$', 'export DATAHUB_ETL_SECRET_REFS='+refs,environment['config'],flags=re.M)
    name='datahub-components-20261102-script1004-'+binding['sparkLauncher']['sha256'][:8];environments=client.request('GET','/environment/query-environment-list');existing=next((item for item in environments if item['name']==name),None)
    if existing is None:client.request('POST','/environment/create',{'name':name,'config':config,'description':'1004隔离脚本原生验收，无明文证书材料','workerGroups':json.dumps(['datahub-development'])});existing=next(item for item in client.request('GET','/environment/query-environment-list') if item['name']==name)
    elif existing['config']!=config:raise RuntimeError('既有隔离验收环境内容变化，拒绝覆盖')
    apppath=runtime/'components-application-private.yaml';app=json.loads(apppath.read_text());integration=app['dataforge']['integration'];integration['engine-environment-codes']['SPARK']=str(existing['code']);script=private['scriptConfiguration'];script['worker-policy']['classpathEntries']=[item['path'] for item in binding['workerClasspath']];script['launchers']['SPARK']={'resource-name':resource['fullName'],'worker-relative-path':jar.name};integration['native-scripts']=script
    for reference in private['workerReferences']:integration.get('managed-secret-bindings',{}).pop(reference,None)
    for reference in private['workerReferences']:integration.setdefault('managed-secret-bindings',{})['['+reference+']']={'allowed-task-ids':[1004],'worker-groups':['datahub-development'],'engines':['SPARK']}
    integration['datax']['bindings']['componentTokenHttp']={'allowed-task-ids':[1004],'connector-codes':['rest-api'],'installed-plugins':['spark-http'],'addresses':['http://127.0.0.1:19087/token-rows'],'authentication':'REQUIRED'}
    integration['datax']['bindings']['componentLoginHttp']={'allowed-task-ids':[1004],'connector-codes':['rest-api'],'installed-plugins':['spark-http'],'addresses':['http://127.0.0.1:19087/login'],'authentication':'NONE'}
    preserved=[line for line in integration['spark-initialization-sql'].splitlines() if not re.match(r'CREATE TEMPORARY FUNCTION (DH_REGEX_REPLACE|DH_SPLIT_TOKEN|DH_CRYPTO_ENCRYPT|DH_CRYPTO_DECRYPT|DH_READ_API|DH_READ_SOAP) ',line)]
    integration['spark-initialization-sql']=functions['sparkInitializationSql']+'\n'+'\n'.join(preserved);integration['managed-functions']=list(dict.fromkeys(integration['managed-functions']+functions['managedFunctions']))
    apppath.write_text(json.dumps(app,ensure_ascii=False,indent=2)+'\n');apppath.chmod(0o600)
    platformpath=runtime/'components-platform-private.json';platform=json.loads(platformpath.read_text());expected=[['id','标签','amount'],['9007199254740993','批次汇总😀','123456.120000000000000001'],['9007199254740994','','\\N']];platform['cases']['token-ftp']={'flowFile':private['flowFile'],'ftpDirectory':'token','expectedRows':expected};platformpath.write_text(json.dumps(platform,ensure_ascii=False,indent=2)+'\n');platformpath.chmod(0o600)
    private['sparkLauncher']=binding['sparkLauncher'];private['environmentCode']=str(existing['code']);private['storageResource']=resource['fullName'];options.fixture.write_text(json.dumps(private,ensure_ascii=False,indent=2)+'\n');options.fixture.chmod(0o600)
    print(json.dumps({'installed':True,'taskId':1004,'environmentCode':str(existing['code']),'resourceName':resource['fullName'],'launcherSha256':binding['sparkLauncher']['sha256'],'functionsSha256':functions['jarSha256']}))
if __name__=='__main__':main()
