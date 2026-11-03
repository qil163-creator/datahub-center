package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileUploadDO;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchFileRulesTest {
    @Test void rejectsForeignAndExpiredUploadsButAcceptsCurrentTaskBinding() {
        Save save = save(1L, List.of(field(0, "中文", "string")));
        BatchFileUploadDO upload = upload(1L, 10L);
        assertDoesNotThrow(() -> BatchFileRules.validate(save, Map.of(1L, upload), 10L, null));
        assertThrows(RuntimeException.class, () -> BatchFileRules.validate(save, Map.of(1L, upload), 11L, null));
        upload.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(RuntimeException.class, () -> BatchFileRules.validate(save, Map.of(1L, upload), 10L, null));
        upload.setTaskId(20L);
        assertDoesNotThrow(() -> BatchFileRules.validate(save, Map.of(1L, upload), 11L, 20L));
        assertThrows(RuntimeException.class, () -> BatchFileRules.validate(save, Map.of(1L, upload), 11L, 21L));
    }
    @Test void validatesFieldIdentityCoverageAndMergeOrder() {
        assertThrows(RuntimeException.class, () -> BatchFileRules.headers(List.of("A", "a")));
        assertThrows(RuntimeException.class, () -> BatchFileRules.validateFields(List.of(field(1, "字段", "string")), 1));
        assertTrue(BatchFileRules.sameStructure(List.of(field(0, "字段", "string")), List.of(field(3, "字段", "string"))));
        assertFalse(BatchFileRules.sameStructure(List.of(field(0, "字段", "int")), List.of(field(0, "字段", "string"))));
        Save save = save(1L, List.of(field(0, "字段", "string")));
        save.getTargets().getFirst().setFileIds(List.of(1L, 1L));
        assertThrows(RuntimeException.class, () -> BatchFileRules.validate(save, Map.of(1L, upload(1L, 10L)), 10L, null));
    }
    @Test void conversionsKeepExactValuesAndRejectInvalidValues() {
        assertEquals(new BigDecimal("12345678901234567890.123456789012345678"), BatchFileRules.value("12345678901234567890.123456789012345678", "decimal"));
        assertThrows(RuntimeException.class, () -> BatchFileRules.value("123456789012345678901", "decimal"));
        assertThrows(RuntimeException.class, () -> BatchFileRules.value("NaN", "double"));
        assertThrows(RuntimeException.class, () -> BatchFileRules.value("2147483648", "int"));
        assertThrows(RuntimeException.class, () -> BatchFileRules.value("yes", "boolean"));
        assertEquals(true, BatchFileRules.value("1", "boolean"));
        assertNull(BatchFileRules.value("", "date"));
    }
    @Test void targetOrderingSurvivesSnapshotAndPartialResultsAreNotSuccess() {
        Task task = new Task(); File a = new File(); a.setId(1L); a.setName("a.csv");
        File b = new File(); b.setId(2L); b.setName("b.csv"); task.setFiles(List.of(a, b));
        Target group = new Target(); group.setName("append_target"); group.setFileIds(List.of(2L, 1L)); task.setTargets(List.of(group));
        List<RunItem> items = BatchFileRunService.initialItems(task);
        assertEquals(List.of(2L, 1L), items.stream().map(RunItem::getFileId).toList());
        items.getFirst().setStatus("SUCCESS"); items.getFirst().setWrittenRows(5L); items.getLast().setStatus("FAILED");
        assertEquals("PARTIAL_SUCCESS", BatchFileRunService.finalStatus(items));
        items.getLast().setStatus("UNKNOWN"); assertEquals("UNKNOWN", BatchFileRunService.finalStatus(items));
    }
    static Field field(int index, String name, String type) { Field f = new Field(); f.setSourceIndex(index); f.setName(name); f.setType(type); return f; }
    static BatchFileUploadDO upload(Long id, Long owner) {
        BatchFileUploadDO u = new BatchFileUploadDO(); u.setId(id); u.setOwnerId(owner); u.setState("READY"); u.setSize(10L);
        u.setExpiresAt(LocalDateTime.now().plusHours(1)); u.setFieldsJson(BatchFileRules.json(List.of(field(0, "字段", "string")))); return u;
    }
    static Save save(Long id, List<Field> fields) {
        Save s = new Save(); FileSelection f = new FileSelection(); f.setId(id); f.setFields(fields); s.setFiles(List.of(f));
        Target t = new Target(); t.setName("target_table"); t.setFileIds(List.of(id)); s.setTargets(List.of(t)); return s;
    }
}

