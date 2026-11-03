package cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryStatusReqVO;
import cn.iocoder.yudao.module.dataforge.service.projectcategory.ProjectCategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证管理端请求位置、真实校验和大整数入参，不用独立测试映射器推断全局序列化。 */
class ProjectCategoryControllerTest extends BaseMockitoUnitTest {
    private static final String PREFIX = "/dataforge/project-category";
    @Mock private ProjectCategoryService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setup() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ProjectCategoryController(service)).setValidator(validator).build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void readEndpointsTakeKindAndLosslessIdentityFromQueryParameters() throws Exception {
        when(service.list("integration")).thenReturn(List.of());
        when(service.get("development", 9007199254740993L)).thenReturn(new ProjectCategoryRespVO());

        mvc.perform(get(PREFIX + "/list").param("kind", "integration")).andExpect(status().isOk());
        mvc.perform(get(PREFIX + "/get").param("kind", "development").param("id", "9007199254740993"))
                .andExpect(status().isOk());

        verify(service).list("integration");
        verify(service).get("development", 9007199254740993L);
    }

    @Test
    void createAndUpdateReadValidatedBodiesWithMaximumTextLengthsAndLargeIds() throws Exception {
        String maximumName = "A".repeat(50);
        String maximumDescription = "描".repeat(256);
        String createBody = """
                {"kind":"integration","parentId":9007199254740993,"name":"%s",
                 "description":"%s","status":0,"sort":3}
                """.formatted(maximumName, maximumDescription);
        mvc.perform(post(PREFIX + "/create").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk());
        String updateBody = """
                {"kind":"development","id":9007199254740995,"parentId":0,"name":"修改类目",
                 "description":"当前描述","status":1,"sort":4}
                """;
        mvc.perform(put(PREFIX + "/update").contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());

        var create = ArgumentCaptor.forClass(ProjectCategorySaveReqVO.class);
        verify(service).create(create.capture());
        assertEquals("integration", create.getValue().getKind());
        assertEquals(9007199254740993L, create.getValue().getParentId());
        assertEquals(maximumName, create.getValue().getName());
        assertEquals(maximumDescription, create.getValue().getDescription());
        var update = ArgumentCaptor.forClass(ProjectCategorySaveReqVO.class);
        verify(service).update(update.capture());
        assertEquals("development", update.getValue().getKind());
        assertEquals(9007199254740995L, update.getValue().getId());
        assertEquals(1, update.getValue().getStatus());
    }

    @Test
    void deletionParsesRepeatedIdsAndStatusEndpointAcceptsOnlyItsSparseBody() throws Exception {
        mvc.perform(delete(PREFIX + "/delete").param("kind", "integration").param("id", "9007199254740993"))
                .andExpect(status().isOk());
        mvc.perform(delete(PREFIX + "/delete-list").param("kind", "development")
                        .param("ids", "9007199254740993", "9007199254740995", "9007199254740995"))
                .andExpect(status().isOk());
        mvc.perform(put(PREFIX + "/update-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"integration\",\"id\":9007199254740993,\"status\":1}"))
                .andExpect(status().isOk());

        verify(service).delete("integration", 9007199254740993L);
        verify(service).deleteList("development", List.of(9007199254740993L, 9007199254740995L, 9007199254740995L));
        var change = ArgumentCaptor.forClass(ProjectCategoryStatusReqVO.class);
        verify(service).updateStatus(change.capture());
        assertEquals("integration", change.getValue().getKind());
        assertEquals(9007199254740993L, change.getValue().getId());
        assertEquals(1, change.getValue().getStatus());
    }

    @Test
    void invalidKindTextBoundsAndStatusNeverReachTheService() throws Exception {
        mvc.perform(get(PREFIX + "/list").param("kind", "other")).andExpect(status().isBadRequest());
        mvc.perform(get(PREFIX + "/get").param("id", "1")).andExpect(status().isBadRequest());
        for (String body : List.of(
                saveBody("other", "正常类目", "描述", 0),
                saveBody("integration", "A".repeat(51), "描述", 0),
                saveBody("development", "正常类目", "描".repeat(257), 0),
                saveBody("development", "正常类目", "描述", 2),
                saveBody("integration", "***", "描述", 0))) {
            mvc.perform(post(PREFIX + "/create").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(PREFIX + "/update-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"integration\",\"id\":1,\"status\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(PREFIX + "/update-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"development\",\"id\":0,\"status\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void destructiveEndpointsRejectIncorrectHttpMethods() throws Exception {
        mvc.perform(get(PREFIX + "/delete").param("kind", "integration").param("id", "1"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(PREFIX + "/update-status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"development\",\"id\":1,\"status\":1}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    private String saveBody(String kind, String name, String description, int status) {
        return "{\"kind\":\"%s\",\"parentId\":0,\"name\":\"%s\",\"description\":\"%s\",\"status\":%d,\"sort\":0}"
                .formatted(kind, name, description, status);
    }
}
