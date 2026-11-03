package cn.iocoder.yudao.module.dataforge.service.job;

import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.operations.OpsInstanceMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 新增运维种类的 PostgreSQL 投影、身份隔离、筛选和持久乐观锁验证。 */
class JobOperationsPostgreSqlTest {
    @Test void queriesSeededJobsAndFullSyncWithRealMapper() throws Exception {
        String url=System.getenv("OPS_TEST_JDBC_URL");assumeTrue(url!=null&&!url.isBlank(),"需要隔离 PostgreSQL 测试库");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z0-9_]+_test(?:\\?.*)?"));
        var source=new PooledDataSource("org.postgresql.Driver",url,System.getenv().getOrDefault("OPS_TEST_JDBC_USER","postgres"),"");
        try {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setEnvironment(new Environment("job-ops-postgresql",new JdbcTransactionFactory(),source));config.addMapper(OpsInstanceMapper.class);
            try(var session=new MybatisSqlSessionFactoryBuilder().build(config).openSession(false)) {
                var mapper=session.getMapper(OpsInstanceMapper.class);
                for(String kind:List.of("JOB","FULL_SYNC")) {
                    var req=new PageReq();req.setKind(kind);assertTrue(mapper.countInstances(req,null,null)>=3);
                    var rows=mapper.selectInstances(req,null,null,0);assertFalse(rows.isEmpty());
                    var row=rows.stream().filter(r->"SOURCE".equals(r.getOrigin())).findFirst().orElseThrow();
                    var identity=new IdentityReq();identity.setKind(kind);identity.setOrigin(row.getOrigin());identity.setId(row.getId());
                    assertEquals(row.getInstanceName(),mapper.selectInstance(identity).getInstanceName());
                    req.setName(row.getInstanceName());assertTrue(mapper.countInstances(req,null,null)>=1);
                    assertTrue(mapper.selectInstances(req,null,null,0).stream().anyMatch(found->found.getId().equals(row.getId())&&found.getOrigin().equals(row.getOrigin())));
                    var parameters=new java.util.HashMap<String,Object>();parameters.put("req",req);parameters.put("categoryIds",null);parameters.put("categoryOrigin",null);
                    var boundSql=config.getMappedStatement(OpsInstanceMapper.class.getName()+".countInstances").getBoundSql(parameters).getSql();
                    assertNotNull(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(boundSql));
                    req.setName("' OR 1=1 --");assertEquals(0L,mapper.countInstances(req,null,null));
                    if("JOB".equals(kind)){assertTrue(mapper.selectJobChildren(row.getId(),"SOURCE").startsWith("["));assertNotNull(mapper.selectJobLog(row.getId(),"SOURCE"));}
                    else assertNotNull(mapper.selectFullSyncSnapshotLog(row.getId()));
                }
                session.getConnection().setAutoCommit(false);
                try(var sql=session.getConnection().createStatement()) {
                    // 独立开发页与作业子行使用相同实例名，不能因任务同名而误选另一条历史运行。
                    sql.executeUpdate("DELETE FROM dh_dataforge_dev_record WHERE id IN (8,9) AND task_name='独立开发实例过滤回归'");
                    assertEquals(2,sql.executeUpdate("INSERT INTO dh_dataforge_dev_record(id,task_id,task_name,status) VALUES (8,1,'独立开发实例过滤回归','STOPPED'),(9,1,'独立开发实例过滤回归','SUCCESS')"));
                    session.clearCache();var development=new PageReq();development.setKind("DEVELOPMENT");development.setName("独立开发实例过滤回归-9");
                    assertEquals(1L,mapper.countInstances(development,null,null));
                    var matched=mapper.selectInstances(development,null,null,0);assertEquals(1,matched.size());
                    assertEquals(9L,matched.getFirst().getId());assertEquals("PLATFORM",matched.getFirst().getOrigin());assertEquals(development.getName(),matched.getFirst().getInstanceName());
                    var identity=new IdentityReq();identity.setId(9L);identity.setKind("DEVELOPMENT");identity.setOrigin("PLATFORM");
                    assertEquals(development.getName(),mapper.selectInstance(identity).getInstanceName());
                    for(String state:List.of("WAITING","STOPPED","UNKNOWN")) {
                        assertEquals(1,sql.executeUpdate("INSERT INTO dh_dataforge_integration_record(task_id,status,trigger_type) SELECT min(id),'"+state+"','JOB' FROM dh_dataforge_integration_task"));
                    }
                    var current=sql.executeQuery("SELECT revision FROM dh_dataforge_job ORDER BY id LIMIT 1");assertTrue(current.next());int revision=current.getInt(1);current.close();
                    assertEquals(1,sql.executeUpdate("UPDATE dh_dataforge_job SET revision=revision+1 WHERE id=(SELECT min(id) FROM dh_dataforge_job) AND revision="+revision+" AND operation_state='IDLE'"));
                    assertEquals(0,sql.executeUpdate("UPDATE dh_dataforge_job SET revision=revision+1 WHERE id=(SELECT min(id) FROM dh_dataforge_job) AND revision="+revision+" AND operation_state='IDLE'"));
                } finally {session.rollback(true);}
            }
        } finally {source.forceCloseAll();}
    }
}
