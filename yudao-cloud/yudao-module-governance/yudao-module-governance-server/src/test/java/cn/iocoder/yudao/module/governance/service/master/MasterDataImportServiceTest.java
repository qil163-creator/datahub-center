package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataChangeSubmitReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelRespVO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDataImportServiceTest {

    private final MasterModelService models = mock(MasterModelService.class);
    private final MasterDataChangeService changes = mock(MasterDataChangeService.class);
    private final MasterDataImportService service = new MasterDataImportService(models, changes);

    @Test
    void createsOneBatchAndLeavesTheGeneratedKeyToTheModelRule() throws Exception {
        var model = model();
        model.setEnabledCodeField("id");
        when(models.getMaintenanceModel(21L)).thenReturn(model);
        String request = UUID.randomUUID().toString();
        service.submit(21L, request, workbook(List.of(List.of("name"), List.of("甲"), List.of("乙"))));
        var submitted = ArgumentCaptor.forClass(MasterDataChangeSubmitReqVO.class);
        verify(changes).submit(submitted.capture());
        assertEquals(request, submitted.getValue().getRequestId());
        assertEquals("CREATE", submitted.getValue().getOperation());
        assertEquals(List.of(Map.of("name", "甲"), Map.of("name", "乙")), submitted.getValue().getRows());
    }

    @Test
    void rejectsInvalidHeaderOrRowBeforeRegisteringAnyChange() throws Exception {
        when(models.getMaintenanceModel(21L)).thenReturn(model());
        for (var rows : List.of(
                List.of(List.of("name"), List.of("缺少主键表头")),
                List.of(List.of("id", "id"), List.of("A", "B")),
                List.of(List.of("id", "name"), List.of("valid", "甲"), List.of("", "乙")),
                List.of(List.of("id"), List.of("valid", "无表头的数据"))
        )) {
            var file = workbook(rows);
            assertThrows(ServiceException.class, () -> service.submit(21L, UUID.randomUUID().toString(), file));
        }
        verifyNoInteractions(changes);
    }

    @Test
    void rejectsReadOnlyModelBeforeRegisteringAnyChange() throws Exception {
        var model = model();
        model.setMaintenanceSupported(false);
        when(models.getMaintenanceModel(21L)).thenReturn(model);
        var file = workbook(List.of(List.of("id"), List.of("A")));
        assertThrows(ServiceException.class, () -> service.submit(21L, UUID.randomUUID().toString(), file));
        verifyNoInteractions(changes);
    }

    private static MasterModelRespVO model() {
        var model = new MasterModelRespVO();
        model.setMaintenanceSupported(true);
        var key = new MasterColumnVO();
        key.setName("id");
        key.setFieldType(ColumnMetaData.TYPE_STRING);
        key.setPk(1);
        var value = new MasterColumnVO();
        value.setName("name");
        value.setFieldType(ColumnMetaData.TYPE_STRING);
        model.setColumns(List.of(key, value));
        return model;
    }

    private static MockMultipartFile workbook(List<List<String>> rows) throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("主数据");
            for (int i = 0; i < rows.size(); i++) {
                var row = sheet.createRow(i);
                for (int c = 0; c < rows.get(i).size(); c++) {
                    row.createCell(c).setCellValue(rows.get(i).get(c));
                }
            }
            workbook.write(bytes);
            return new MockMultipartFile("file", "qa.xlsx", null, bytes.toByteArray());
        }
    }
}
