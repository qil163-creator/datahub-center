package cn.iocoder.yudao.module.dataforge.service.fileresource;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.file.core.remote.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fileresource.FileResourceOperationDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fileresource.FileResourceOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证权限后的业务编排、远程结果不确定性与受限内容处理。 */
class FileResourceServiceTest {
    private final FileResourceConnectionResolver resolver = mock(FileResourceConnectionResolver.class);
    private final FileResourceOperationMapper operations = mock(FileResourceOperationMapper.class);
    private final RemoteFileClient client = mock(RemoteFileClient.class);
    private final FileResourceService service = new FileResourceService();
    private static final Long ID = 9007199254740993L;
    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(service,"connections",resolver);
        ReflectionTestUtils.setField(service,"operationMapper",operations);
        when(resolver.open(ID)).thenReturn(client);
    }

    @Test void filtersBeforePaginationAndKeepsDirectoryFirst() throws Exception {
        when(client.list("/")).thenReturn(List.of(file("x.csv",8),file("b.txt",1),file("a.txt",2),dir("/alpha")));
        FileResourcePageReqVO req = new FileResourcePageReqVO();
        req.setConnectionId(ID); req.setType("TXT"); req.setPageNo(2); req.setPageSize(1);
        var page = service.page(req);
        assertEquals(2,page.getTotal()); assertEquals("b.txt",page.getList().getFirst().getName());
        verify(client).close();
    }

    @Test void summaryReadsRealRootAndCountsOnlyFiles() throws Exception {
        when(client.stat("/")).thenReturn(dir("/"));
        when(client.list("/")).thenReturn(List.of(file("a.txt",10),dir("/child")));
        when(client.list("/child")).thenReturn(List.of(new RemoteFileEntry("b.txt","/child/b.txt",false,20,1)));
        when(operations.countTodayUploads(ID)).thenReturn(7L);
        var result = service.summary(ID);
        assertTrue(result.isComplete()); assertEquals("CONNECTED",result.getConnectionStatus());
        assertEquals(2,result.getFileCount()); assertEquals(1,result.getDirectoryCount());
        assertEquals(30,result.getStorageBytes()); assertEquals(7,result.getTodayUploadCount());
    }

    @Test void incompleteSummaryDoesNotInventZero() throws Exception {
        when(client.stat("/")).thenReturn(dir("/"));
        when(client.list("/")).thenThrow(new IOException("private endpoint credentials"));
        var result=service.summary(ID);
        assertFalse(result.isComplete()); assertNull(result.getFileCount()); assertNull(result.getStorageBytes());
        assertFalse(result.getMessage().contains("credentials"));
    }

    @Test void uploadRecordsIntentBeforeRemoteWriteAndOutcomeAfterward() throws Exception {
        var file=new MockMultipartFile("file","sample.txt","text/plain","data".getBytes(StandardCharsets.UTF_8));
        service.upload(ID,"/",file,true);
        var inOrder=inOrder(operations,client);
        inOrder.verify(operations).insert(any(FileResourceOperationDO.class));
        inOrder.verify(client).upload(eq("/sample.txt"),any(InputStream.class),eq(4L),eq(true));
        inOrder.verify(operations).updateById(any(FileResourceOperationDO.class));
        var captor=ArgumentCaptor.forClass(FileResourceOperationDO.class);
        verify(operations).updateById(captor.capture());
        assertEquals("SUCCESS",captor.getValue().getStatus());
    }

    @Test void uploadFailureRetainsUnknownIntentAndNeverRetries() throws Exception {
        doThrow(new IOException("connection lost")).when(client).upload(anyString(),any(),anyLong(),anyBoolean());
        assertThrows(ServiceException.class,()->service.upload(ID,"/",new MockMultipartFile("file","x.txt","text/plain",new byte[]{1}),false));
        var captor=ArgumentCaptor.forClass(FileResourceOperationDO.class);
        verify(operations).updateById(captor.capture());
        assertEquals("UNKNOWN",captor.getValue().getStatus());
        verify(client,times(1)).upload(anyString(),any(),anyLong(),anyBoolean());
    }

    @Test void rejectsOversizeAndRootDeletionBeforeOpeningConnection() {
        var file=mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getSize()).thenReturn(FileResourceService.MAX_BYTES+1);
        assertThrows(ServiceException.class,()->service.upload(ID,"/",file,false));
        var req=new FileResourceBatchReqVO(); req.setConnectionId(ID); req.setPaths(List.of("/safe","/"));
        assertThrows(ServiceException.class,()->service.delete(req));
        verifyNoInteractions(resolver,operations);
    }

    @Test void rejectsTraversalAndNameSeparators() {
        var req=new FileResourceNameReqVO(); req.setConnectionId(ID); req.setPath("/"); req.setName("../escape");
        assertThrows(ServiceException.class,()->service.mkdir(req));
        req.setPath("/../escape"); req.setName("safe");
        assertThrows(ServiceException.class,()->service.rename(req));
        verifyNoInteractions(resolver,operations);
    }

    @Test void generatedPathsCannotExceedTheRequestAndAuditLimit() {
        String parent = "/" + "p/".repeat(2046) + "p";
        var req = new FileResourceNameReqVO(); req.setConnectionId(ID); req.setPath(parent); req.setName("new");
        assertThrows(ServiceException.class, () -> service.mkdir(req));
        assertThrows(ServiceException.class, () -> service.upload(ID, parent,
                new MockMultipartFile("file", "file.txt", "text/plain", new byte[]{1}), false));
        req.setPath(parent + "/a");
        assertThrows(ServiceException.class, () -> service.rename(req));
        verifyNoInteractions(resolver, operations);
    }

    @Test void preservesLiteralPercentAndUnicodeNamesAcrossOperations() throws Exception {
        String original = "100%+中文.txt";
        String directory = "/完成率100%";
        service.upload(ID, directory,
                new MockMultipartFile("file", original, "text/plain", new byte[]{1}), false);
        verify(client).upload(eq(directory + "/" + original), any(InputStream.class), eq(1L), eq(false));

        var rename = new FileResourceNameReqVO();
        rename.setConnectionId(ID); rename.setPath(directory + "/" + original); rename.setName("  50%+中文.txt  ");
        service.rename(rename);
        verify(client).rename(directory + "/" + original, directory + "/50%+中文.txt");

        var mkdir = new FileResourceNameReqVO();
        mkdir.setConnectionId(ID); mkdir.setPath("/"); mkdir.setName("完成率100%");
        service.mkdir(mkdir);
        verify(client).mkdir(directory);

        when(client.list(directory)).thenReturn(List.of(
                new RemoteFileEntry("50%+中文.txt", directory + "/50%+中文.txt", false, 1, 1)));
        var page = new FileResourcePageReqVO(); page.setConnectionId(ID); page.setPath(directory);
        assertEquals(directory + "/50%+中文.txt", service.page(page).getList().getFirst().getPath());
    }

    @Test void rejectsEncodedOctetsBeforeMutation() {
        for (String encoded : List.of("%2f", "%2F", "%25", "%41")) {
            assertThrows(ServiceException.class, () -> service.upload(ID, "/",
                    new MockMultipartFile("file", "report" + encoded + ".txt", "text/plain", new byte[]{1}), false));
            var req = new FileResourceNameReqVO();
            req.setConnectionId(ID); req.setPath("/"); req.setName("folder" + encoded);
            assertThrows(ServiceException.class, () -> service.mkdir(req));
            req.setPath("/folder" + encoded); req.setName("safe");
            assertThrows(ServiceException.class, () -> service.rename(req));
        }
        verifyNoInteractions(resolver, operations);
    }

    @Test void batchReportsPartialFailureWithoutPretendingAtomicRollback() throws Exception {
        when(client.stat("/ok.txt")).thenReturn(file("ok.txt",1));
        when(client.stat("/fail.txt")).thenThrow(new IOException());
        var req=new FileResourceBatchReqVO(); req.setConnectionId(ID); req.setPaths(List.of("/ok.txt","/fail.txt"));
        var result=service.delete(req);
        assertEquals(List.of("/ok.txt"),result.getSucceededPaths());
        assertEquals("/fail.txt",result.getFailures().getFirst().path());
        verify(client).delete("/ok.txt");
    }

    @Test void textPreviewIsBoundedAndDoesNotRenderMarkup() throws Exception {
        byte[] text=new byte[1024*1024+1]; java.util.Arrays.fill(text,(byte)'x');
        when(client.stat("/a.txt")).thenReturn(file("a.txt",text.length));
        doAnswer(inv->{ ((OutputStream)inv.getArgument(1)).write(text); return null; }).when(client).download(anyString(),any(),anyLong());
        var result=service.preview(ID,"/a.txt");
        assertTrue(result.truncated()); assertEquals(1024*1024,result.content().length()); assertEquals("TEXT",result.type());
        when(client.stat("/a.html")).thenReturn(file("a.html",12));
        assertEquals("UNSUPPORTED",service.preview(ID,"/a.html").type());
    }

    @Test void previewRejectsForgedMimeAndHonorsBinaryLimit() throws Exception {
        assertNull(FileResourceService.previewMime("PNG","<html>evil</html>".getBytes(StandardCharsets.UTF_8)));
        assertNull(FileResourceService.previewMime("SVG","<svg>evil</svg>".getBytes(StandardCharsets.UTF_8)));
        byte[] pdf="%PDF-1.7 example".getBytes(StandardCharsets.US_ASCII);
        assertEquals("application/pdf",FileResourceService.previewMime("PDF",pdf));
        when(client.stat("/large.pdf")).thenReturn(file("large.pdf",21L*1024*1024));
        assertThrows(ServiceException.class,()->service.previewContent(ID,"/large.pdf"));
        verify(client,never()).download(anyString(),any(),anyLong());
    }

    private static RemoteFileEntry file(String name,long size) { return new RemoteFileEntry(name,"/"+name,false,size,1); }
    private static RemoteFileEntry dir(String path) { return new RemoteFileEntry(path.equals("/")?"/":path.substring(1),path,true,0,1); }
}

