package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.config.DolphinSchedulerProperties;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.invocation.InvocationOnMock;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Independent operation snapshot from the deployed 3.4.2 OpenAPI, not Java/Javadoc extraction.
 * These tests invoke reachable public APIs and observe the HTTP boundary without calling DS.
 * Body encoding and response semantics are verified by dedicated HTTP contract tests.
 */
class DolphinSchedulerApiCoverageTest {

    private static final String API_PACKAGE = "cn.iocoder.yudao.framework.dolphinscheduler.api";

    @TestFactory
    Stream<DynamicTest> exposesEveryDocumentedOperationWithItsHttpMethodAndPath() throws Exception {
        JSONObject snapshot;
        try (var input = getClass().getResourceAsStream("/dolphinscheduler/openapi-3.4.2-operations.json")) {
            assertThat(input).isNotNull();
            snapshot = JSONUtil.parseObj(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(snapshot.getStr("version")).isEqualTo("3.4.2");
        var operations = snapshot.getJSONArray("operations").toList(JSONObject.class);
        assertThat(operations).hasSize(243);
        assertThat(operations.stream().map(operation -> operation.getStr("operationId")).toList())
                .doesNotHaveDuplicates();
        DolphinSchedulerClient client = new DolphinSchedulerClient(new DolphinSchedulerProperties());
        List<Class<?>> groups = new ArrayList<>();
        for (Method entry : DolphinSchedulerClient.class.getMethods()) {
            if (entry.getParameterCount() == 0 && API_PACKAGE.equals(entry.getReturnType().getPackageName())) {
                Object group = entry.invoke(client);
                assertThat(group).isNotNull();
                groups.add(group.getClass());
            }
        }
        assertThat(groups).hasSize(33).doesNotHaveDuplicates();
        return operations.stream().map(operation -> DynamicTest.dynamicTest(
                operation.getStr("method") + " " + operation.getStr("path"),
                () -> assertOperation(groups, operation)));
    }

    private void assertOperation(List<Class<?>> groups, JSONObject operation) throws Exception {
        List<InvocationOnMock> requests = new ArrayList<>();
        DolphinSchedulerHttp transport = mock(DolphinSchedulerHttp.class, invocation -> {
            requests.add(invocation);
            return invocation.getMethod().getReturnType() == byte[].class ? new byte[0] : new JSONObject();
        });
        String operationId = operation.getStr("operationId");
        Method method = groups.stream().flatMap(group -> Arrays.stream(group.getMethods()))
                .filter(candidate -> candidate.getName().equals(operationId))
                .filter(candidate -> !candidate.isAnnotationPresent(Deprecated.class))
                .max(Comparator.comparingInt(Method::getParameterCount))
                .orElseThrow(() -> new AssertionError("No reachable public API for " + operationId));
        Object api = method.getDeclaringClass().getConstructor(DolphinSchedulerHttp.class).newInstance(transport);
        Object[] arguments = new Object[method.getParameterCount()];
        for (int index = 0; index < arguments.length; index++) {
            Class<?> type = method.getParameterTypes()[index];
            arguments[index] = type == String.class && method.getDeclaringClass().getSimpleName().equals("DataSourceApi")
                    && (operationId.equals("createDataSource")
                    || operationId.equals("connectDataSource") || operationId.equals("updateDataSource"))
                    ? "{\"name\":\"sample\"}" : sample(type);
        }
        method.invoke(api, arguments);
        assertThat(requests).as(operationId).hasSize(1);
        InvocationOnMock request = requests.getFirst();
        String transportMethod = request.getMethod().getName();
        boolean explicitMethod = transportMethod.equals("download") || transportMethod.equals("multipart");
        String actualMethod = explicitMethod ? request.getArgument(0) : switch (transportMethod) {
            case "get" -> "GET";
            case "delete" -> "DELETE";
            case "postForm", "postJson" -> "POST";
            case "putForm", "putJson" -> "PUT";
            default -> throw new AssertionError("Unexpected transport method: " + transportMethod);
        };
        String expectedPath = operation.getStr("path");
        for (var parameter : operation.getJSONObject("pathParameters").entrySet()) {
            expectedPath = expectedPath.replace("{" + parameter.getKey() + "}",
                    "integer".equals(parameter.getValue()) ? "41" : "sample");
        }
        // Five OpenAPI operations omit the projectCode path parameter schema; their route still declares it.
        expectedPath = expectedPath.replace("{projectCode}", "41");
        assertThat(actualMethod).as(operationId + " HTTP method").isEqualTo(operation.getStr("method"));
        assertThat(request.<String>getArgument(explicitMethod ? 1 : 0))
                .as(operationId + " path").isEqualTo(expectedPath);
    }

    private Object sample(Class<?> type) throws Exception {
        if (type == String.class) return "sample";
        if (type == Integer.class || type == int.class) return 41;
        if (type == Long.class || type == long.class) return 41L;
        if (type == Boolean.class || type == boolean.class) return true;
        if (type == File.class) return new File("sample.jar");
        if (type == Object.class || type == List.class) return List.of("sample");
        if (type == Map.class) return Map.of("name", "sample");
        Object request = type.getConstructor().newInstance();
        for (Method setter : type.getMethods()) {
            if (setter.getName().startsWith("set") && setter.getParameterCount() == 1) {
                setter.invoke(request, sample(setter.getParameterTypes()[0]));
            }
        }
        return request;
    }
}
