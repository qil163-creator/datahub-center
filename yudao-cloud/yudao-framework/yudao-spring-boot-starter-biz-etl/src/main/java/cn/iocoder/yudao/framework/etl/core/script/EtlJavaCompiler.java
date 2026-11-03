package cn.iocoder.yudao.framework.etl.core.script;


import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 脚本内存编译器：将脚本源码编译为 Class 并加载（JDK 环境）
 *
 * @author Li
 */
public class EtlJavaCompiler {

    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("package\\s+([\\w.]+)\\s*;");

    /**
     * 编译检查：返回错误信息列表（空列表表示通过）
     */
    public static List<String> check(String source) {
        requireWorker();
        List<String> errors = new ArrayList<>();
        try {
            compile(source);
        } catch (IllegalStateException ex) {
            for (String line : ex.getMessage().split("\\R")) {
                if (errors.size() >= 20) {
                    break;
                }
                errors.add(line.length() > 80 ? line.substring(0, 80) : line);
            }
        }
        return errors;
    }

    /**
     * 编译并实例化脚本（脚本类需实现 {@link EtlJavaScript}）
     */
    public static EtlJavaScript compileScript(String source) {
        requireWorker();
        Class<?> clazz = compile(source);
        if (!EtlJavaScript.class.isAssignableFrom(clazz)) {
            throw new IllegalStateException(String.format(
                    "脚本类需实现 %s 接口", EtlJavaScript.class.getName()));
        }
        try {
            return (EtlJavaScript) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception ex) {
            throw new IllegalStateException("脚本类实例化失败");
        }
    }

    /** 兼容原站接口的实例只允许在已经隔离的 worker 中构造。 */
    public static tech.qiantong.qdata.common.core.base.EtlBase compileQdataScript(String source) {
        requireWorker();
        Class<?> clazz = compile(source);
        if (!tech.qiantong.qdata.common.core.base.EtlBase.class.isAssignableFrom(clazz)) throw new IllegalStateException("脚本类需实现原站 EtlBase 接口");
        try { return (tech.qiantong.qdata.common.core.base.EtlBase) clazz.getDeclaredConstructor().newInstance(); }
        catch (Exception failure) { throw new IllegalStateException("脚本类实例化失败"); }
    }

    /**
     * 内存编译源码并加载类
     */
    public static Class<?> compile(String source) {
        requireWorker();
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("脚本代码不能为空");
        }
        String className = resolveClassName(source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("无法获取 JavaCompiler，请确认运行环境是 JDK 而不是 JRE");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> classBytes = new HashMap<>();
        try (StandardJavaFileManager standardManager =
                     compiler.getStandardFileManager(diagnostics, null, null);
             JavaFileManager fileManager = new ForwardingJavaFileManager<>(standardManager) {
                 /**
                  * 返回Java文件对应输出。
                  */
                 @Override
                 public JavaFileObject getJavaFileForOutput(Location location, String name,
                         JavaFileObject.Kind kind, FileObject sibling) {
                     ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                     classBytes.put(name, buffer);
                     return new SimpleJavaFileObject(
                             URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind) {
                         /**
                          * 返回缓冲区。
                          */
                         @Override
                         public OutputStream openOutputStream() {
                             return buffer;
                         }
                     };
                 }
             }) {
            JavaFileObject sourceFile = new SimpleJavaFileObject(
                    URI.create("mem:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE) {
                /**
                 * 返回源端。
                 */
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            // 仅使用固定工作进程类路径；注解处理器绝不能处理用户源码。
            List<String> options = List.of("-classpath", System.getProperty("java.class.path"),
                    "-encoding", "UTF-8", "-proc:none", "-Xmaxerrs", "20", "-Xmaxwarns", "20");
            boolean success = compiler.getTask(null, fileManager, diagnostics, options, null,
                    List.of(sourceFile)).call();
            if (!success) {
                StringBuilder message = new StringBuilder("编译失败：");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        message.append(String.format("%n第 %s 行：%s",
                                diagnostic.getLineNumber(), diagnostic.getCode()));
                    }
                }
                throw new IllegalStateException(message.toString());
            }
            ClassLoader loader = new ClassLoader(EtlJavaScript.class.getClassLoader()) {
                /**
                 * 查找类。
                 */
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    ByteArrayOutputStream buffer = classBytes.get(name);
                    if (buffer == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] bytes = buffer.toByteArray();
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            return loader.loadClass(className);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("编译异常");
        }
    }

    /**
     * 从源码解析全限定类名
     */
    public static String sourceClassName(String source) {
        if (source == null || source.isBlank() || source.length() > 1024 * 1024) throw new IllegalArgumentException("脚本源码为空或超过上限");
        return resolveClassName(source);
    }
    private static String resolveClassName(String source) {
        Matcher classMatcher = CLASS_NAME_PATTERN.matcher(source);
        if (!classMatcher.find()) {
            throw new IllegalStateException("未找到类定义，请检查脚本代码");
        }
        String className = classMatcher.group(1);
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
        return packageMatcher.find() ? packageMatcher.group(1) + "." + className : className;
    }

    /**
     * 校验并取得工作进程。
     */
    private static void requireWorker() {
        if (!EtlScriptWorker.isWorkerProcess()) {
            throw new IllegalStateException("Java 编译仅允许在受控 ETL worker 内执行，请使用 EtlScriptRunner");
        }
    }

}
