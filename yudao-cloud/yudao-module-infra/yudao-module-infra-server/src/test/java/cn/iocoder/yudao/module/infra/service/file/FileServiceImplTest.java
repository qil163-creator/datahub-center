package cn.iocoder.yudao.module.infra.service.file;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.ObjectUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.framework.test.core.util.AssertUtils;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FileCreateReqVO;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FilePresignedUrlRespVO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import cn.iocoder.yudao.module.infra.dal.mysql.file.FileMapper;
import cn.iocoder.yudao.module.infra.framework.file.core.client.FileClient;
import jakarta.annotation.Resource;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static cn.iocoder.yudao.framework.common.util.date.LocalDateTimeUtils.buildTime;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.*;
import static cn.iocoder.yudao.module.infra.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

@Import({FileServiceImpl.class})
public class FileServiceImplTest extends BaseDbUnitTest {

    @Resource
    private FileServiceImpl fileService;

    @Resource
    private FileMapper fileMapper;

    @MockitoBean
    private FileConfigService fileConfigService;

    @BeforeEach
    public void setUp() {
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_AS_DIRECTORY = true;
    }

    @Test
    public void testGetFilePage() {
        // mock 数据
        FileDO dbFile = randomPojo(FileDO.class, o -> { // 等会查询到
            o.setPath("yunai");
            o.setType("image/jpg");
            o.setCreateTime(buildTime(2021, 1, 15));
        });
        fileMapper.insert(dbFile);
        // 测试 path 不匹配
        fileMapper.insert(ObjectUtils.cloneIgnoreId(dbFile, o -> o.setPath("tudou")));
        // 测试 type 不匹配
        fileMapper.insert(ObjectUtils.cloneIgnoreId(dbFile, o -> {
            o.setType("image/png");
        }));
        // 测试 createTime 不匹配
        fileMapper.insert(ObjectUtils.cloneIgnoreId(dbFile, o -> {
            o.setCreateTime(buildTime(2020, 1, 15));
        }));
        // 准备参数
        FilePageReqVO reqVO = new FilePageReqVO();
        reqVO.setPath("yunai");
        reqVO.setType("jp");
        reqVO.setCreateTime((new LocalDateTime[]{buildTime(2021, 1, 10), buildTime(2021, 1, 20)}));

        // 调用
        PageResult<FileDO> pageResult = fileService.getFilePage(reqVO);
        // 断言
        assertEquals(1, pageResult.getTotal());
        assertEquals(1, pageResult.getList().size());
        AssertUtils.assertPojoEquals(dbFile, pageResult.getList().get(0));
    }

    /**
     * content、name、directory、type 都非空
     */
    @Test
    public void testCreateFile_success_01() throws Exception {
        // 准备参数
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        String name = "单测文件名";
        String directory = randomString();
        String type = "image/jpeg";
        // mock Master 文件客户端
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        String url = randomString();
        AtomicReference<String> pathRef = new AtomicReference<>();
        when(client.upload(same(content), argThat(path -> {
            assertTrue(path.matches(directory + "/\\d{8}/\\d+/" + name + ".jpg"));
            pathRef.set(path);
            return true;
        }), eq(type))).thenReturn(url);
        when(client.getId()).thenReturn(10L);
        // 调用
        String result = fileService.createFile(content, name, directory, type);
        // 断言
        assertEquals(result, url);
        // 校验数据
        FileDO file = fileMapper.selectOne(FileDO::getUrl, url);
        assertEquals(10L, file.getConfigId());
        assertEquals(pathRef.get(), file.getPath());
        assertEquals(url, file.getUrl());
        assertEquals(type, file.getType());
        assertEquals(content.length, file.getSize());
    }

