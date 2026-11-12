package cn.iocoder.yudao.module.governance.dal.mysql.security;

import lombok.Data;

/**
 * 窗口内某用户的敏感访问统计（只读投影）。
 */
@Data
public class SafetyAccessUserStat {

    private Long userId;
    private String userName;
    private Integer total;
    private Integer plaintext;
    private Integer tables;
    private Integer offHours;
}
