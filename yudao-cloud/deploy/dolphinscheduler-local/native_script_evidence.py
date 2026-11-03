#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""采集真实平台任务、DS原生参数与日志，扫描运行令牌和私钥材料后写公开验收证据。"""
import argparse,base64,hashlib,json,re,subprocess,urllib.request,zipfile,io,shlex
from pathlib import Path
from smoke import Client

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();base=Path(__file__).resolve().parent;r=base/'runtime';platform=json.loads((r/'components-platform-private.json').read_text());private=json.loads((r/'components-script-private.json').read_text());ds=json.loads((r/'components-ds.json').read_text());client=Client(ds['server']);client.token=ds['token'];nonce=json.loads((r/'components-platform-public.json').read_text())['publicNonce'];token=hashlib.sha256(nonce.encode()).hexdigest()
    sentinels={'runtimeToken':token.encode(),'platformApiToken':platform['apiToken'].encode(),'dsApiToken':ds['token'].encode()}
    for path in Path(private['secretDirectory']).glob('*.key'):
        raw=path.read_bytes().strip();sentinels[path.name+'Pem']=raw;sentinels[path.name+'Escaped']=raw.replace(b'\n',b'\\n');encoded=b''.join(line for line in raw.splitlines() if not line.startswith(b'-----'));sentinels[path.name+'Base64']=encoded;sentinels[path.name+'Der']=base64.b64decode(encoded)
    artifact=base/'verification/native-script-artifacts';artifact.mkdir(exist_ok=True);captured=[]
    def scan(name,data):
        hits=[]
        for label,pattern in sentinels.items():
            variants=[pattern]
            try:text=pattern.decode('utf-8');variants += [text.encode('utf-16le'),text.encode('utf-16be')]
            except UnicodeDecodeError:pass
            if any(value and value in data for value in variants):hits.append(label)
        if zipfile.is_zipfile(io.BytesIO(data)):
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                if sum(item.file_size for item in archive.infolist())>67108864:raise RuntimeError('验收归档展开预算超限')
                for item in archive.infolist():
                    expanded=archive.read(item)
                    for label,pattern in sentinels.items():
                        variants=[pattern]
                        try:text=pattern.decode('utf-8');variants += [text.encode('utf-16le'),text.encode('utf-16be')]
                        except UnicodeDecodeError:pass
                        if any(value and value in expanded for value in variants):hits.append(label+'@'+item.filename)
        if hits:raise RuntimeError('验收材料包含运行秘密，已停止公开写出：'+name+' '+','.join(hits))
        path=artifact/name;path.write_bytes(data);captured.append({'file':str(path.relative_to(base)),'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'secretHits':0})
    checks={}
    for case,file in [('nonce','components-20261102-platform-script-token.json'),('hutool','components-20261102-platform-script-hutool-token.json'),('finalVersion','components-20261102-platform-script-final-version.json')]:
        report=json.loads((base/'verification'/file).read_text());entry=next(iter(report['checks'].values()));assert report['status']=='PASS' and entry['passed'];checks[case]=entry
        definition=client.request('GET','/projects/'+ds['projectCode']+'/workflow-definition/'+str(entry['definitionCode']));scan(case+'-ds-definition.json',(json.dumps(definition,ensure_ascii=False,indent=2)+'\n').encode())
        entry['actualInitializationJars']=[]
        for task in definition.get('taskDefinitionList',[]):
            parameters=task.get('taskParams',{})
            if isinstance(parameters,str):parameters=json.loads(parameters)
            encoded=parameters.get('mainArgs','')
            if encoded:
                raw=base64.urlsafe_b64decode(''.join(shlex.split(encoded)))
                for jar in dict.fromkeys(re.findall(rb"file://(/[^'\s]+\.jar)",raw)):
                    path=jar.decode();digest=subprocess.check_output(['docker','exec','-u','10001',platform['container'],'sha256sum',path],text=True).split()[0]
                    entry['actualInitializationJars'].append({'path':path,'sha256':digest,'readableByExecutionUid10001':True})
        instance=str(entry['dsInstanceId']);finder='import pathlib,json;print(json.dumps([str(p) for p in pathlib.Path("/opt/dolphinscheduler/logs").rglob("*.log") if p.parent.name=="'+instance+'" and p.name=="'+instance+'.log"]))';paths=json.loads(subprocess.check_output(['docker','exec',platform['container'],'python3','-c',finder],text=True));assert len(paths)==1;scan(case+'-native-task.log',subprocess.check_output(['docker','exec',platform['container'],'cat',paths[0]]))
    for name,source in [('broker-negative.json','/tmp/datahub-script-broker-probe-final.log'),('sandbox-isolation.json','/tmp/datahub-script-sandbox-probe-final.log')]:
        data=Path(source).read_bytes();value=json.loads(data);assert value['status']=='PASS';checks[name]=value;scan(name,data)
    scan('platform-runtime.log',(r/'components-server.log').read_bytes())
    for name in ['components-flow-token-ftp.json','components-flow-hutool-token-ftp.json','components-flow-hutool-token-final-ftp.json']:scan(name,(r/name).read_bytes())
    binding=json.loads(Path('/tmp/datahub-native-script-binding.json').read_text());functions=json.loads(Path('/tmp/datahub-script-etl-functions.json').read_text())
    for name,source in [('launcher.jar',Path(binding['sparkLauncher']['path'])),('worker.jar',Path(binding['workerClasspath'][0]['path']))]:scan(name,source.read_bytes())
    report={'status':'PASS','scope':'正式DataForge task1004 -> 独立DS3.4.2 -> 真Spark3.5.6 -> mTLS broker -> Docker隔离qData脚本 -> token私有上下文 -> 真API认证 -> 有序整批增删/跨行Decimal -> FTP精确CSV','checks':checks,'taskId':1004,'finalPlatformSnapshot':json.loads((r/'components-server.json').read_text()),'dsServer':ds['server'],'projectCode':ds['projectCode'],'launcherSha256':binding['sparkLauncher']['sha256'],'workerSha256':binding['workerClasspath'][0]['sha256'],'functionsSha256':functions['jarSha256'],'artifacts':captured,'secretScanEncodings':['UTF-8','UTF-16LE','UTF-16BE','PKCS8-DER'],'actualSuccessfulInstances':[checks[name]['dsInstanceId'] for name in ['nonce','hutool','finalVersion']],'limitations':['BATCH每批新worker，Spark完整实际SQL分区，超限整批失败，不隐式切批','broker重启epoch失效，必须正常保存发布；未知副作用不重试','Flink另见独立native-script-flink证据；Flink custom-token不开放','公开nonce登录夹具证明传输和流程，不替代生产认证服务验收']}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':'PASS','artifacts':len(captured),'secretHits':0,'taskId':1004}))
if __name__=='__main__':main()
