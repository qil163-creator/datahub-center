package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILL_SUBMISSION_PREVIEW_DENIED;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FillSubmissionPreviewPermissionTest {

    private final FillWorkItemService workItemService = mock(FillWorkItemService.class);
    private final FillBpmAdapter bpmAdapter = mock(FillBpmAdapter.class);
    private final FillSubmissionServiceImpl service = new FillSubmissionServiceImpl();
    private final FillWorkItemDO item = FillWorkItemDO.builder().id(4L).build();
    private final FillSubmissionDO submission = FillSubmissionDO.builder()
            .id(3L).submitterUserId(5L).processInstanceId("pi-1").build();

    FillSubmissionPreviewPermissionTest() {
        ReflectionTestUtils.setField(service, "workItemService", workItemService);
        ReflectionTestUtils.setField(service, "bpmAdapter", bpmAdapter);
    }

    private void check(Long userId) {
        ReflectionTestUtils.invokeMethod(service, "checkPreviewPermission", submission, item, userId);
    }

    @Test
    void submitterAndFillRelationsDoNotNeedBpm() {
        when(workItemService.hasViewPermission(item, 6L)).thenReturn(true);

        assertThatCode(() -> check(5L)).doesNotThrowAnyException();
        assertThatCode(() -> check(6L)).doesNotThrowAnyException();
        verify(bpmAdapter, never()).isProcessInstanceParticipant(anyString(), anyLong());
    }

    @Test
    void approvalParticipantIsAllowed() {
        when(bpmAdapter.isProcessInstanceParticipant("pi-1", 7L)).thenReturn(true);

        assertThatCode(() -> check(7L)).doesNotThrowAnyException();
    }

    @Test
    void unrelatedUserWithOnlyMenuPermissionIsDenied() {
        assertThatThrownBy(() -> check(8L))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(FILL_SUBMISSION_PREVIEW_DENIED.getCode()));
    }

    @Test
    void bpmFailureFailsClosed() {
        when(bpmAdapter.isProcessInstanceParticipant("pi-1", 7L)).thenThrow(new IllegalStateException("bpm down"));

        assertThatThrownBy(() -> check(7L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void submissionWithoutProcessInstanceIsDeniedForOutsiders() {
        submission.setProcessInstanceId(null);

        assertThatThrownBy(() -> check(7L)).isInstanceOf(ServiceException.class);
        verify(bpmAdapter, never()).isProcessInstanceParticipant(anyString(), anyLong());
    }
}
