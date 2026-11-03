package org.apache.dolphinscheduler.plugin.task.datax;

import java.util.List;

/** 独立任务类型的版本化契约；缺少部署时禁止静默退回普通 INSERT。 */
public final class DatahubDataxUpsertParameters extends DataxParameters {
    private String writeContract;
    private String upsertSchema;
    private String upsertTable;
    private List<String> upsertKeys;
    public String getWriteContract() { return writeContract; }
    public void setWriteContract(String value) { writeContract = value; }
    public String getUpsertSchema() { return upsertSchema; }
    public void setUpsertSchema(String value) { upsertSchema = value; }
    public String getUpsertTable() { return upsertTable; }
    public void setUpsertTable(String value) { upsertTable = value; }
    public List<String> getUpsertKeys() { return upsertKeys; }
    public void setUpsertKeys(List<String> value) { upsertKeys = value; }
    @Override public boolean checkParameters() {
        return super.checkParameters() && getCustomConfig() == 0 && "POSTGRESQL".equals(getDtType())
                && ("POSTGRESQL_UPSERT_V1".equals(writeContract) || "POSTGRESQL_UPSERT_HOOKS_V2".equals(writeContract)) && upsertSchema != null && upsertTable != null
                && upsertKeys != null && !upsertKeys.isEmpty()
                && ("POSTGRESQL_UPSERT_HOOKS_V2".equals(writeContract)
                    || ((getPreStatements() == null || getPreStatements().isEmpty())
                        && (getPostStatements() == null || getPostStatements().isEmpty())));
    }
}