    /**
     * content 非空，其它都空
     */
    @Test
    public void testCreateFile_success_02() throws Exception {
        // 准备参数
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        // mock Master 文件客户端
        String type = "image/jpeg";
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        String url = randomString();
        AtomicReference<String> pathRef = new AtomicReference<>();
        when(client.upload(same(content), argThat(path -> {
            assertTrue(path.matches("\\d{8}/\\d+/6318848e882d8a7e7e82789d87608f684ee52d41966bfc8cad3ce15aad2b970e\\.jpg"));
            pathRef.set(path);
            return true;
        }), eq(type))).thenReturn(url);
        when(client.getId()).thenReturn(10L);
        // 调用
        String result = fileService.createFile(content, null, null, null);
        // 断言
        assertEquals(result, url);
        // 校验数据
        FileDO file = fileMapper.selectOne(FileDO::getUrl, url);
        assertEquals(10L, file.getConfigId());
        assertEquals(pathRef.get(), file.getPath());
        assertEquals(url, file.getUrl());
        assertEquals(type, file.getType());
        assertEquals(content.length, file.getSize());
    }

    @Test
    public void testCreateFile_success_image() throws Exception {
        for (String extension : new String[]{"jpg", "png", "gif", "bmp"}) {
            // 准备参数
            String type = "jpg".equals(extension) ? "image/jpeg" : "image/" + extension;
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), extension, outputStream));
            byte[] content = outputStream.toByteArray();
            String name = "头像." + extension.toUpperCase(Locale.ROOT);
            // mock 方法
            FileClient client = mock(FileClient.class);
            when(fileConfigService.getMasterFileClient()).thenReturn(client);
            String url = randomString();
            when(client.upload(same(content), anyString(), eq(type))).thenReturn(url);

            // 调用
            String result = fileService.createFile(content, name, null, "application/octet-stream");
            // 断言
            assertEquals(url, result);
            FileDO file = fileMapper.selectOne(FileDO::getUrl, url);
            assertEquals(name, file.getName());
            assertEquals(type, file.getType());
        }
    }

    @Test
    public void testCreateFile_success_excel() throws Exception {
        for (String extension : new String[]{"xls", "xlsx"}) {
            // 准备参数
            String type = "xls".equals(extension) ? "application/vnd.ms-excel"
                    : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            byte[] content;
            try (Workbook workbook = "xls".equals(extension) ? new HSSFWorkbook() : new XSSFWorkbook();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                workbook.createSheet("用户").createRow(0).createCell(0).setCellValue("芋道源码");
                workbook.write(outputStream);
                content = outputStream.toByteArray();
            }
            // mock 方法
            FileClient client = mock(FileClient.class);
            when(fileConfigService.getMasterFileClient()).thenReturn(client);
            String url = randomString();
            when(client.upload(same(content), anyString(), eq(type))).thenReturn(url);

            // 调用
            String result = fileService.createFile(content, "用户." + extension, null, null);
            // 断言
            assertEquals(url, result);
            assertEquals(type, fileMapper.selectOne(FileDO::getUrl, url).getType());
        }
    }

    @Test
    public void testCreateFile_success_detectType() throws Exception {
        for (String declaredType : new String[]{null, "", "image/jpg", "text/html", "application/octet-stream"}) {
            // 准备参数
            byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
            // mock 方法
            FileClient client = mock(FileClient.class);
            when(fileConfigService.getMasterFileClient()).thenReturn(client);
            String url = randomString();
            when(client.upload(same(content), anyString(), eq("image/jpeg"))).thenReturn(url);

            // 调用
            String result = fileService.createFile(content, "头像.jpg", null, declaredType);
            // 断言
            assertEquals(url, result);
            assertEquals("image/jpeg", fileMapper.selectOne(FileDO::getUrl, url).getType());
        }
    }

    @Test
    public void testCreateFile_success_document() throws Exception {
        for (String[] document : new String[][]{
                {"test.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>", "image/svg+xml"},
                {"test.html", "<html><script>alert(1)</script></html>", "text/html"},
                {"test.xml", "<?xml version=\"1.0\"?><root>test</root>", "application/xml"},
                {"test.markdown", "# test\n\nhello", "text/markdown"},
                {"test.eml", "From: a@example.com\r\nTo: b@example.com\r\nSubject: test\r\n\r\nhello", "message/rfc822"}}) {
            // 准备参数
            String name = document[0];
            byte[] content = document[1].getBytes(StandardCharsets.UTF_8);
            String type = document[2];
            // mock 方法
            FileClient client = mock(FileClient.class);
            when(fileConfigService.getMasterFileClient()).thenReturn(client);
            String url = randomString();
            when(client.upload(same(content), anyString(), eq(type))).thenReturn(url);

            // 调用
            String result = fileService.createFile(content, name, null, "application/octet-stream");
            // 断言
            assertEquals(url, result);
            assertEquals(type, fileMapper.selectOne(FileDO::getUrl, url).getType());
        }
    }

    @Test
    public void testCreateFile_success_opus() throws Exception {
        // 准备参数
        byte[] content = new byte[64];
        System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, content, 28, 8);
        // mock 方法
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        String url = randomString();
        when(client.upload(same(content), anyString(), eq("audio/opus"))).thenReturn(url);

        // 调用
        String result = fileService.createFile(content, "voice.ogg", null, "audio/ogg;codecs=opus");
        // 断言
        assertEquals(url, result);
        assertEquals("audio/opus", fileMapper.selectOne(FileDO::getUrl, url).getType());
    }

    @Test
    public void testCreateFile_typeNotAllowed_fakeImage() {
        for (String text : new String[]{"<html><script>alert(1)</script></html>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>", "普通文本"}) {
            // 准备参数
            byte[] content = text.getBytes(StandardCharsets.UTF_8);

            // 调用，并断言异常
            assertServiceException(() -> fileService.createFile(content, "头像.jpg", null, "image/jpeg"),
                    FILE_TYPE_NOT_ALLOWED);
            // 断言未上传文件、未保存记录
            verifyNoInteractions(fileConfigService);
            assertEquals(0L, fileMapper.selectCount());
        }
    }

    @Test
    public void testCreateFile_typeNotAllowed_extension() {
        for (String name : new String[]{"头像.svg", "头像.js", "头像.exe", "头像.unknown", "头像.png"}) {
            // 准备参数
            byte[] content = ResourceUtil.readBytes("file/erweima.jpg");

            // 调用，并断言异常
            assertServiceException(() -> fileService.createFile(content, name, null, "image/jpeg"), FILE_TYPE_NOT_ALLOWED);
            // 校验调用
            verifyNoInteractions(fileConfigService);
        }
    }

    @Test
    public void testCreateFile_fileIsEmpty() {
        // 准备参数
        byte[] content = new byte[0];

        // 调用，并断言异常
        assertServiceException(() -> fileService.createFile(content, "头像.jpg", null, null), FILE_IS_EMPTY);
        // 校验调用
        verifyNoInteractions(fileConfigService);
    }

    @Test
    public void testPresignPutUrl_success() {
        for (String extension : new String[]{"jpg", "JPEG", "jfif", "png", "gif", "webp", "bmp", "ico", "tiff",
                "avif", "heic", "heif", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md",
                "zip", "rar", "mp3", "mp4", "svg", "html", "htm", "xml", "markdown", "eml", "msg", "epub",
                "json", "opus", "ogg", "m4a", "webm", "amr", "wma", "wmv", "flv", "mkv"}) {
            // 准备参数
            String name = "测试文件." + extension;
            // mock 方法
            FileClient client = mock(FileClient.class);
            when(fileConfigService.getMasterFileClient()).thenReturn(client);
            when(client.getId()).thenReturn(10L);
            when(client.presignPutUrl(anyString())).thenReturn("upload-url");
            when(client.presignGetUrl(anyString(), isNull())).thenReturn("visit-url");

            // 调用
            FilePresignedUrlRespVO result = fileService.presignPutUrl(name, "avatar");
            // 断言
            assertEquals(10L, result.getConfigId());
            assertTrue(result.getPath().endsWith("/" + name));
            assertEquals("upload-url", result.getUploadUrl());
            assertEquals("visit-url", result.getUrl());
        }
    }

    @Test
    public void testPresignPutUrl_typeNotAllowed() {
        // 准备参数
        for (String name : new String[]{"test.php", "test.jsp", "test.js", "test.exe", "test.unknown", "test"}) {
            // 调用，并断言异常
            assertServiceException(() -> fileService.presignPutUrl(name, null), FILE_TYPE_NOT_ALLOWED);
            // 校验调用
            verifyNoInteractions(fileConfigService);
        }
    }

    @Test
    public void testDeleteFile_success() throws Exception {
        // mock 数据
        FileDO dbFile = randomPojo(FileDO.class, o -> o.setConfigId(10L).setPath("tudou.jpg"));
        fileMapper.insert(dbFile);// @Sql: 先插入出一条存在的数据
        // mock Master 文件客户端
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getFileClient(eq(10L))).thenReturn(client);
        // 准备参数
        Long id = dbFile.getId();

        // 调用
        fileService.deleteFile(id);
        // 校验数据不存在了
        assertNull(fileMapper.selectById(id));
        // 校验调用
        verify(client).delete(eq("tudou.jpg"));
    }

    @Test
    public void testDeleteFile_notExists() {
        // 准备参数
        Long id = randomLongId();

        // 调用, 并断言异常
        assertServiceException(() -> fileService.deleteFile(id), FILE_NOT_EXISTS);
    }

    @Test
    public void testDeleteFile_pathInvalid() {
        // mock 数据
        FileDO dbFile = randomPojo(FileDO.class, o -> o.setConfigId(10L).setPath("../tudou.jpg"));
        fileMapper.insert(dbFile);

        // 调用，并断言异常
        assertServiceException(() -> fileService.deleteFile(dbFile.getId()), FILE_PATH_INVALID);
    }

    @Test
    public void testGetFileContent() throws Exception {
        // 准备参数
        Long configId = 10L;
        String path = "tudou.jpg";
        // mock 方法
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getFileClient(eq(10L))).thenReturn(client);
        byte[] content = new byte[]{};
        when(client.getContent(eq("tudou.jpg"))).thenReturn(content);

        // 调用
        byte[] result = fileService.getFileContent(configId, path);
        // 断言
        assertSame(result, content);
    }

    @Test
    public void testGetFileContent_pathInvalid() {
        // 准备参数
        Long configId = 10L;
        String path = "../tudou.jpg";

        // 调用，并断言异常
        assertServiceException(() -> fileService.getFileContent(configId, path), FILE_PATH_INVALID);
    }

    @Test
    public void testGetFileByConfigIdAndPath() {
        // mock 数据
        FileDO dbFile = randomPojo(FileDO.class, o -> o.setConfigId(10L).setPath("avatar/中文 100%+文件.jpg"));
        fileMapper.insert(dbFile);
        FileDO latestFile = ObjectUtils.cloneIgnoreId(dbFile, o -> o.setName("最新文件名.jpg"));
        fileMapper.insert(latestFile);
        fileMapper.insert(ObjectUtils.cloneIgnoreId(dbFile, o -> o.setPath("avatar/other.jpg")));
        fileMapper.insert(ObjectUtils.cloneIgnoreId(dbFile, o -> o.setConfigId(20L)));

        // 调用
        FileDO result = fileService.getFileByConfigIdAndPath(10L, "avatar/中文 100%+文件.jpg");

        // 断言
        AssertUtils.assertPojoEquals(latestFile, result);
    }

    @Test
    public void testCreateFileByPresignedPath_success() {
        // 准备参数
        FileCreateReqVO reqVO = randomPojo(FileCreateReqVO.class, o -> {
            o.setPath("avatar/test.jpg");
            o.setName("test.jpg");
            o.setUrl("https://www.iocoder.cn/test.jpg?token=123");
        });

        // 调用
        Long fileId = fileService.createFile(reqVO);

        // 断言
        FileDO file = fileMapper.selectById(fileId);
        assertEquals("avatar/test.jpg", file.getPath());
        assertEquals("test.jpg", file.getName());
        assertEquals("https://www.iocoder.cn/test.jpg", file.getUrl());
        assertEquals("image/jpeg", file.getType());
    }

    @Test
    public void testCreateFileByPresignedPath_typeNotAllowed() {
        for (String[] file : new String[][]{{"test.js", "avatar/test.js"}, {"test.jpg", "avatar/test.html"},
                {"test.html", "avatar/test.jpg"}, {"test.jpg", "avatar/test.png"}}) {
            // 准备参数
            String name = file[0];
            String path = file[1];
            FileCreateReqVO reqVO = randomPojo(FileCreateReqVO.class, o -> {
                o.setName(name);
                o.setPath(path);
                o.setType("image/jpeg");
            });

            // 调用，并断言异常
            assertServiceException(() -> fileService.createFile(reqVO), FILE_TYPE_NOT_ALLOWED);
            // 断言未保存记录
            assertEquals(0L, fileMapper.selectCount());
        }
    }

    @Test
    public void testCreateFileByPresignedPath_nameInvalid() {
        // 准备参数
        FileCreateReqVO reqVO = randomPojo(FileCreateReqVO.class, o -> {
            o.setPath("avatar/test.jpg");
            o.setName("../test.jpg");
        });

        // 调用，并断言异常
        assertServiceException(() -> fileService.createFile(reqVO), FILE_PATH_INVALID);
    }

    @Test
    public void testCreateFileByPresignedPath_pathInvalid() {
        // 准备参数
        FileCreateReqVO reqVO = randomPojo(FileCreateReqVO.class, o -> {
            o.setPath("../test.jpg");
            o.setName("test.jpg");
        });

        // 调用，并断言异常
        assertServiceException(() -> fileService.createFile(reqVO), FILE_PATH_INVALID);
    }

    @Test
    public void testGenerateUploadPath_AllEnabled() {
        // 准备参数
        String name = "test.jpg";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/yyyyMMdd/{时间戳+随机数}/test.jpg
        assertTrue(path.startsWith(directory + "/"));
        // 包含日期格式：8 位数字，如 20240517
        assertTrue(path.matches(directory + "/\\d{8}/\\d+/test\\.jpg"));
    }

    @Test
    public void testGenerateUploadPath_PrefixEnabled_SuffixDisabled() {
        // 准备参数
        String name = "test.jpg";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = false;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/yyyyMMdd/test.jpg
        assertTrue(path.startsWith(directory + "/"));
        // 包含日期格式：8 位数字，如 20240517
        assertTrue(path.matches(directory + "/\\d{8}/test\\.jpg"));
    }

    @Test
    public void testGenerateUploadPath_PrefixDisabled_SuffixEnabled() {
        // 准备参数
        String name = "test.jpg";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = false;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/{时间戳+随机数}/test.jpg
        assertTrue(path.startsWith(directory + "/"));
        assertTrue(path.matches(directory + "/\\d+/test\\.jpg"));
    }

    @Test
    public void testGenerateUploadPath_AllDisabled() {
        // 准备参数
        String name = "test.jpg";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = false;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = false;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/test.jpg
        assertEquals(directory + "/" + name, path);
    }

    @Test
    public void testGenerateUploadPath_NoExtension() {
        // 准备参数
        String name = "test";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/yyyyMMdd/{时间戳+随机数}/test
        assertTrue(path.startsWith(directory + "/"));
        assertTrue(path.matches(directory + "/\\d{8}/\\d+/test"));
    }

    @Test
    public void testGenerateUploadPath_DirectoryNull() {
        // 准备参数
        String name = "test.jpg";
        String directory = null;
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：yyyyMMdd/{时间戳+随机数}/test.jpg
        assertTrue(path.matches("\\d{8}/\\d+/test\\.jpg"));
    }

    @Test
    public void testGenerateUploadPath_SuffixAsName_AllEnabled() {
        // 准备参数
        String name = "test.jpg";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_AS_DIRECTORY = false;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/yyyyMMdd/test_{时间戳+随机数}.jpg
        assertTrue(path.matches(directory + "/\\d{8}/test_\\d+\\.jpg"));
    }

    @Test
    public void testGenerateUploadPath_SuffixAsName_PrefixDisabled() {
        // 准备参数
        String name = "test.jpg";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = false;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_AS_DIRECTORY = false;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/test_{时间戳+随机数}.jpg
        assertTrue(path.matches(directory + "/test_\\d+\\.jpg"));
    }

    @Test
    public void testGenerateUploadPath_SuffixAsName_NoExtension() {
        // 准备参数
        String name = "test";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_AS_DIRECTORY = false;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：avatar/yyyyMMdd/test_{时间戳+随机数}
        assertTrue(path.matches(directory + "/\\d{8}/test_\\d+"));
    }

    @Test
    public void testGenerateUploadPath_FileNameInvalid() {
        // 准备参数
        String name = "../test.jpg";
        String directory = "avatar";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = false;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = false;

        // 调用，并断言异常
        assertServiceException(() -> fileService.generateUploadPath(name, directory), FILE_PATH_INVALID);
    }

    @Test
    public void testGenerateUploadPath_DirectoryInvalid() {
        // 准备参数
        String name = "test.jpg";
        String directory = "../avatar";

        // 调用，并断言异常
        assertServiceException(() -> fileService.generateUploadPath(name, directory), FILE_PATH_INVALID);
    }

    @Test
    public void testGenerateUploadPath_DirectoryEmpty() {
        // 准备参数
        String name = "test.jpg";
        String directory = "";
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = true;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = true;

        // 调用
        String path = fileService.generateUploadPath(name, directory);

        // 断言
        // 格式为：yyyyMMdd/{时间戳+随机数}/test.jpg
        assertTrue(path.matches("\\d{8}/\\d+/test\\.jpg"));
    }

    @Test
    public void testCreateFileWithInfo_uniquePathAndDetectedType() throws Exception {
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = false;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = false;
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.upload(same(content), anyString(), eq("image/jpeg")))
                .thenAnswer(invocation -> "https://example.com/" + invocation.getArgument(1));

        FileDO first = fileService.createFileWithInfo(content, "report.jpg", "docs", "text/html");
        FileDO second = fileService.createFileWithInfo(content, "report.jpg", "docs", "text/html");

        assertNotEquals(first.getPath(), second.getPath());
        assertTrue(first.getPath().matches("docs/[a-f0-9]{32}/report\\.jpg"));
        assertEquals("report.jpg", first.getName());
        assertEquals("image/jpeg", first.getType());
        assertEquals(10L, first.getConfigId());
        assertNotNull(first.getId());
        FileDO stored = fileMapper.selectById(first.getId());
        AssertUtils.assertPojoEquals(first, stored, "createTime", "updateTime", "deleted");
        assertFalse(stored.getDeleted());
        assertNotNull(stored.getCreateTime());
        assertNotNull(stored.getUpdateTime());
        assertEquals(2L, fileMapper.selectCount());
    }

    @Test
    public void testCreateFileWithInfo_registrationFailureDeletesOnlyUploadedObject() throws Exception {
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        FileClient client = mock(FileClient.class);
        FileMapper failingMapper = mock(FileMapper.class);
        FileServiceImpl service = fileServiceWithMapper(failingMapper);
        IllegalStateException registrationFailure = new IllegalStateException("文件登记失败");
        AtomicReference<String> uploadedPath = new AtomicReference<>();
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.upload(same(content), anyString(), eq("image/jpeg"))).thenAnswer(invocation -> {
            uploadedPath.set(invocation.getArgument(1));
            return "https://example.test/report.jpg";
        });
        when(failingMapper.insert(any(FileDO.class))).thenThrow(registrationFailure);

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> service.createFileWithInfo(content, "report.jpg", "docs", "image/jpeg"));

        assertSame(registrationFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        assertTrue(uploadedPath.get().matches("docs/\\d{8}/[a-f0-9]{32}/report\\.jpg"));
        var order = inOrder(client, failingMapper);
        order.verify(client).upload(same(content), eq(uploadedPath.get()), eq("image/jpeg"));
        order.verify(failingMapper).insert(argThat((FileDO file) -> uploadedPath.get().equals(file.getPath())));
        order.verify(client).delete(uploadedPath.get());
        verify(client, times(1)).delete(anyString());
        assertEquals(0L, fileMapper.selectCount());
    }

    @Test
    public void testCreateFileWithInfo_cleanupFailurePreservesRegistrationFailure() throws Exception {
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        FileClient client = mock(FileClient.class);
        FileMapper failingMapper = mock(FileMapper.class);
        FileServiceImpl service = fileServiceWithMapper(failingMapper);
        IllegalStateException registrationFailure = new IllegalStateException("文件登记失败");
        Exception cleanupFailure = new Exception("对象删除失败");
        AtomicReference<String> uploadedPath = new AtomicReference<>();
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.upload(same(content), anyString(), eq("image/jpeg"))).thenAnswer(invocation -> {
            uploadedPath.set(invocation.getArgument(1));
            return "https://example.test/report.jpg";
        });
        when(failingMapper.insert(any(FileDO.class))).thenThrow(registrationFailure);
        doThrow(cleanupFailure).when(client).delete(anyString());

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> service.createFileWithInfo(content, "report.jpg", "docs", "image/jpeg"));

        assertSame(registrationFailure, actual);
        assertArrayEquals(new Throwable[]{cleanupFailure}, actual.getSuppressed());
        verify(client).delete(uploadedPath.get());
        assertEquals(0L, fileMapper.selectCount());
    }

    @Test
    public void testCreateFile_legacyRegistrationFailureDoesNotDeleteSharedPath() throws Exception {
        byte[] content = ResourceUtil.readBytes("file/erweima.jpg");
        FileClient client = mock(FileClient.class);
        FileMapper failingMapper = mock(FileMapper.class);
        FileServiceImpl service = fileServiceWithMapper(failingMapper);
        IllegalStateException registrationFailure = new IllegalStateException("文件登记失败");
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = false;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = false;
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.upload(same(content), eq("docs/report.jpg"), eq("image/jpeg")))
                .thenReturn("https://example.test/report.jpg");
        when(failingMapper.insert(any(FileDO.class))).thenThrow(registrationFailure);

        assertSame(registrationFailure, assertThrows(IllegalStateException.class,
                () -> service.createFile(content, "report.jpg", "docs", "image/jpeg")));

        verify(client, never()).delete(anyString());
    }

    private FileServiceImpl fileServiceWithMapper(FileMapper mapper) {
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileConfigService", fileConfigService);
        ReflectionTestUtils.setField(service, "fileMapper", mapper);
        return service;
    }

    @Test
    public void testCreateFileWithInfo_rejectsEmptyAndFakeImage() {
        assertServiceException(() -> fileService.createFileWithInfo(new byte[0], "report.jpg", "docs", null),
                FILE_IS_EMPTY);
        byte[] content = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertServiceException(() -> fileService.createFileWithInfo(content, "report.jpg", "docs", "image/jpeg"),
                FILE_TYPE_NOT_ALLOWED);

        verifyNoInteractions(fileConfigService);
        assertEquals(0L, fileMapper.selectCount());
    }

    @Test
    public void testPresignPutUrlWithInfo_uniquePath() {
        FileServiceImpl.PATH_PREFIX_DATE_ENABLE = false;
        FileServiceImpl.PATH_SUFFIX_TIMESTAMP_ENABLE = false;
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.presignPutUrl(anyString())).thenReturn("upload-url");
        when(client.presignGetUrl(anyString(), isNull())).thenReturn("visit-url");

        FilePresignedUrlRespVO first = fileService.presignPutUrlWithInfo("report.pdf", "docs");
        FilePresignedUrlRespVO second = fileService.presignPutUrlWithInfo("report.pdf", "docs");

        assertNotEquals(first.getPath(), second.getPath());
        assertTrue(first.getPath().matches("docs/[a-f0-9]{32}/report\\.pdf"));
        assertEquals(10L, first.getConfigId());
        assertEquals("upload-url", first.getUploadUrl());
        assertEquals("visit-url", first.getUrl());
    }

    @Test
    public void testPresignPutUrlWithInfo_rejectsInvalidPathAndType() {
        assertServiceException(() -> fileService.presignPutUrlWithInfo("report.js", "docs"), FILE_TYPE_NOT_ALLOWED);
        assertServiceException(() -> fileService.presignPutUrlWithInfo("../report.pdf", "docs"), FILE_PATH_INVALID);
        assertServiceException(() -> fileService.presignPutUrlWithInfo("report.pdf", "../docs"), FILE_PATH_INVALID);

        verifyNoInteractions(fileConfigService);
    }

    @Test
    public void testGetFileAccessUrl_usesOriginalConfig() {
        FileDO file = randomPojo(FileDO.class, o -> o.setConfigId(10L).setPath("docs/report.pdf"));
        fileMapper.insert(file);
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getFileClient(10L)).thenReturn(client);
        when(client.presignGetUrl("docs/report.pdf", 300)).thenReturn("https://example.com/report.pdf?signature=valid");

        assertEquals("https://example.com/report.pdf?signature=valid", fileService.getFileAccessUrl(file.getId(), 300));
        verify(fileConfigService).getFileClient(10L);
        verify(fileConfigService, never()).getMasterFileClient();
    }

    @Test
    public void testGetFileAccessUrl_fallsBackForUnsupportedSigning() {
        FileDO file = randomPojo(FileDO.class, o -> o.setConfigId(10L).setPath("docs/report.pdf")
                .setUrl("https://example.com/report.pdf"));
        fileMapper.insert(file);
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getFileClient(10L)).thenReturn(client);
        when(client.presignGetUrl("docs/report.pdf", null)).thenThrow(new UnsupportedOperationException());

        assertEquals(file.getUrl(), fileService.getFileAccessUrl(file.getId(), null));
    }

    @Test
    public void testGetFileAccessUrl_signingFailureIsNotHidden() {
        FileDO file = randomPojo(FileDO.class, o -> o.setConfigId(10L).setPath("docs/report.pdf"));
        fileMapper.insert(file);
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getFileClient(10L)).thenReturn(client);
        when(client.presignGetUrl("docs/report.pdf", null)).thenThrow(new IllegalStateException("signing failed"));

        assertThrows(IllegalStateException.class, () -> fileService.getFileAccessUrl(file.getId(), null));
    }

    @Test
    public void testGetFileAccessUrl_notExists() {
        assertServiceException(() -> fileService.getFileAccessUrl(randomLongId(), null), FILE_NOT_EXISTS);
        verifyNoInteractions(fileConfigService);
    }

    @Test
    public void testCreateFile_success_jar() throws Exception {
        byte[] content = createJdbcJar();
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.upload(same(content), anyString(), eq("application/java-archive")))
                .thenReturn("https://example.com/driver.jar");

        String url = fileService.createFile(content, "driver.jar", null, "application/octet-stream");

        assertEquals("https://example.com/driver.jar", url);
        FileDO file = fileMapper.selectOne(FileDO::getUrl, url);
        assertEquals("application/java-archive", file.getType());
        assertEquals(content.length, file.getSize());
    }

    @Test
    public void testCreateFileWithInfo_success_jar() throws Exception {
        byte[] content = createJdbcJar();
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.upload(same(content), anyString(), eq("application/java-archive")))
                .thenReturn("https://example.com/driver.jar");

        FileDO file = fileService.createFileWithInfo(content, "driver.jar", "drivers", "application/octet-stream");

        assertNotNull(file.getId());
        assertEquals("application/java-archive", file.getType());
        assertTrue(file.getPath().startsWith("drivers/"));
        assertTrue(file.getPath().endsWith("/driver.jar"));
        FileDO stored = fileMapper.selectById(file.getId());
        AssertUtils.assertPojoEquals(file, stored, "createTime", "updateTime", "deleted");
        assertFalse(stored.getDeleted());
        assertNotNull(stored.getCreateTime());
        assertNotNull(stored.getUpdateTime());
    }

    @Test
    public void testPresignPutUrlAndCreateFile_jar() {
        FileClient client = mock(FileClient.class);
        when(fileConfigService.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(10L);
        when(client.presignPutUrl(anyString())).thenReturn("upload-url");
        when(client.presignGetUrl(anyString(), isNull())).thenReturn("https://example.com/driver.jar");

        FilePresignedUrlRespVO regular = fileService.presignPutUrl("driver.jar", "drivers");
        FilePresignedUrlRespVO unique = fileService.presignPutUrlWithInfo("driver.jar", "drivers");
        assertEquals("upload-url", regular.getUploadUrl());
        assertEquals("upload-url", unique.getUploadUrl());
        assertNotEquals(regular.getPath(), unique.getPath());
        FileCreateReqVO reqVO = randomPojo(FileCreateReqVO.class, o -> {
            o.setConfigId(unique.getConfigId());
            o.setPath(unique.getPath());
            o.setName("driver.jar");
            o.setUrl(unique.getUrl());
            o.setType("application/octet-stream");
        });

        Long id = fileService.createFile(reqVO);

        assertEquals("application/java-archive", fileMapper.selectById(id).getType());
    }

    private static byte[] createJdbcJar() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            jar.write("com.example.Driver".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

}
