#!/usr/bin/env python3
"""真实 Worker / VM 中断与同键重放验收；仅操作独立 Colima 实例，保留全部实例与数据。"""
import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import selectors
import subprocess
import time
from policy_runtime import Runtime
from job_policy_smoke import Suite


class RowGate:
    def __init__(self,r,policy):self.r=r;self.policy=policy;self.p=None
    def __enter__(self):
        self.p=subprocess.Popen(self.r.psql_command('writer'),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
        self.p.stdin.write(self.r.fixture['roles']['writer']['password']+'\nSET idle_in_transaction_session_timeout=\'180s\';BEGIN;DO $gate$ BEGIN PERFORM id FROM public."'+self.r.fixture['targets'][self.policy]+'" WHERE id=2501 FOR UPDATE; END $gate$;SELECT \'IDEMPOTENT_GATE_READY\';\n');self.p.stdin.flush()
        with selectors.DefaultSelector() as s:
            s.register(self.p.stdout,selectors.EVENT_READ);deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                if s.select(1) and 'IDEMPOTENT_GATE_READY' in self.p.stdout.readline():return self
        self.__exit__();raise RuntimeError('目标行锁未就绪')
    def __exit__(self,*args):
        if self.p and self.p.poll() is None:
            try:self.p.communicate('ROLLBACK;\n\\q\n',timeout=10)
            except (BrokenPipeError,OSError,subprocess.TimeoutExpired):self.p.kill();self.p.communicate(timeout=5)


class RecoverySuite(Suite):
    def inventory(self,record):
        row=self.row(record);prefix='/projects/'+self.r.private['ds']['projectCode']+'/workflow-instances'
        result={'platform':self.brief(row),'children':[]}
        if not row.get('dsInstanceId'):return result
        parent=self.r.ds(prefix+'/'+str(row['dsInstanceId']))
        result['parent']={k:parent.get(k) for k in ['id','state','startTime','endTime','workflowDefinitionCode','projectCode','host']}
        for t in self.r.ds(prefix+'/'+str(parent['id'])+'/tasks')['taskList']:
            childid=self.r.ds(prefix+'/query-sub-by-parent',{'taskId':t['id']}).get('subWorkflowInstanceId')
            if not childid:continue
            child=self.r.ds(prefix+'/'+str(childid))
            workers=self.r.ds(prefix+'/'+str(childid)+'/tasks')['taskList']
            result['children'].append({'parentTaskId':t['id'],'instanceId':childid,'state':child['state'],
                'definitionCode':str(child['workflowDefinitionCode']),'startTime':child['startTime'],'endTime':child['endTime'],
                'workers':[{k:x.get(k) for k in ['id','taskType','state','flag','host','startTime','endTime','processId','retryTimes','logPath']} for x in workers]})
        return result
    def exact(self,policy):
        source='public."'+self.r.fixture['sourceTable']+'"';target='public."'+self.r.fixture['targets'][policy]+'"'
        sql="SELECT (SELECT count(*) FROM "+target+"),(SELECT count(DISTINCT id) FROM "+target+") ,(SELECT count(*) FROM ((SELECT id,name,amount FROM "+source+" EXCEPT ALL SELECT id,name,amount FROM "+target+") UNION ALL (SELECT id,name,amount FROM "+target+" EXCEPT ALL SELECT id,name,amount FROM "+source+")) d);"
        values=[int(x) for x in self.r.sql(sql).split('\t')]
        if values != [3007,3007,0]:raise RuntimeError('目标与源逐行不一致：'+str(values))
        checksums=[]
        for table in [source,target]:
            checksums.append(self.r.sql("SELECT md5(string_agg(row(id,name,amount)::text,E'\\n' ORDER BY id)) FROM "+table+";"))
        assert checksums[0]==checksums[1]
        return {'count':values[0],'distinctKeys':values[1],'symmetricExceptAllCount':values[2],'sourceChecksum':checksums[0],'targetChecksum':checksums[1],'totals':self.r.totals(policy)}
    def terminal(self,policy,record,before=None):
        row=self.wait(lambda:self.row(record),lambda x:x['status'] in ['SUCCESS','FAILURE','STOPPED'],'真实恢复终态',seconds=240)
        inv=self.inventory(record);self.r.save(policy+'-'+str(record)+'-terminal.json',inv)
        logs=[]
        for item in [row,*(row.get('children') or [])]:
            identity={k:item[k] for k in ['id','kind','origin']}
            viewed=self.r.api('/dataforge/ops-instance/log',query=identity)['message']
            downloaded=self.r.api('/dataforge/ops-instance/log-download',query=identity,raw=True).decode()
            assert viewed==downloaded
            assert not any(s and s in viewed for s in self.r.secret_values),'平台日志包含私密值'
            name=policy+'-'+str(record)+'-'+item['kind'].lower()+'.log';(self.output/name).write_text(viewed)
            logs.append({'file':name,'bytes':len(viewed.encode()),'sha256':hashlib.sha256(viewed.encode()).hexdigest(),'viewDownloadEqual':True})
        assert row['status']=='SUCCESS', '真实作业未成功：'+str(record)+'/'+row['status']
        assert inv['parent']['state']=='SUCCESS' and len(inv['children'])==2
        assert all(c['state']=='SUCCESS' for c in inv['children'])
        workers=[x for c in inv['children'] for x in c['workers'] if x['state']=='SUCCESS']
        assert {x['taskType'] for x in workers}=={'DATAX_UPSERT','SQL'}
        integration=next(c for c in inv['children'] if any(x['taskType']=='DATAX_UPSERT' for x in c['workers']))
        development=next(c for c in inv['children'] if any(x['taskType']=='SQL' for x in c['workers']))
        assert development['startTime']>=integration['endTime']
        if before:
            assert before['parent']['id']==inv['parent']['id'],'恢复不得换平台父实例或重新手工触发'
            prior={x['id'] for c in before['children'] for x in c['workers'] if x['taskType']=='DATAX_UPSERT'}
            later={x['id'] for x in workers if x['taskType']=='DATAX_UPSERT'}
            assert prior and later and prior.isdisjoint(later),'必须观察到新 Worker 任务重执行'
        return {'recordId':record,'execution':inv,'data':self.exact(policy),'logs':logs,'sameParentRecovered':bool(before)}

    def run(self,policy,mode,colima):
        case=self.report['cases'].setdefault(policy,{'records':{},'observations':[]})
        if mode=='replay':
            record=self.submit(policy,'replay_'+self.attempt)
            result=self.terminal(policy,record);case.setdefault('replays',[]).append(result);self.save();return result
        with RowGate(self.r,policy) as gate:
            record=self.submit(policy,'fault_'+self.attempt)
            def probe():
                row=self.row(record)
                if row['status'] in ['FAILURE','STOPPED','SUCCESS']:
                    self.r.save(policy+'-unexpected-'+str(record)+'.json',self.inventory(record));raise RuntimeError('故障窗口前已终态 record='+str(record))
                return {'row':self.brief(row),'totals':self.r.totals(policy),'waiters':self.r.writer_waiters(policy)}
            window=self.wait(probe,lambda x:x['row']['submissionStage']=='BOUND' and 1<x['totals']['count']<3007 and x['waiters'],'部分批次已提交、下一批真实阻塞',seconds=90)
            before=self.inventory(record);assert any(x['host'].endswith(':1235') for c in before['children'] for x in c['workers'] if x['taskType']=='DATAX_UPSERT')
            case['beforeFault']={'at':dt.datetime.now().isoformat(),'window':window,'execution':before};self.save()
            print(json.dumps({'phase':'PARTIAL_COMMIT_CONFIRMED','recordId':record,'policy':policy,'committedRows':window['totals']['count']},ensure_ascii=False),flush=True)
            if policy=='worker':
                subprocess.run(['docker','--context','colima-job-policy','kill','--signal','KILL','datahub-job-policy-upsert-worker-1'],check=True,stdout=subprocess.DEVNULL)
                self.wait(lambda:[self.r.writer_pid_exists(x['pid']) for x in window['waiters']],lambda x:not any(x),'旧 writer 连接退出',seconds=15)
                assert self.r.totals(policy)==window['totals'],'故障后未提交批次应回滚'
                subprocess.run(['docker','--context','colima-job-policy','start','datahub-job-policy-upsert-worker-1'],check=True,stdout=subprocess.DEVNULL)
            else:
                assert policy=='host'
                # 第二类故障：所有批次已提交但任务尚未结束，随后丢失宿主与执行回执。
                gate.__exit__()
                self.wait(lambda:self.r.totals(policy),lambda x:x['count']==3007,'全部数据已提交但等待任务回执',seconds=15)
                committed=self.inventory(record)
                assert committed['parent']['state']=='RUNNING_EXECUTION'
                assert any(x['state']=='RUNNING_EXECUTION' and x['taskType']=='DATAX_UPSERT' for c in committed['children'] for x in c['workers']), '必须命中 DataX 尚未确认终态的真实窗口'
                case['committedBeforeHostFault']={'at':dt.datetime.now().isoformat(),'execution':committed,'data':self.exact(policy)};self.save()
                print(json.dumps({'phase':'ALL_COMMITTED_BEFORE_ACK','recordId':record,'rows':3007}),flush=True)
                env=os.environ.copy();env['PATH']=str(colima.parent)+os.pathsep+env['PATH']
                with (self.output/'host-reboot-command.log').open('w') as log:
                    subprocess.run([str(colima),'stop','--profile','job-policy','--force'],env=env,check=True,stdout=log,stderr=subprocess.STDOUT,timeout=60)
                    subprocess.run([str(colima),'start','--profile','job-policy'],env=env,check=True,stdout=log,stderr=subprocess.STDOUT,timeout=180)
            case['faultInjectedAt']=dt.datetime.now().isoformat();self.save()
        print(json.dumps({'phase':'FAULT_INJECTED_WAITING_FOR_RECOVERY','recordId':record,'policy':policy}),flush=True)
        if policy=='host':
            self.wait(lambda:subprocess.run(['docker','--context','colima-job-policy','exec','datahub-job-policy-postgres-1','pg_isready','-p','55427'],capture_output=True).returncode,lambda x:x==0,'虚拟机数据库恢复',seconds=60)
            self.wait(lambda:self.engine_ready(),bool,'DS 引擎恢复',seconds=90)
        result=self.terminal(policy,record,before);case['result']=result;self.save()
        print(json.dumps({'phase':'RECOVERY_PASSED','policy':policy,'recordId':record,'data':result['data']},ensure_ascii=False),flush=True)
        return result
    def engine_ready(self):
        try:self.r.ds('/projects/'+self.r.private['ds']['projectCode']+'/workflow-instances',{'pageNo':1,'pageSize':1});return True
        except Exception:return False

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--case',choices=['worker','host'],required=True);p.add_argument('--mode',choices=['fault','replay'],default='fault');p.add_argument('--attempt',default='initial');p.add_argument('--colima',type=Path,default=Path.home()/'.local/share/datahub-job-policy-runtime/bin/colima');a=p.parse_args()
    r=Runtime(a.config,a.output_dir);suite=RecoverySuite(r,a.output_dir,a.attempt)
    job=r.api('/dataforge/job/get',query={'id':suite.tasks['policies'][a.case]['jobId']});assert job['scheduleStatus']==0,'验收只允许已关闭业务周期'
    # 多个验收进程共享证据账本；在发起任何执行前排他锁定，避免互相覆盖原始请求身份。
    with (a.output_dir / '.idempotent-acceptance.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        suite=RecoverySuite(r,a.output_dir,a.attempt)
        suite.run(a.case,a.mode,a.colima)

if __name__=='__main__':main()
