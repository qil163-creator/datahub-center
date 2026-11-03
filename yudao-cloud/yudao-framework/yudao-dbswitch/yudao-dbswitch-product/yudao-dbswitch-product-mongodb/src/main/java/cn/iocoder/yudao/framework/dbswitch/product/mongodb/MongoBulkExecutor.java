package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** 单个有界无序原生批次。精确回执绝不授权自动重放。 */
public final class MongoBulkExecutor {

    public enum Operation {INSERT, UPDATE, DELETE, UPSERT}

    private static final Map<Integer, String> TYPED = Map.ofEntries(
            Map.entry(0x01, "double"), Map.entry(0x02, "string"), Map.entry(0x05, "binData"),
            Map.entry(0x07, "objectId"),
            Map.entry(0x08, "bool"), Map.entry(0x09, "date"), Map.entry(0x10, "int"), Map.entry(0x11, "timestamp"),
            Map.entry(0x12, "long"), Map.entry(0x13, "decimal"));

    /**
     * 初始化 {@code MongoBulkExecutor}。
     */
    private MongoBulkExecutor() {
    }

    /**
     * 将当前数据编码为 MongoDB 批量原生操作并逐项检查写入响应。
     */
    public static long execute(DataSource source, MongoPreparedTarget target, List<Object[]> input,
            Operation operation, BatchLimits limits, DbSwitchOptions options) {
        if (target == null) {
            throw new IllegalStateException("MongoDB writer has not been prepared");
        }
        var rows = BatchValueConverter.convert(input, target.fields().size(), limits);
        if (rows.isEmpty()) {
            return 0;
        }
        boolean attempted = false;
        long acknowledged = -1, rejected = -1;
        try (Connection connection = source.getConnection()) {
            target.verify(connection);
            var session = MongoNativeSession.open(connection, options.maxCacheBytes(), options.queryTimeoutSeconds());
            var operations = new ArrayList<byte[]>();
            var keys = new HashSet<String>();
            long documentBytes = 0;
            // 首个命令执行前，构造并校验完整原生批次。
            for (Object[] row : rows) {
                byte[] document;
                if (target.frame()) {
                    document = MongoMigrationFrame.document(session, row);
                } else {
                    var values = new LinkedHashMap<String, byte[]>();
                    for (int i = 0; i < row.length; i++) {
                        String name = target.fields().get(i);
                        if (row[i] != MissingCellValue.INSTANCE) {
                            values.put(name, MongoCellCodec.encode(session, row[i], name.equals("_id")));
                        }
                    }
                    document = session.document(values);
                }
                var cells = session.cells(document);
                byte[] id = cells.get("_id");
                if (operation != Operation.INSERT && id == null) {
                    throw new IllegalArgumentException("MongoDB update/delete require native identity");
                }
                if (id != null) {
                    var identity = MongoCellCodec.detach(session, id, true);
                    if (operation != Operation.INSERT && !identity.hasMatchingKey()) {
                        throw new IllegalArgumentException(
                                "MongoDB synchronization identity has no proven comparison contract");
                    }
                    if (identity.hasMatchingKey() && !keys.add(
                            Base64.getEncoder().encodeToString(identity.matchingKey()))) {
                        throw new IllegalArgumentException(
                                "MongoDB native batch contains duplicate matching identities");
                    }
                }
                byte[] item;
                if (operation == Operation.INSERT) {
                    item = MongoMigrationFrame.wrapDocument(document);
                } else {
                    var command = new LinkedHashMap<String, byte[]>();
                    // UPSERT 按 BSON 类型和值绑定标识：数值相等但类型不同的
                    // 标识，要么精确匹配，要么在折叠值的 _id 索引上明确失败，绝不
                    // 被此 upsert 静默替换。
                    command.put("q", operation == Operation.UPSERT ? typedIdentity(session,
                            id) : MongoMigrationFrame.wrapDocument(session.document(Map.of("_id", id))));
                    if (operation == Operation.DELETE) {
                        command.put("limit", MongoCellCodec.encode(session, 1, false));
                    } else {
                        if (target.frame()) {
                            command.put("u", MongoMigrationFrame.wrapDocument(document));
                        } else {
                            // UPSERT 在 $set 中保留 _id：类型化运算符查询无法为插入
                            // 文档推导 _id，对匹配文档执行相同 _id 的 $set 是合法空操作。
                            var sets = new LinkedHashMap<>(cells);
                            if (operation != Operation.UPSERT) {
                                sets.remove("_id");
                            }
                            var update = new LinkedHashMap<String, byte[]>();
                            if (!sets.isEmpty()) {
                                update.put("$set", MongoMigrationFrame.wrapDocument(session.document(sets)));
                            }
                            var unsets = new LinkedHashMap<String, byte[]>();
                            for (int i = 0; i < row.length; i++) {
                                if (!target.fields().get(i).equals("_id") && row[i] == MissingCellValue.INSTANCE) {
                                    unsets.put(target.fields().get(i), MongoCellCodec.encode(session, "", false));
                                }
                            }
                            if (!unsets.isEmpty()) {
                                update.put("$unset", MongoMigrationFrame.wrapDocument(session.document(unsets)));
                            }
                            if (update.isEmpty()) {
                                throw new IllegalArgumentException("MongoDB update requires an explicit non-key field");
                            }
                            command.put("u", MongoMigrationFrame.wrapDocument(session.document(update)));
                        }
                        command.put("multi", MongoCellCodec.encode(session, false, false));
                        command.put("upsert", MongoCellCodec.encode(session, operation == Operation.UPSERT, false));
                    }
                    item = MongoMigrationFrame.wrapDocument(session.document(command));
                }
                documentBytes = Math.addExact(documentBytes, item.length);
                if (documentBytes > Math.min(limits.maxBatchBytes(), 16L * 1024 * 1024 - 1024)) {
                    throw new IllegalArgumentException(
                            "Encoded MongoDB native batch exceeds its byte limit before write");
                }
                operations.add(item);
            }
            var command = new LinkedHashMap<String, byte[]>();
            command.put(switch (operation) {
                        case INSERT -> "insert";
                        case UPDATE, UPSERT -> "update";
                        case DELETE -> "delete";
                    },
                    MongoCellCodec.encode(session, target.table(), false));
            command.put(switch (operation) {
                case INSERT -> "documents";
                case UPDATE, UPSERT -> "updates";
                case DELETE -> "deletes";
            }, session.array(operations));
            command.put("ordered", MongoCellCodec.encode(session, false, false));
            command.put("writeConcern", MongoMigrationFrame.wrapDocument(session.encode(
                    "{\"w\":\"majority\",\"j\":true,\"wtimeout\":" + 1000L * options.queryTimeoutSeconds() + "}")));
            byte[] encoded = session.document(command);
            if (encoded.length > Math.min(limits.maxBatchBytes(), 16L * 1024 * 1024)) {
                throw new IllegalArgumentException("Encoded MongoDB native batch exceeds its byte limit before write");
            }
            attempted = true;
            var reply = MongoReplies.success(session, session.command(target.schema(), encoded));
            if (reply.has("writeConcernError")) {
                throw new UnconfirmedWriteException();
            }
            long n = MongoReplies.integer(reply.path("n"));
            var failures = reply.path("writeErrors");
            if (!failures.isMissingNode() && !failures.isArray()) {
                throw new UnconfirmedWriteException();
            }
            Set<Long> failedOrdinals = new HashSet<>();
            for (var failure : failures) {
                long index = MongoReplies.integer(failure.path("index"));
                if (index < 0 || index >= rows.size() || !failedOrdinals.add(index)
                        || MongoReplies.integer(failure.path("code")) <= 0) {
                    throw new UnconfirmedWriteException();
                }
            }
            if (n < 0 || n > rows.size() - failedOrdinals.size()
                    || (operation == Operation.INSERT || operation == Operation.UPSERT) && n + failedOrdinals.size() != rows.size()) {
                throw new UnconfirmedWriteException();
            }
            acknowledged = n;
            rejected = rows.size() - n;
        } catch (SQLException | RuntimeException failure) {
            if (acknowledged >= 0) {
                throw new PartialBatchWriteException(acknowledged, rejected, 0, false);
            }
            if (attempted) {
                throw new UnconfirmedWriteException();
            }
            if (failure instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalStateException("MongoDB native batch was not submitted");
        }
        if (rejected > 0) {
            throw new PartialBatchWriteException(acknowledged, rejected, 0, true);
        }
        return acknowledged;
    }

    /** 按 BSON 类型和值绑定标识的 upsert 查询：{@code {_id: {$type: ..., $eq: <raw value>}}}。 */
    private static byte[] typedIdentity(MongoNativeSession session, byte[] id) throws SQLException {
        String name = TYPED.get(Byte.toUnsignedInt(id[4]));
        if (name == null) {
            throw new IllegalArgumentException("MongoDB upsert identity type has no proven typed match");
        }
        var clause = new LinkedHashMap<String, byte[]>();
        clause.put("$type", MongoCellCodec.encode(session, name, false));
        clause.put("$eq", id);
        return MongoMigrationFrame.wrapDocument(
                session.document(Map.of("_id", MongoMigrationFrame.wrapDocument(session.document(clause)))));
    }
}
