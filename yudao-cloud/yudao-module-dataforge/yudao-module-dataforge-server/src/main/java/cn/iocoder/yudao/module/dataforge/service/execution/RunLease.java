package cn.iocoder.yudao.module.dataforge.service.execution;

import java.time.LocalDateTime;

/** 某次领取的 fencing 身份；只有数据库时间未过期的持有者可以写入。 */
public record RunLease(Long recordId, String token, LocalDateTime leaseUntil) {

}
