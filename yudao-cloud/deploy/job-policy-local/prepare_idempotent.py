#!/usr/bin/env python3
"""在现有隔离库新增独立幂等夹具，不更改旧追加验收数据、不启用业务周期。"""
import argparse
import copy
import datetime as dt
import json
from pathlib import Path
import sys
from policy_runtime import Runtime
from prepare_fixture import secure_write
import prepare_tasks

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-config',type=Path,required=True)
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--output-dir',type=Path,required=True)
    args=parser.parse_args()
    if not args.config.exists():
        base=json.loads(args.base_config.read_text());cfg=copy.deepcopy(base);suffix=dt.datetime.now().strftime('%Y%m%d_%H%M%S')
        f=cfg['fixture'];f['suffix']=suffix;f['sourceTable']='codex_job_e2e_idem_src_'+suffix
        f['targets']={kind:'codex_job_e2e_idem_'+kind+'_'+suffix for kind in ['worker','host']}
        f['upsertKeys']=['id'];cfg['ds']['workerGroup']='idempotent';args.config.parent.mkdir(parents=True,exist_ok=True);secure_write(args.config,cfg)
    r=Runtime(args.config,args.output_dir);f=r.fixture
    # 单独表名、限定本地测试库；读取/写入角色沿用已受管 DS 绑定，只增加新表权限。
    source=f['sourceTable'];tables=list(f['targets'].values())
    sql='BEGIN;CREATE TABLE IF NOT EXISTS public."'+source+'"(id bigint PRIMARY KEY,name text,amount bigint);'
    sql+='INSERT INTO public."'+source+'" SELECT n,CASE WHEN n%13=0 THEN NULL WHEN n%7=0 THEN $q$  q\'Data 中文  $q$ ELSE \'name-\'||n END,n*7 FROM generate_series(1,3007)n ON CONFLICT(id) DO NOTHING;'
    for table in tables:
        sql+='CREATE TABLE IF NOT EXISTS public."'+table+'"(id bigint PRIMARY KEY,name text,amount bigint);'
        sql+='INSERT INTO public."'+table+'" VALUES(2501,\'preexisting sentinel\',-1) ON CONFLICT(id) DO NOTHING;'
        sql+='GRANT SELECT,INSERT,UPDATE ON public."'+table+'" TO "'+f['roles']['writer']['name']+'";'
        sql+='GRANT SELECT ON public."'+table+'" TO "'+f['roles']['query']['name']+'";'
    sql+='GRANT SELECT ON public."'+source+'" TO "'+f['roles']['reader']['name']+'","'+f['roles']['query']['name']+'";COMMIT;'
    r.admin_query(sql)
    r.save('fixture.json',{'suffix':f['suffix'],'database':f['database'],'sourceTable':source,'targets':f['targets'],'rowCount':3007,'uniqueKeys':['id'],'oldFixturesPreserved':True})
    prepare_tasks.STRATEGIES={'worker':'SERIAL_WAIT','host':'SERIAL_WAIT'}
    old=sys.argv;sys.argv=[old[0],'--config',str(args.config),'--output-dir',str(args.output_dir)]
    try:prepare_tasks.main()
    finally:sys.argv=old

if __name__=='__main__':main()
