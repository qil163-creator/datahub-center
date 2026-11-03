package cn.iocoder.yudao.module.supply.service.apitest;

import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestCatalogRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestDetailRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestSendReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.apitest.vo.ApiTestSendRespVO;

public interface ApiTestService {

    /**
     * 获得平台内已上线 API 测试目录。
     *
     * @return 目录
     */
    ApiTestCatalogRespVO getCatalog();

    /**
     * 获得已发布 API 的最小测试定义。
     *
     * @param id 记录编号
     * @return API测试详情响应数据
     */
    ApiTestDetailRespVO getDetail(Long id);

    /**
     * 发送登记 API 或公开 HTTP 地址的真实测试请求。
     *
     * @param reqVO 请求参数
     * @return API测试发送响应数据
     */
    ApiTestSendRespVO send(ApiTestSendReqVO reqVO);
}
