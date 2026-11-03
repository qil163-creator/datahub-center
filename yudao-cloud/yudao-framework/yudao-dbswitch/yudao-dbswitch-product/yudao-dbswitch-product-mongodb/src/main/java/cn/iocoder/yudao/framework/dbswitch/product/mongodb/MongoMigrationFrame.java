package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 双列无损原生协议，与 MongoDB 采样得到的业务字段元数据分开。 */
public final class MongoMigrationFrame {

    public static final String DOCUMENT = "__dbswitch_document";

    public static final List<String> FIELDS = List.of("_id", DOCUMENT);

    /**
     * 初始化 {@code MongoMigrationFrame}。
     */
    private MongoMigrationFrame() {
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static List<ColumnDescription> columns() {
        return FIELDS.stream().map(name -> {
            var column = new ColumnDescription();
            column.setFieldName(name);
            column.setLabelName(name);
            column.setFieldType(Types.OTHER);
            column.setFieldTypeName(name.equals("_id") ? "BSON_ID" : "BSON_DOCUMENT");
            column.setFiledTypeClassName(NativeCellValue.class.getName());
            column.setDbType(ProductTypeEnum.MONGODB);
            column.setNullable(false);
            column.setPk(name.equals("_id"));
            column.setRemarks("Native BSON migration frame; not sampled business fields");
            return column;
        }).toList();
    }

    /**
     * 校验并取得字段集合。
     */
    public static void requireFields(List<String> fields) {
        if (!FIELDS.equals(fields)) {
            throw new IllegalArgumentException("MongoDB native migration frame fields cannot be renamed or removed");
        }
    }

    /**
     * 根据当前输入构建并返回 {@code Object}。
     */
    public static Object[] detach(MongoNativeSession session, byte[] document) throws SQLException {
        Map<String, byte[]> fields = session.cells(document);
        if (!fields.containsKey("_id")) {
            throw new SQLException("MongoDB document has no native identity");
        }
        // 将完整 BSON 文档包装为单个原生值，包括原始 _id。
        byte[] frame = wrapDocument(document);
        return new Object[]{MongoCellCodec.detach(session, fields.get("_id"), true), MongoCellCodec.detach(session,
                frame, false)};
    }

    /**
     * 返回当前文档。
     */
    public static byte[] document(MongoNativeSession session, Object[] row) throws SQLException {
        if (row == null || row.length != 2 || !(row[0] instanceof NativeCellValue) || !(row[1] instanceof NativeCellValue)) {
            throw new IllegalArgumentException("MongoDB native frame requires owned BSON identity and document values");
        }
        byte[] identity = MongoCellCodec.encode(session, row[0], true);
        byte[] envelope = MongoCellCodec.encode(session, row[1], false);
        if (envelope[4] != 0x03) {
            throw new IllegalArgumentException("MongoDB native frame body must be a BSON document");
        }
        byte[] document = Arrays.copyOfRange(envelope, 7, envelope.length - 1);
        Map<String, byte[]> cells = session.cells(document);
        if (!cells.containsKey("_id") || !Arrays.equals(identity, cells.get("_id"))) {
            throw new IllegalArgumentException("MongoDB frame identity disagrees with the complete document");
        }
        return document;
    }

    /**
     * 包装文档。
     */
    public static byte[] wrapDocument(byte[] document) {
        int length = Math.addExact(document.length, 8);
        var envelope = java.nio.ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        envelope.putInt(length).put((byte) 0x03).put((byte) 'v').put((byte) 0).put(document).put((byte) 0);
        return envelope.array();
    }
}
