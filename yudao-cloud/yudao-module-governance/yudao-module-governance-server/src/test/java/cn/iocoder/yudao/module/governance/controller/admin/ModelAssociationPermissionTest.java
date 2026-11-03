package cn.iocoder.yudao.module.governance.controller.admin;

import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.StandardCodeSetController;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.StandardElementController;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelAssociationPermissionTest {
    public static class Permissions {
        private final Set<String> granted;
        Permissions(String... granted) { this.granted = Set.of(granted); }
        public boolean hasPermission(String permission) { return granted.contains(permission); }
    }

    @Test
    void bothEndpointsRequireTheirStandardPermissionAndModelPermission() throws Exception {
        verify(StandardElementController.class, "getStandardElementModelFields", "governance:standard-element:query");
        verify(StandardCodeSetController.class, "getModelFields", "governance:standard-code-set:query");
    }

    private void verify(Class<?> controller, String method, String standardPermission) throws Exception {
        String rule = controller.getMethod(method, Integer.class).getAnnotation(PreAuthorize.class).value();
        var expression = new SpelExpressionParser().parseExpression(rule);
        var context = new StandardEvaluationContext();
        for (String[] permissions : new String[][] {{}, {standardPermission}, {"modeling:model:query"}}) {
            context.setBeanResolver((ctx, name) -> new Permissions(permissions));
            assertFalse(Boolean.TRUE.equals(expression.getValue(context, Boolean.class)));
        }
        context.setBeanResolver((ctx, name) -> new Permissions(standardPermission, "modeling:model:query"));
        assertTrue(Boolean.TRUE.equals(expression.getValue(context, Boolean.class)));
    }
}
