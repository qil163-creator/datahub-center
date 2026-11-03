#!/usr/bin/env python3
"""仅为本次1004验收准备任务证书、受管配置和源码图；私钥不进入仓库或任务参数。"""
import argparse,hashlib,json,os,shlex,subprocess,tempfile,urllib.request,uuid
from pathlib import Path

def run(*args):return subprocess.check_output(args,stderr=subprocess.DEVNULL,text=True).strip()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--binding',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);options=parser.parse_args()
    base=Path(__file__).resolve().parent;private=base/'runtime';binding=json.loads(options.binding.read_text());container='datahub-ds-local-standalone-1'
    root=Path(tempfile.mkdtemp(prefix='datahub-native-script-',dir='/private/tmp'));root.chmod(0o700);secrets=root/'secrets';secrets.mkdir(mode=0o700);ledger=root/'ledger';ledger.mkdir(mode=0o700)
    ca_config=root/'ca.conf';ca_config.write_text('[req]\ndistinguished_name=dn\nx509_extensions=ca\n[dn]\n[ca]\nbasicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n')
    run('openssl','req','-config',str(ca_config),'-x509','-newkey','rsa:2048','-nodes','-days','3','-subj','/CN=DataHub Script Acceptance CA','-keyout',str(root/'ca-private.pem'),'-out',str(secrets/'script.ca'))
    fingerprints={}
    for name,usage in [('server','serverAuth'),('task1004','clientAuth'),('other-task','clientAuth')]:
        raw=root/(name+'-raw.pem');key=secrets/(('script.server' if name=='server' else 'script1004' if name=='task1004' else 'otherTask')+'.key');cert=secrets/(('script.server' if name=='server' else 'script1004' if name=='task1004' else 'otherTask')+'.cert')
        run('openssl','req','-newkey','rsa:2048','-nodes','-subj','/CN=datahub-'+name,'-keyout',str(raw),'-out',str(root/(name+'.csr')))
        run('openssl','pkcs8','-topk8','-nocrypt','-in',str(raw),'-out',str(key));raw.unlink()
        ext=root/(name+'.ext');ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage='+usage+'\n'+('subjectAltName=DNS:host.docker.internal,DNS:localhost,IP:127.0.0.1\n' if name=='server' else ''))
        run('openssl','x509','-req','-in',str(root/(name+'.csr')),'-CA',str(secrets/'script.ca'),'-CAkey',str(root/'ca-private.pem'),'-CAcreateserial','-days','3','-extfile',str(ext),'-out',str(cert))
        fingerprints[name]=hashlib.sha256(subprocess.check_output(['openssl','x509','-in',str(cert),'-outform','DER'])).hexdigest()
    for path in root.rglob('*'):
        if path.is_file():path.chmod(0o600)
    policy={'enabled':True,'classpathEntries':[x['path'] for x in binding['workerClasspath']],'maxSourceBytes':1048576,'maxBatchBytes':8388608,'maxResultBytes':8388608,'maxBatchRows':1000,'maxResultRows':1000,'maxLogBytes':65536,'startupTimeoutMillis':30000,'batchTimeoutMillis':30000,'heapMegabytes':256,'mode':'DOCKER','docker':{'executable':run('which','docker'),'endpoint':json.loads(run('docker','context','inspect','--format','{{json .Endpoints.docker.Host}}')),'image':binding['workerImage'],'hostPathMappings':[],'requireSwapLimit':False,'commandTimeoutMillis':5000,'maxLifetimeMillis':60000,'recovery':{'directory':str(ledger),'serviceId':'native-script-acceptance','maxRecords':16,'recoverOnStartup':True,'reconciliationTimeoutMillis':10000}}}
    overlay={'enabled':True,'endpoint':'https://host.docker.internal:18447/v1/script','listen-host':'0.0.0.0','listen-port':18447,'server-private-key-ref':'script.server.key','server-certificate-ref':'script.server.cert','authority-ref':'script.ca','worker-policy':policy,'resources':{'script1004':{'maximum-rows':1000,'maximum-flush-millis':300000,'http-resource-refs':['componentTokenHttp'],'identities':[{'task-id':1004,'worker-group':'datahub-development','engine':'SPARK','certificate-sha256':fingerprints['task1004'],'private-key-ref':'script1004.key','certificate-ref':'script1004.cert','authority-ref':'script.ca'}]}},'launchers':{'SPARK':{'resource-name':'datahub/resources/'+Path(binding['sparkLauncher']['path']).name,'worker-relative-path':Path(binding['sparkLauncher']['path']).name}}}
    public=json.loads((private/'components-platform-public.json').read_text());nonce=public['publicNonce']
    token_source='''public class TokenFixture implements tech.qiantong.qdata.common.core.base.EtlBase {
 public String execute(com.alibaba.fastjson2.JSONObject data,Object logParams)throws Exception {
  String token=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("'''+nonce+'''".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  writeLog(logParams,"response="+token); System.err.println("response="+token); return token;
 }
}'''
    java_source='''public class BatchFixture implements tech.qiantong.qdata.common.core.base.EtlBase {
 public String execute(com.alibaba.fastjson2.JSONObject data,Object logParams)throws Exception {
  com.alibaba.fastjson2.JSONArray rows=data.getJSONArray("data");java.math.BigDecimal total=java.math.BigDecimal.ZERO;
  for(Object value:rows){com.alibaba.fastjson2.JSONObject row=(com.alibaba.fastjson2.JSONObject)value;Object amount=row.get("amount");if(amount!=null)total=total.add((java.math.BigDecimal)amount);}
  if(rows.size()!=2||rows.getJSONObject(0).getLongValue("id")!=9007199254740993L||rows.getJSONObject(1).getLongValue("id")!=9007199254740994L)throw new IllegalStateException("完整有序分区须保留输入数组顺序");
  com.alibaba.fastjson2.JSONObject first=rows.getJSONObject(0);rows.remove(0);first.put("label","批次汇总😀");first.put("amount",total);rows.add(first);return "1";
 }
}'''
    flow=json.loads((private/'components-flow-http-ftp.json').read_text());graph=json.loads(flow['flowJson']);api=graph['nodes'][0];api['properties']['nodeJson']['resourceBindingId']='componentTokenHttp';api['properties']['nodeJson'].pop('headerSecretRefs');api['properties']['nodeJson']['headerContextRefs']={'Authorization':{'tokenKey':'login','prefix':'Bearer '}};api['properties']['nodeJson']['apiConfig']['requestUrl']='http://127.0.0.1:19087/token-rows'
    graph['nodes'][1]['properties']['nodeJson']['path']='token'
    common={'resourceBindingId':'script1004','workerState':'BATCH','batchPolicy':'PARTITION','maximumRows':1000}
    def node(id,code,config):return {'id':id,'type':'etl-node','x':100,'y':100,'properties':{'name':id,'toolCode':code,'toolType':2,'nodeJson':config}}
    graph['nodes'].append(node('token','custom-token',{**common,'code':token_source,'tokenKey':'login'}));graph['nodes'].append(node('java','java-script',{**common,'code':java_source}));graph['nodes'].append(node('sort','sort-records',{'sorts':[{'field':'id','order':'ASC'}]}))
    graph['nodes'].append(node('inputsort','sort-records',{'sorts':[{'field':'id','order':'ASC'}]}))
    graph['edges']=[{'id':a+'-'+b,'sourceNodeId':a,'targetNodeId':b} for a,b in [('token','api'),('api','inputsort'),('inputsort','java'),('java','sort'),('sort','write')]]
    flow.update(id=1004,flowJson=json.dumps(graph,ensure_ascii=False));flowfile=private/'components-flow-token-ftp.json';flowfile.write_text(json.dumps(flow,ensure_ascii=False,indent=2)+'\n');flowfile.chmod(0o600)
    result={'fixtureRoot':str(root),'secretDirectory':str(secrets),'secretReferences':[path.name for path in secrets.iterdir() if path.name in ['script.server.key','script.server.cert','script1004.key','script1004.cert','script.ca']],'workerReferences':['script1004.key','script1004.cert','script.ca'],'fingerprints':fingerprints,'scriptConfiguration':overlay,'flowFile':str(flowfile),'sparkLauncher':binding['sparkLauncher']}
    options.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');options.output.chmod(0o600);print(json.dumps({'prepared':True,'fixtureRoot':str(root),'flowFile':str(flowfile),'launcherSha256':binding['sparkLauncher']['sha256']}))
if __name__=='__main__':main()
