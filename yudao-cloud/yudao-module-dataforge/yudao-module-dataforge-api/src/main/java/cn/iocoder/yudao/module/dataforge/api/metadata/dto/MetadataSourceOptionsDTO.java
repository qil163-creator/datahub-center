package cn.iocoder.yudao.module.dataforge.api.metadata.dto;

import lombok.Data;

import java.util.List;

@Data
public class MetadataSourceOptionsDTO {

    private List<ConnectionOption> connections;

    private List<SystemOption> systems;

    @Data
    public static class ConnectionOption {

        private Long id;

        private String name;

        private Integer collectType;

        private String dbType;

        private String host;

        private String port;

        private String username;
    }

    @Data
    public static class SystemOption {

        private Long id;

        private String name;
    }
}
