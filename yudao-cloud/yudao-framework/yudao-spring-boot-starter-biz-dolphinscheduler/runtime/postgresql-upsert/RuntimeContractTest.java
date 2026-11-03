import com.alibaba.datax.common.util.Configuration;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertHooks;
import org.apache.dolphinscheduler.plugin.task.datax.DatahubDataxUpsertParameters;
import org.apache.dolphinscheduler.plugin.task.datax.DatahubDataxUpsertTaskChannelFactory;

/** 在实际 Java 8 / DataX 发行依赖上验证探测约定，避免只用平台 Java 25 的编译结果推断兼容性。 */
public final class RuntimeContractTest {
    public static void main(String[] args) throws Exception {
        Class<?> type=Class.forName("cn.iocoder.yudao.runtime.datax.DatahubPostgresqlWriter$UpsertTask");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(Configuration.class);constructor.setAccessible(true);
        Object task=constructor.newInstance(Configuration.from("{\"upsertSchema\":\"public\",\"upsertTable\":\"rows\",\"upsertKeys\":[\"id\"]}"));
        java.lang.reflect.Method method=type.getDeclaredMethod("calcValueHolder",String.class);method.setAccessible(true);
        if ("?".equals(method.invoke(task,""))) throw new AssertionError("必须进入实际类型占位符分支");
        for (String name:new String[]{"int8","bigint","text","numeric","timestamptz"})
            if (!String.valueOf(method.invoke(task,name)).startsWith("?::")) throw new AssertionError(name);
        try {method.invoke(task,"jsonb");throw new AssertionError("未验证类型必须拒绝");}
        catch (java.lang.reflect.InvocationTargetException expected) {if (!(expected.getCause() instanceof IllegalArgumentException)) throw expected;}
        if (!"DATAX_UPSERT".equals(new DatahubDataxUpsertTaskChannelFactory().getName())) throw new AssertionError("任务类型错误");
        if (new DatahubDataxUpsertParameters().checkParameters()) throw new AssertionError("缺失契约必须拒绝");
        if(PostgresUpsertHooks.parse("UPDATE \"test-schema\".audit SET n=n+1 WHERE phase='pre;one'; INSERT INTO \"test-schema\".audit (phase,n) VALUES ('post',2)","test-schema").size()!=2)
            throw new AssertionError("带引号 schema 或字符串分号解析错误");
        for(String sql:new String[]{"COMMIT", "UPDATE public.audit SET n=nextval('seq')", "INSERT INTO public.audit (n) VALUES (audit.conflict())", "UPDATE public.audit SET n=audit.evil '1'", "UPDATE public.audit SET n=(VALUES (1))", "UPDATE public.audit SET n=1 -- hidden\r; SELECT pg_notify('a','b')", "UPDATE public.audit SET n=(TABLE public.audit)",
                "UPDATE public.audit SET n=(SELECT 1)", "DELETE FROM other.audit", "UPDATE public._datahub_upsert_runs SET state='COMPLETED'", "INSERT INTO public.audit DEFAULT VALUES"}) {
            try {PostgresUpsertHooks.parse(sql,"public"); throw new AssertionError("危险钩子未拒绝");}
            catch(IllegalArgumentException expected) { }
        }
        cn.iocoder.yudao.runtime.datax.DatahubPostgresqlWriter.Job malformed=new cn.iocoder.yudao.runtime.datax.DatahubPostgresqlWriter.Job();
        malformed.setPluginJobConf(Configuration.from("{\"writeContract\":\"POSTGRESQL_UPSERT_HOOKS_V2\",\"upsertSchema\":\"public\",\"upsertTable\":\"frozen\",\"connection\":[{\"table\":[\"public.other\"]}]}"));
        try {malformed.init(); throw new AssertionError("V2 必须在连接数据库前核对冻结目标");}
        catch(IllegalArgumentException expected) {if(!expected.getMessage().contains("冻结")) throw expected;}
        System.out.println("RuntimeContractTest: PASS (Java 8 / DataX / DS 3.4.2)");
    }
}
