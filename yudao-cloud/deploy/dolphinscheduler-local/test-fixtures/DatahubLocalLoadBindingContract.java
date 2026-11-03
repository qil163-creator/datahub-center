import java.util.*;
import org.springframework.core.env.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.apache.dolphinscheduler.server.worker.config.WorkerConfig;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
public class DatahubLocalLoadBindingContract {
 public static void main(String[] args) {
  StandardEnvironment env=new StandardEnvironment();
  Map<String,Object> values=new HashMap<>();
  values.put("MASTER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS","0.9");
  values.put("WORKER_SERVERLOADPROTECTION_MAXSYSTEMMEMORYUSAGEPERCENTAGETHRESHOLDS","0.9");
  values.put("WORKER_PHYSICALTASKCONFIG_TASKEXECUTORTHREADSIZE","2");
  env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("contract-systemEnvironment",values));
  Map<String,Object> yaml=new HashMap<>();
  for(String node:new String[]{"worker","master"}) {
    yaml.put(node+".server-load-protection.enabled",true);
    yaml.put(node+".server-load-protection.max-system-cpu-usage-percentage-thresholds",0.8);
    yaml.put(node+".server-load-protection.max-jvm-cpu-usage-percentage-thresholds",0.8);
    yaml.put(node+".server-load-protection.max-system-memory-usage-percentage-thresholds",0.8);
  }
  yaml.put("worker.physical-task-config.task-executor-thread-size",100);
  env.getPropertySources().addLast(new MapPropertySource("yaml-defaults",yaml));
  ConfigurationPropertySources.attach(env);
  WorkerConfig worker=Binder.get(env).bind("worker",Bindable.of(WorkerConfig.class)).get();
  MasterConfig master=Binder.get(env).bind("master",Bindable.of(MasterConfig.class)).get();
  if(worker.getPhysicalTaskConfig().getTaskExecutorThreadSize()!=2)throw new AssertionError("workerThreads");
  if(worker.getServerLoadProtection().getMaxSystemMemoryUsagePercentageThresholds()!=0.9)throw new AssertionError("workerMemory");
  if(master.getServerLoadProtection().getMaxSystemMemoryUsagePercentageThresholds()!=0.9)throw new AssertionError("masterMemory");
  if(!worker.getServerLoadProtection().isEnabled() || !master.getServerLoadProtection().isEnabled())throw new AssertionError("disabled protection");
  if(worker.getServerLoadProtection().getMaxSystemCpuUsagePercentageThresholds()!=0.8 || master.getServerLoadProtection().getMaxSystemCpuUsagePercentageThresholds()!=0.8
    || worker.getServerLoadProtection().getMaxJvmCpuUsagePercentageThresholds()!=0.8 || master.getServerLoadProtection().getMaxJvmCpuUsagePercentageThresholds()!=0.8)throw new AssertionError("changed CPU guard");
  System.out.println("DatahubLocalLoadBindingContract: PASS workerThreads=2 masterMemory=0.9 workerMemory=0.9 CPUguards=0.8 protection=enabled");
 }
}
