import java.sql.*;
public class DatahubRegistryFileContract {
 public static void main(String[] args) throws Exception {
  try(Connection c=DriverManager.getConnection(args[1],"sa","");Statement s=c.createStatement()) {
   if(args[0].equals("seed")) {
    s.execute("SET DB_CLOSE_DELAY -1");
    s.execute("CREATE TABLE t_ds_jdbc_registry_data(data_type varchar(30))");
    s.execute("INSERT INTO t_ds_jdbc_registry_data VALUES ('EPHEMERAL'),('PERSISTENT')");
    s.execute("CREATE TABLE t_ds_jdbc_registry_lock(id int)"); s.execute("INSERT INTO t_ds_jdbc_registry_lock VALUES (1)");
    s.execute("CREATE TABLE t_ds_jdbc_registry_client_heartbeat(id int)"); s.execute("INSERT INTO t_ds_jdbc_registry_client_heartbeat VALUES (1)");
    s.execute("CREATE TABLE t_ds_jdbc_registry_data_change_event(jdbc_registry_data varchar(200))");
    s.execute("INSERT INTO t_ds_jdbc_registry_data_change_event VALUES ('{\"dataType\":\"EPHEMERAL\"}'),('{\"dataType\":\"PERSISTENT\"}')");
    s.execute("CREATE TABLE t_ds_workflow_definition(id int)"); s.execute("INSERT INTO t_ds_workflow_definition VALUES (7)");
    s.execute("SHUTDOWN"); return;
   }
   int ephemeral;
   try(ResultSet r=s.executeQuery("SELECT count(*) FROM t_ds_jdbc_registry_data WHERE data_type='EPHEMERAL'")){r.next();ephemeral=r.getInt(1);}
   if(args[0].equals("expect-stale")) {
    if(ephemeral!=1) throw new AssertionError("No stale row: "+ephemeral);
    System.out.println("REPRODUCED: cleanup COMMIT without SHUTDOWN lost across JVM exit");
   } else {
    if(ephemeral!=0)throw new AssertionError("ephemeral survived: "+ephemeral);
    for(String table:new String[]{"t_ds_jdbc_registry_data","t_ds_workflow_definition"})
     try(ResultSet r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();if(r.getInt(1)!=1)throw new AssertionError(table);}
    for(String table:new String[]{"t_ds_jdbc_registry_lock","t_ds_jdbc_registry_client_heartbeat","t_ds_jdbc_registry_data_change_event"})
     try(ResultSet r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();if(r.getInt(1)!=0)throw new AssertionError(table);}
    System.out.println("PASS: file H2 separate JVMs cleanup durable; persistent registry and workflow retained");
   }
   s.execute("SHUTDOWN");
  }
 }
}