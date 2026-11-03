package cn.iocoder.yudao.module.dataforge.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopyNameSupportTest {

    @Test
    void nextAppendsCopySuffixAndIncrements() {
        Set<String> existing = new HashSet<>();
        existing.add("订单同步_副本");
        existing.add("订单同步_副本2");
        String name = CopyNameSupport.next("订单同步", 40, existing::contains);
        assertEquals("订单同步_副本3", name);
    }

    @Test
    void nextUsesFirstCopyWhenFree() {
        String name = CopyNameSupport.next("订单同步", 40, ignored -> false);
        assertEquals("订单同步_副本", name);
    }

}
