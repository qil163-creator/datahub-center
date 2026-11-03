package cn.iocoder.yudao.framework.dolphinscheduler.request;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * createUser 请求参数。
 */
public class CreateUserRequest {

    private String userName;

    private String userPassword;

    private Integer tenantId;

    private String queue;

    private String email;

    private String phone;

    private Integer state;

    /**
     * 返回用户名。
     */
    public String getUserName() {
        return userName;
    }

    /**
     * 设置用户名。
     */
    public CreateUserRequest setUserName(String userName) {
        this.userName = userName;
        return this;
    }

    /**
     * 返回用户密码。
     */
    public String getUserPassword() {
        return userPassword;
    }

    /**
     * 设置用户密码。
     */
    public CreateUserRequest setUserPassword(String userPassword) {
        this.userPassword = userPassword;
        return this;
    }

    /**
     * 返回执行租户编号。
     */
    public Integer getTenantId() {
        return tenantId;
    }

    /**
     * 设置执行租户编号。
     */
    public CreateUserRequest setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    /**
     * 返回队列。
     */
    public String getQueue() {
        return queue;
    }

    /**
     * 设置队列。
     */
    public CreateUserRequest setQueue(String queue) {
        this.queue = queue;
        return this;
    }

    /**
     * 返回邮箱。
     */
    public String getEmail() {
        return email;
    }

    /**
     * 设置邮箱。
     */
    public CreateUserRequest setEmail(String email) {
        this.email = email;
        return this;
    }

    /**
     * 返回手机号。
     */
    public String getPhone() {
        return phone;
    }

    /**
     * 设置手机号。
     */
    public CreateUserRequest setPhone(String phone) {
        this.phone = phone;
        return this;
    }

    /**
     * 返回状态。
     */
    public Integer getState() {
        return state;
    }

    /**
     * 设置状态。
     */
    public CreateUserRequest setState(Integer state) {
        this.state = state;
        return this;
    }

    /**
     * 将请求参数转换为远端接口使用的参数映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (userName != null) {
            params.put("userName", userName);
        }
        if (userPassword != null) {
            params.put("userPassword", userPassword);
        }
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        if (queue != null) {
            params.put("queue", queue);
        }
        if (email != null) {
            params.put("email", email);
        }
        if (phone != null) {
            params.put("phone", phone);
        }
        if (state != null) {
            params.put("state", state);
        }
        return params;
    }

}
