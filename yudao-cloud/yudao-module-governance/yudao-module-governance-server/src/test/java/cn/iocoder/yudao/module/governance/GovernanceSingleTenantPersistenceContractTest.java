package cn.iocoder.yudao.module.governance;

import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceSingleTenantPersistenceContractTest {
    private static final Pattern TENANT_COLUMN = Pattern.compile("(?i)\\btenant_id\\b|#\\{[^}]*tenant");

    @Test
    void allGovernanceEntitiesAndMapperStatementsUseBusinessIdentifiers() throws Exception {
        var resources = new PathMatchingResourcePatternResolver();
        var metadata = new CachingMetadataReaderFactory(resources);
        int entityCount = 0;
        int sqlCount = 0;
        for (var resource : resources.getResources("classpath*:cn/iocoder/yudao/module/governance/dal/**/*.class")) {
            var className = metadata.getMetadataReader(resource).getClassMetadata().getClassName();
            if (className.contains("$")) continue;
            var type = Class.forName(className, false, getClass().getClassLoader());
            var table = type.getAnnotation(TableName.class);
            if (table != null && table.value().startsWith("dh_")) {
                assertThat(ReflectionUtils.findField(type, "tenantId")).as(className).isNull();
                entityCount++;
            }
            for (var method : type.getDeclaredMethods()) {
                for (Class<? extends Annotation> annotation : List.of(Select.class, Insert.class, Update.class, Delete.class)) {
                    var value = method.getAnnotation(annotation);
                    if (value == null) continue;
                    String sql = String.join(" ", (String[]) annotation.getMethod("value").invoke(value));
                    assertThat(TENANT_COLUMN.matcher(sql).find()).as(type.getSimpleName() + "." + method.getName()).isFalse();
                    sqlCount++;
                }
            }
        }
        assertThat(entityCount).isGreaterThan(50);
        assertThat(sqlCount).isGreaterThan(50);
    }

}
