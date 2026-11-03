package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import org.junit.jupiter.api.Test;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_TABLE_ENGLISH_NAME_INVALID;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_TABLE_ENGLISH_NAME_REQUIRED;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_TABLE_NAME_TOO_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 表名规范：完整表名 = 分层前缀 + 表英文名，且不重复拼前缀。
 */
class ModelTableNameResolverTest {

    private final ModelTableNameResolver resolver = new ModelTableNameResolver();

    private DwLayerDO layer(String tablePrefix) {
        DwLayerDO layer = new DwLayerDO();
        layer.setTablePrefix(tablePrefix);
        return layer;
    }

    @Test
    void buildFullTableName_shouldPrependLayerPrefix() {
        assertEquals("dwd_accept_event",
                resolver.buildFullTableName(layer("dwd_"), "accept_event"));
    }

    @Test
    void buildFullTableName_shouldNotDuplicatePrefix() {
        // 用户把前缀一起填了，或存量接口直接传完整表名
        assertEquals("dwd_accept_event",
                resolver.buildFullTableName(layer("dwd_"), "dwd_accept_event"));
    }

    @Test
    void buildFullTableName_shouldFallbackToEnglishNameWithoutPrefix() {
        assertEquals("accept_event",
                resolver.buildFullTableName(null, "accept_event"));
        assertEquals("accept_event",
                resolver.buildFullTableName(layer("  "), "accept_event"));
    }

    @Test
    void buildFullTableName_shouldNormalizeCase() {
        assertEquals("dwd_accept_event",
                resolver.buildFullTableName(layer("DWD_"), " ACCEPT_EVENT "));
    }

    @Test
    void buildFullTableName_shouldRejectBlankOrIllegalName() {
        assertEquals(MODEL_TABLE_ENGLISH_NAME_REQUIRED.getCode(),
                assertThrows(ServiceException.class,
                        () -> resolver.buildFullTableName(layer("dwd_"), " ")).getCode());
        // 中划线、以数字开头都不允许
        assertEquals(MODEL_TABLE_ENGLISH_NAME_INVALID.getCode(),
                assertThrows(ServiceException.class,
                        () -> resolver.buildFullTableName(layer("dwd_"), "accept-event")).getCode());
        assertEquals(MODEL_TABLE_ENGLISH_NAME_INVALID.getCode(),
                assertThrows(ServiceException.class,
                        () -> resolver.buildFullTableName(layer("dwd_"), "1accept")).getCode());
    }

    @Test
    void buildFullTableName_shouldRejectTooLongName() {
        String longName = "a".repeat(126);
        assertEquals(MODEL_TABLE_NAME_TOO_LONG.getCode(),
                assertThrows(ServiceException.class,
                        () -> resolver.buildFullTableName(layer("dwd_"), longName)).getCode());
    }

    @Test
    void resolveEnglishName_shouldStripLayerPrefix() {
        assertEquals("accept_event",
                resolver.resolveEnglishName(layer("dwd_"), "dwd_accept_event"));
        // 表名不带该分层前缀时原样返回，逆向接入的存量表就是这种情况
        assertEquals("ods_accept_event",
                resolver.resolveEnglishName(layer("dwd_"), "ODS_ACCEPT_EVENT"));
        assertEquals("accept_event",
                resolver.resolveEnglishName(null, "accept_event"));
        assertNull(resolver.resolveEnglishName(layer("dwd_"), " "));
    }

    @Test
    void resolveEnglishName_shouldKeepNameEqualToPrefix() {
        // 表名恰好等于前缀时不能剥成空串
        assertEquals("dwd_", resolver.resolveEnglishName(layer("dwd_"), "dwd_"));
    }

}
