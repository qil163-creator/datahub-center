package cn.iocoder.yudao.framework.dbswitch.dbwriter;

/** 观测到的提交机制；这些确认均不代表跨表原子性。 */
public enum CommitAcknowledgement {
    NONE,
    JDBC_COMMIT_RETURNED,
    POSTGRESQL_XID8_VERIFIED,
    /** 完整的 OLAP Stream Load 回执确认所有请求行均可见。 */
    OLAP_STREAM_LOAD_VISIBLE
}
