package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import lombok.Data;

import java.util.List;

@Data
public class BusinessEntityOptionsRespVO {

    private List<BusinessEntityRespVO.Summary> entities;

    private List<Long> unavailableProcessEventIds;

    private List<Long> unavailablePredecessorEntityIds;
}
