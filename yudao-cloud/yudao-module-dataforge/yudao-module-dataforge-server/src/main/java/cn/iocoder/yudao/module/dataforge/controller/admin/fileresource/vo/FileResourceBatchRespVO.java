package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FileResourceBatchRespVO {

    private List<String> succeededPaths = new ArrayList<>();

    private List<Failure> failures = new ArrayList<>();

    public record Failure(String path, String message) {

    }
}
