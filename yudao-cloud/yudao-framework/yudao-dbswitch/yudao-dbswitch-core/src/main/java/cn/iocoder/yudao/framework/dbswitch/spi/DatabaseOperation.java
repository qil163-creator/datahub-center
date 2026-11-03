package cn.iocoder.yudao.framework.dbswitch.spi;

/** 操作分别认证；产品名称不承诺支持能力。 */
public enum DatabaseOperation {
    CONNECT, METADATA, DDL_CREATE, DDL_ALTER, READ, WRITE_INSERT, WRITE_COPY, WRITE_UPSERT, WRITE_ATOMIC_BATCH, SYNC_FULL, SYNC_DIFF, SYNC_CURSOR
}
