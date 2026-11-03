package cn.iocoder.yudao.module.asset.service.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandMatchDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandReplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandMatchMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDemandReplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Slf4j
@Service
@Validated
public class MarketDemandServiceImpl implements MarketDemandService {

    private static final Set<Integer> HALL_STATUSES = Set.of(
            MarketDemandDO.STATUS_PUBLISHED, MarketDemandDO.STATUS_MATCHING,
            MarketDemandDO.STATUS_MATCHED, MarketDemandDO.STATUS_APPLYING);

    private static final Set<Integer> CLOSE_STATUSES = Set.of(
            MarketDemandDO.STATUS_CLOSED, MarketDemandDO.STATUS_CANCELLED, MarketDemandDO.STATUS_REJECTED);

    private static final Set<Integer> TERMINAL_STATUSES = Set.of(
            MarketDemandDO.STATUS_CLOSED, MarketDemandDO.STATUS_CANCELLED, MarketDemandDO.STATUS_REJECTED);

    @Resource
    private MarketDemandMapper demandMapper;

    @Resource
    private MarketDemandMatchMapper matchMapper;

    @Resource
    private MarketDemandReplyMapper replyMapper;

    @Resource
    private DataAssetMapper assetMapper;

    @Resource
    private MarketApplyService applyService;

    @Resource
    private MarketResourceApplyMapper applyMapper;

    @Resource
    private MarketDeliveryService deliveryService;

    @Resource
    private MarketAccessService accessService;

    @Resource
    private MarketResourceService resourceService;

    @Resource
    private MarketDisplayHelper displayHelper;

    @Resource
    private MarketObjectGuard objectGuard;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private MarketNotifyHelper notifyHelper;

    /**
     * 创建数据需求。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDemand(MarketDemandSaveReqVO reqVO) {
        Long userId = objectGuard.requireUserId();
        MarketDemandDO demand = BeanUtils.toBean(reqVO, MarketDemandDO.class);
        demand.setDemandNo(nextDemandNo());
        demand.setApplyUserId(userId);
        demand.setApplyDeptId(resolveDeptId(reqVO.getApplyDeptId()));
        fillContact(demand);
        if (demand.getExpectedDelivery() == null) {
            demand.setExpectedDelivery(MarketDemandDO.DELIVERY_ANY);
        }
        if (demand.getUrgency() == null) {
            demand.setUrgency(MarketDemandDO.URGENCY_MEDIUM);
        }
        boolean publish = Boolean.TRUE.equals(reqVO.getPublish());
        demand.setStatus(publish ? MarketDemandDO.STATUS_PUBLISHED : MarketDemandDO.STATUS_DRAFT);
        if (publish) {
            demand.setPublishTime(LocalDateTime.now());
        }
        demandMapper.insert(demand);
        return demand.getId();
    }

    /**
     * 修改数据需求。
     */
    @Override
    public void updateDemand(MarketDemandSaveReqVO reqVO) {
        MarketDemandDO demand = validateOwner(reqVO.getId());
        if (!Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_DRAFT)
                && !Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_PUBLISHED)
                && !Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_MATCHING)) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        MarketDemandDO update = BeanUtils.toBean(reqVO, MarketDemandDO.class);
        update.setApplyDeptId(resolveDeptId(reqVO.getApplyDeptId()));
        // 编辑只写表单字段，不把初读的状态/负责人/发布时间覆盖到并发更新后的行。
        if (demandMapper.update(update, demandMutationState(demand)) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
    }

    /**
     * 删除草稿需求。
     */
    @Override
    public void deleteDemand(Long id) {
        MarketDemandDO demand = validateOwner(id);
        if (!Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_DRAFT)
                && !Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_CANCELLED)) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        // 沿用 BaseMapper 的逻辑删除，同时复验删除资格，不能删除已被发布/转交的需求。
        if (demandMapper.delete(demandMutationState(demand)) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
    }

    /**
     * 发布到需求大厅。
     */
    @Override
    public void publishDemand(Long id) {
        MarketDemandDO demand = validateOwner(id);
        if (!Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_DRAFT)) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        if (demandMapper.update(null, demandMutationState(demand)
                .set(MarketDemandDO::getStatus, MarketDemandDO.STATUS_PUBLISHED)
                .setSql("publish_time = clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
    }

    /**
     * 关闭、撤销或驳回需求。
     */
    @Override
    public void closeDemand(MarketDemandCloseReqVO reqVO) {
        MarketDemandDO demand = validateExists(reqVO.getId());
        if (reqVO.getStatus() == null || !CLOSE_STATUSES.contains(reqVO.getStatus())) {
            throw exception(MARKET_DEMAND_CLOSE_STATUS_INVALID);
        }
        boolean owner = Objects.equals(demand.getApplyUserId(), objectGuard.requireUserId());
        if (Objects.equals(reqVO.getStatus(), MarketDemandDO.STATUS_REJECTED) && owner) {
            throw exception(MARKET_DEMAND_FORBIDDEN);
        }
        if (Objects.equals(reqVO.getStatus(), MarketDemandDO.STATUS_CANCELLED) && !owner) {
            throw exception(MARKET_DEMAND_FORBIDDEN);
        }
        if (Objects.equals(reqVO.getStatus(), MarketDemandDO.STATUS_CLOSED) && !owner
                && !objectGuard.hasPermission(MarketObjectGuard.PERM_DEMAND_MATCH)) {
            throw exception(MARKET_DEMAND_FORBIDDEN);
        }
        if (demand.getStatus() == null || (!Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_DRAFT)
                && !HALL_STATUSES.contains(demand.getStatus()))) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        if (demandMapper.update(null, demandMutationState(demand)
                .set(MarketDemandDO::getStatus, reqVO.getStatus())
                .set(MarketDemandDO::getCloseReason, reqVO.getCloseReason())) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
    }

    /** 单条状态写入的资格快照；不依赖节点锁，不将读取时的授权沿用到已变化的行。 */
    private static LambdaUpdateWrapper<MarketDemandDO> demandMutationState(MarketDemandDO demand) {
        return new LambdaUpdateWrapper<MarketDemandDO>()
                .eq(MarketDemandDO::getId, demand.getId())
                .eq(MarketDemandDO::getStatus, demand.getStatus())
                .eq(demand.getApplyUserId() != null, MarketDemandDO::getApplyUserId, demand.getApplyUserId())
                .isNull(demand.getApplyUserId() == null, MarketDemandDO::getApplyUserId)
                .eq(demand.getApplyDeptId() != null, MarketDemandDO::getApplyDeptId, demand.getApplyDeptId())
                .isNull(demand.getApplyDeptId() == null, MarketDemandDO::getApplyDeptId)
                .eq(demand.getAcceptanceStatus() != null, MarketDemandDO::getAcceptanceStatus,
                        demand.getAcceptanceStatus())
                .isNull(demand.getAcceptanceStatus() == null, MarketDemandDO::getAcceptanceStatus)
                .eq(demand.getAcceptedApplyId() != null, MarketDemandDO::getAcceptedApplyId,
                        demand.getAcceptedApplyId())
                .isNull(demand.getAcceptedApplyId() == null, MarketDemandDO::getAcceptedApplyId)
                .eq(demand.getAcceptedTime() != null, MarketDemandDO::getAcceptedTime, demand.getAcceptedTime())
                .isNull(demand.getAcceptedTime() == null, MarketDemandDO::getAcceptedTime);
    }

    /**
     * 需求详情。
     */
    @Override
    public MarketDemandRespVO getDemand(Long id) {
        MarketDemandDO demand = validateExists(id);
        objectGuard.assertCanViewDemand(demand);
        MarketDemandRespVO vo = toResp(demand, true);
        vo.setMatches(matchMapper.selectListByDemandId(id).stream().map(this::toMatchResp).toList());
        vo.setReplies(replyMapper.selectListByDemandId(id).stream().map(this::toReplyResp).toList());
        vo.setMatchCount(vo.getMatches().size());
        vo.setReplyCount(vo.getReplies().size());
        return vo;
    }

    /**
     * 需求分页。
     */
    @Override
    public PageResult<MarketDemandRespVO> getDemandPage(MarketDemandPageReqVO reqVO) {
        Long selfUserId = null;
        Collection<Integer> hallStatuses = null;
        if (Objects.equals(reqVO.getIfSelf(), 1)) {
            selfUserId = objectGuard.requireUserId();
        } else {
            if (reqVO.getStatus() != null && !HALL_STATUSES.contains(reqVO.getStatus())) {
                return PageResult.empty();
            }
            hallStatuses = reqVO.getStatus() == null ? HALL_STATUSES : List.of(reqVO.getStatus());
        }
        PageResult<MarketDemandDO> page = demandMapper.selectPage(reqVO, selfUserId, hallStatuses);
        List<Long> ids = page.getList().stream().map(MarketDemandDO::getId).toList();
        Map<Long, Long> matchCounts = matchMapper.selectListByDemandIds(ids).stream()
                .collect(Collectors.groupingBy(MarketDemandMatchDO::getDemandId, Collectors.counting()));
        return new PageResult<>(page.getList().stream().map(item -> {
            MarketDemandRespVO vo = toResp(item, false);
            vo.setMatchCount(matchCounts.getOrDefault(item.getId(), 0L).intValue());
            return vo;
        }).toList(), page.getTotal());
    }

    /**
     * 响应需求。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long replyDemand(MarketDemandReplySaveReqVO reqVO) {
        MarketDemandDO demand = validateOpen(reqVO.getDemandId());
        Long userId = objectGuard.requireUserId();
        MarketDemandReplyDO reply = BeanUtils.toBean(reqVO, MarketDemandReplyDO.class);
        reply.setReplyUserId(userId);
        if (reply.getReplyDeptId() == null) {
            reply.setReplyDeptId(SecurityFrameworkUtils.getLoginUserDeptId());
        }
        if (replyMapper.insert(reply) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        markMatching(demand);
        notifyHelper.notifyDemandReplied(demand, userId);
        return reply.getId();
    }

    /**
     * 把已有资产匹配到需求。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Long matchDemand(MarketDemandMatchSaveReqVO reqVO) {
        MarketDemandDO demand = validateOpen(reqVO.getDemandId());
        DataAssetDO asset = assetMapper.selectById(reqVO.getAssetId());
        if (asset == null) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (!Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
        }
        if (matchMapper.selectByDemandAndAsset(reqVO.getDemandId(), reqVO.getAssetId()) != null) {
            throw exception(MARKET_DEMAND_MATCH_DUPLICATE);
        }
        MarketDemandMatchDO match = new MarketDemandMatchDO();
        match.setDemandId(reqVO.getDemandId());
        match.setAssetId(reqVO.getAssetId());
        match.setMatchType(MarketDemandMatchDO.TYPE_MANUAL);
        match.setMatchNote(reqVO.getMatchNote());
        match.setStatus(MarketDemandMatchDO.STATUS_PENDING);
        match.setMatcherUserId(objectGuard.requireUserId());
        // 预检查只改善提示；跨节点竞争由平台唯一索引裁决，不能重投或吞掉其他数据库异常。
        int inserted = matchMapper.insertIfAbsent(match);
        if (inserted == 0) {
            throw exception(MARKET_DEMAND_MATCH_DUPLICATE);
        }
        if (inserted != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        // 匹配 -> 资产 -> 需求；锁后不再调用权限/元数据等 RPC，仅提交本地状态并登记提交后通知。
        // 不能只重复普通 SELECT：需求 CAS 等待期间资产仍可能下架、删除或换绑。
        DataAssetDO currentAsset = lockMatchingAsset(asset);
        markMatching(demand);
        notifyHelper.notifyDemandMatched(demand, currentAsset);
        return match.getId();
    }

    /** 新增和接受匹配共用末次资产复验；调用方必须在真实事务内，且锁后不能再做远端查询。 */
    private DataAssetDO lockMatchingAsset(DataAssetDO asset) {
        DataAssetDO currentAsset = assetMapper.selectForMatching(asset.getId());
        if (currentAsset == null || Boolean.TRUE.equals(currentAsset.getDeleted())) {
            throw exception(DATA_ASSET_NOT_EXISTS);
        }
        if (!Objects.equals(currentAsset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
            throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
        }
        if (!sameMatchingAsset(asset, currentAsset)) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        return currentAsset;
    }

    /** 绑定、发布依据和供给归属必须与入口一致；浏览计数、描述等不属于匹配资格版本。 */
    private static boolean sameMatchingAsset(DataAssetDO original, DataAssetDO current) {
        return Objects.equals(original.getId(), current.getId())
                && Objects.equals(original.getAssetType(), current.getAssetType())
                && Objects.equals(original.getCatalogItemUrn(), current.getCatalogItemUrn())
                && Objects.equals(original.getMetaTableId(), current.getMetaTableId())
                && Objects.equals(original.getResourceUrn(), current.getResourceUrn())
                && Objects.equals(original.getPublishedSnapshotId(), current.getPublishedSnapshotId())
                && Objects.equals(original.getPublishedVersion(), current.getPublishedVersion())
                && Objects.equals(original.getPublishedCatalogVersionId(), current.getPublishedCatalogVersionId())
                && Objects.equals(original.getDatasourceId(), current.getDatasourceId())
                && Objects.equals(original.getDbName(), current.getDbName())
                && Objects.equals(original.getSchemaName(), current.getSchemaName())
                && Objects.equals(original.getTableName(), current.getTableName())
                && Objects.equals(original.getOwnerDeptId(), current.getOwnerDeptId())
                && Objects.equals(original.getTechLeaderUserId(), current.getTechLeaderUserId())
                && Objects.equals(original.getBusinessLeaderUserId(), current.getBusinessLeaderUserId())
                && Objects.equals(original.getProviderDeptId(), current.getProviderDeptId())
                && Objects.equals(original.getSourceDeptId(), current.getSourceDeptId())
                && Objects.equals(original.getShareAttr(), current.getShareAttr())
                && Objects.equals(original.getShareAttribute(), current.getShareAttribute())
                && Objects.equals(original.getShareCondition(), current.getShareCondition())
                && Objects.equals(original.getShareDenyReason(), current.getShareDenyReason())
                && Objects.equals(original.getTechAvailable(), current.getTechAvailable())
                && Objects.equals(original.getCatalogRevision(), current.getCatalogRevision());
    }

    /**
     * 需求方确认匹配。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void confirmMatch(MarketDemandMatchConfirmReqVO reqVO) {
        MarketDemandMatchDO match = matchMapper.selectById(reqVO.getId());
        if (match == null) {
            throw exception(MARKET_DEMAND_MATCH_NOT_EXISTS);
        }
        MarketDemandDO demand = validateOwner(match.getDemandId());
        if (demand.getStatus() == null || !HALL_STATUSES.contains(demand.getStatus())
                || !Objects.equals(match.getStatus(), MarketDemandMatchDO.STATUS_PENDING)) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        if (!Objects.equals(reqVO.getStatus(), MarketDemandMatchDO.STATUS_ACCEPTED)
                && !Objects.equals(reqVO.getStatus(), MarketDemandMatchDO.STATUS_REJECTED)) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        boolean accepted = Objects.equals(reqVO.getStatus(), MarketDemandMatchDO.STATUS_ACCEPTED);
        boolean createApply = accepted && Boolean.TRUE.equals(reqVO.getCreateApply());
        DataAssetDO asset = null;
        if (accepted) {
            asset = match.getAssetId() == null ? null : assetMapper.selectById(match.getAssetId());
            if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
                throw exception(DATA_ASSET_NOT_EXISTS);
            }
            if (!Objects.equals(asset.getStatus(), DataAssetDO.STATUS_PUBLISHED)) {
                throw exception(MARKET_APPLY_ASSET_NOT_PUBLISHED);
            }
        }
        // 原匹配关联也属于授权依据，不能只凭 id/PENDING 修改已换绑的行。
        // 此 UPDATE 先取得匹配锁，与验收的匹配 -> 申请 -> 需求锁序一致。
        if (matchMapper.update(null, matchConfirmationState(match)
                .eq(MarketDemandMatchDO::getStatus, MarketDemandMatchDO.STATUS_PENDING)
                .set(MarketDemandMatchDO::getStatus, reqVO.getStatus())
                .set(MarketDemandMatchDO::getConfirmUserId, objectGuard.requireUserId())
                .setSql("confirm_time = clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")
                .set(MarketDemandMatchDO::getConfirmNote, reqVO.getConfirmNote())) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        if (createApply) {
            MarketResourceApplyDO apply = applyService.createApplyFromDemand(
                    demand.getId(), match.getAssetId(), demand.getApplyUserId(),
                    demand.getApplyDeptId(), demand.getPurpose());
            if (matchMapper.update(null, matchConfirmationState(match)
                    .eq(MarketDemandMatchDO::getStatus, MarketDemandMatchDO.STATUS_ACCEPTED)
                    .set(MarketDemandMatchDO::getApplyId, apply.getId())) != 1) {
                throw exception(MARKET_DEMAND_STATUS_DENY);
            }
        }
        if (accepted) {
            // 匹配 -> 申请写入/回填 -> 资产 -> 需求，避免在创建/复用申请之前反向持有资产锁。
            // 只接受也必须复验；失败回滚已写入的确认、草稿和过程记录，拒绝则允许结束失效匹配。
            asset = lockMatchingAsset(asset);
        }
        Integer nextStatus = createApply ? MarketDemandDO.STATUS_APPLYING
                : accepted && (Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_PUBLISHED)
                || Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_MATCHING))
                ? MarketDemandDO.STATUS_MATCHED : demand.getStatus();
        // 三种模式均要复验需求；拒绝或已 APPLYING 时也不能省略此条件更新。
        // 失败回滚本事务的匹配、草稿及过程记录，不留下“终态需求下确认成功”的孤立结果。
        if (demandMapper.update(null, new LambdaUpdateWrapper<MarketDemandDO>()
                .eq(MarketDemandDO::getId, demand.getId())
                .eq(MarketDemandDO::getStatus, demand.getStatus())
                .eq(MarketDemandDO::getApplyUserId, demand.getApplyUserId())
                .eq(demand.getApplyDeptId() != null, MarketDemandDO::getApplyDeptId, demand.getApplyDeptId())
                .isNull(demand.getApplyDeptId() == null, MarketDemandDO::getApplyDeptId)
                .eq(demand.getTitle() != null, MarketDemandDO::getTitle, demand.getTitle())
                .isNull(demand.getTitle() == null, MarketDemandDO::getTitle)
                .eq(demand.getPurpose() != null, MarketDemandDO::getPurpose, demand.getPurpose())
                .isNull(demand.getPurpose() == null, MarketDemandDO::getPurpose)
                .eq(demand.getExpectedFields() != null, MarketDemandDO::getExpectedFields, demand.getExpectedFields())
                .isNull(demand.getExpectedFields() == null, MarketDemandDO::getExpectedFields)
                .eq(demand.getAcceptanceStatus() != null, MarketDemandDO::getAcceptanceStatus,
                        demand.getAcceptanceStatus())
                .isNull(demand.getAcceptanceStatus() == null, MarketDemandDO::getAcceptanceStatus)
                .set(MarketDemandDO::getStatus, nextStatus)) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        if (!accepted) {
            asset = match.getAssetId() == null ? null : assetMapper.selectById(match.getAssetId());
        }
        notifyHelper.notifyDemandConfirmed(demand, match, asset,
                accepted ? "接受" : "拒绝");
    }

    /** 确认和草稿关联回填共用原匹配身份；可空关联必须精确比较，不能省略条件。 */
    private static LambdaUpdateWrapper<MarketDemandMatchDO> matchConfirmationState(MarketDemandMatchDO match) {
        return new LambdaUpdateWrapper<MarketDemandMatchDO>()
                .eq(MarketDemandMatchDO::getId, match.getId())
                .eq(MarketDemandMatchDO::getDemandId, match.getDemandId())
                .eq(match.getAssetId() != null, MarketDemandMatchDO::getAssetId, match.getAssetId())
                .isNull(match.getAssetId() == null, MarketDemandMatchDO::getAssetId)
                .eq(match.getApplyId() != null, MarketDemandMatchDO::getApplyId, match.getApplyId())
                .isNull(match.getApplyId() == null, MarketDemandMatchDO::getApplyId)
                .eq(match.getMatcherUserId() != null, MarketDemandMatchDO::getMatcherUserId, match.getMatcherUserId())
                .isNull(match.getMatcherUserId() == null, MarketDemandMatchDO::getMatcherUserId);
    }

    /**
     * 验收已交付需求，已匹配不等于已解决。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void acceptDemand(Long id) {
        MarketDemandDO demand = validateOwner(id);
        if (Objects.equals(demand.getAcceptanceStatus(), MarketDemandDO.ACCEPTANCE_DONE)) {
            return;
        }
        if (TERMINAL_STATUSES.contains(demand.getStatus())) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        List<MarketDemandMatchDO> matches = matchMapper.selectListByDemandId(id);
        MarketDemandMatchDO acceptedMatch = null;
        MarketResourceApplyDO ready = null;
        for (MarketDemandMatchDO match : matches) {
            if (!isAcceptedMatch(demand, match)) {
                continue;
            }
            MarketResourceApplyDO candidate = applyMapper.selectById(match.getApplyId());
            if (candidate != null && Objects.equals(candidate.getId(), match.getApplyId())
                    && Objects.equals(candidate.getAssetId(), match.getAssetId())
                    && Objects.equals(candidate.getApplyUserId(), demand.getApplyUserId())
                    && isDeliveredApply(candidate)) {
                acceptedMatch = match;
                ready = candidate;
                break;
            }
        }
        if (ready == null) {
            throw exception(MARKET_DEMAND_ACCEPT_DENIED);
        }

        // 与确认匹配的更新顺序一致：匹配 -> 申请 -> 需求；持锁后不再进行交付核查或 RPC。
        // 旧关联可能在交付读取期间被删除、拒绝或换绑，必须复验并持锁到验收事务提交。
        MarketDemandMatchDO currentMatch = matchMapper.selectForAcceptance(acceptedMatch.getId());
        if (!sameAcceptanceMatch(acceptedMatch, currentMatch)) {
            throw exception(MARKET_DEMAND_ACCEPT_DENIED);
        }
        // 交付检查在锁前完成；复用原申请所有者锁，等待后重读且持有到验收事务结束。
        // 只按版本判断会漏掉未推进版本的旧写入路径，因此同时核对批准范围和绑定。
        MarketResourceApplyDO current = applyMapper.selectOwnedForRenewal(ready.getId(), demand.getApplyUserId());
        if (!sameAcceptanceGrant(ready, current)) {
            throw exception(MARKET_DEMAND_ACCEPT_DENIED);
        }
        // 交付核查期间关闭/取消/驳回、转交或另一次验收可能已经提交。
        // 用读取时的状态和归属作 CAS，不能复活终态，也不能将零行更新冒充验收成功。
        if (demandMapper.update(null, new LambdaUpdateWrapper<MarketDemandDO>()
                .eq(MarketDemandDO::getId, demand.getId())
                .eq(MarketDemandDO::getStatus, demand.getStatus())
                .eq(MarketDemandDO::getApplyUserId, demand.getApplyUserId())
                .eq(MarketDemandDO::getAcceptanceStatus, MarketDemandDO.ACCEPTANCE_NONE)
                .set(MarketDemandDO::getAcceptanceStatus, MarketDemandDO.ACCEPTANCE_DONE)
                .set(MarketDemandDO::getAcceptedApplyId, current.getId())
                .setSql("accepted_time = clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")
                .set(MarketDemandDO::getStatus, MarketDemandDO.STATUS_MATCHED)) != 1) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        // UPDATE 可能等待其他事务的纯行锁，WHERE 中求值的时间不能代表等待结束后的时间。
        // 在同一真实事务中复查；到期、时钟不可用或查询失败均抛出异常，回滚上面的 CAS。
        // 此时只读取本地数据库时钟，不再调用可能跨服务的交付核查。
        if (!accessService.isEffective(current)) {
            throw exception(MARKET_DEMAND_ACCEPT_DENIED);
        }
    }

    /**
     * 判断市场需求是否已受理匹配。
     */
    private static boolean isAcceptedMatch(MarketDemandDO demand, MarketDemandMatchDO match) {
        return match != null && match.getId() != null && match.getId() > 0
                && match.getApplyId() != null && match.getApplyId() > 0
                && match.getAssetId() != null && match.getAssetId() > 0
                && Objects.equals(match.getDemandId(), demand.getId())
                && Objects.equals(match.getStatus(), MarketDemandMatchDO.STATUS_ACCEPTED)
                && !Boolean.TRUE.equals(match.getDeleted());
    }

    /** 同一用户/资产的有效申请可以被多个需求复用；匹配才是当前需求的关联依据。 */
    private static boolean sameAcceptanceMatch(MarketDemandMatchDO observed, MarketDemandMatchDO current) {
        return current != null && !Boolean.TRUE.equals(current.getDeleted())
                && Objects.equals(observed.getId(), current.getId())
                && Objects.equals(observed.getDemandId(), current.getDemandId())
                && Objects.equals(observed.getAssetId(), current.getAssetId())
                && Objects.equals(observed.getApplyId(), current.getApplyId())
                && Objects.equals(observed.getStatus(), current.getStatus());
    }

    /** 仅核对验收依据；首次使用时间、备注和投递调度进度不是批准变化，不参与冲突判断。 */
    private static boolean sameAcceptanceGrant(MarketResourceApplyDO observed, MarketResourceApplyDO current) {
        return current != null
                && Objects.equals(observed.getId(), current.getId())
                && Objects.equals(observed.getRevision(), current.getRevision())
                && Objects.equals(observed.getSourceRevision(), current.getSourceRevision())
                && Objects.equals(observed.getAssetId(), current.getAssetId())
                && Objects.equals(observed.getAssetType(), current.getAssetType())
                && Objects.equals(observed.getApiId(), current.getApiId())
                && Objects.equals(observed.getAppId(), current.getAppId())
                && Objects.equals(observed.getDemandId(), current.getDemandId())
                && Objects.equals(observed.getApplyUserId(), current.getApplyUserId())
                && Objects.equals(observed.getApplyDeptId(), current.getApplyDeptId())
                && Objects.equals(observed.getFieldScope(), current.getFieldScope())
                && Objects.equals(observed.getSnapshot(), current.getSnapshot())
                && Objects.equals(observed.getCatalogVersionId(), current.getCatalogVersionId())
                && Objects.equals(observed.getSourceGrantId(), current.getSourceGrantId())
                && Objects.equals(observed.getPurpose(), current.getPurpose())
                && Objects.equals(observed.getDutyMatter(), current.getDutyMatter())
                && Objects.equals(observed.getLegalBasis(), current.getLegalBasis())
                && Objects.equals(observed.getApplyStartTime(), current.getApplyStartTime())
                && Objects.equals(observed.getApplyEndTime(), current.getApplyEndTime())
                && Objects.equals(observed.getApplyUseType(), current.getApplyUseType())
                && Objects.equals(observed.getApplyUseTimes(), current.getApplyUseTimes())
                && Objects.equals(observed.getStatus(), current.getStatus())
                && Objects.equals(observed.getIfAuth(), current.getIfAuth())
                && Objects.equals(observed.getPaused(), current.getPaused());
    }

    /**
     * 按需求推荐已发布资产。
     */
    @Override
    public List<MarketResourceRespVO> suggestAssets(Long demandId) {
        MarketDemandDO demand = validateExists(demandId);
        objectGuard.assertCanViewDemand(demand);
        DataAssetPageReqVO pageReq = new DataAssetPageReqVO();
        pageReq.setPageNo(1);
        pageReq.setPageSize(8);
        pageReq.setStatus(DataAssetDO.STATUS_PUBLISHED);
        pageReq.setKeyword(firstKeyword(demand.getTitle(), demand.getDescription(), demand.getExpectedFields()));
        return resourceService.getPublishedPage(pageReq).getList();
    }

    /**
     * 判断市场需求是否已交付申请。
     */
    private boolean isDeliveredApply(MarketResourceApplyDO apply) {
        // 与实际访问使用同一数据库时钟和 [start, end) 规则，交付读取后须重新确认。
        return accessService.isEffective(apply) && deliveryService.isConfirmedApply(apply)
                && accessService.isEffective(apply);
    }

    /**
     * 标记匹配中。
     */
    private void markMatching(MarketDemandDO demand) {
        // 先插入子记录，再以原需求快照校验并持锁至同一事务提交。
        // 已 MATCHING/MATCHED/APPLYING 也要复验；不能把未命中的状态更新当作成功。
        int nextStatus = Objects.equals(demand.getStatus(), MarketDemandDO.STATUS_PUBLISHED)
                ? MarketDemandDO.STATUS_MATCHING : demand.getStatus();
        if (demandMapper.update(null, demandMutationState(demand)
                .eq(demand.getTitle() != null, MarketDemandDO::getTitle, demand.getTitle())
                .isNull(demand.getTitle() == null, MarketDemandDO::getTitle)
                .eq(demand.getPurpose() != null, MarketDemandDO::getPurpose, demand.getPurpose())
                .isNull(demand.getPurpose() == null, MarketDemandDO::getPurpose)
                .eq(demand.getExpectedFields() != null, MarketDemandDO::getExpectedFields, demand.getExpectedFields())
                .isNull(demand.getExpectedFields() == null, MarketDemandDO::getExpectedFields)
                .set(MarketDemandDO::getStatus, nextStatus)) != 1) {
            // 回滚刚插入的响应/匹配；通知尚未登记，不生成孤立记录或错误成功通知。
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
    }

    /**
     * 将市场需求业务记录转换为接口响应。
     */
    private MarketDemandRespVO toResp(MarketDemandDO demand, boolean detail) {
        MarketDemandRespVO vo = BeanUtils.toBean(demand, MarketDemandRespVO.class);
        vo.setApplyUserName(displayHelper.userLabel(
                displayHelper.userNames(demand.getApplyUserId()), demand.getApplyUserId()));
        vo.setApplyDeptName(displayHelper.deptLabel(
                displayHelper.deptNames(demand.getApplyDeptId()), demand.getApplyDeptId()));
        return vo;
    }

    /**
     * 转换匹配响应。
     */
    private MarketDemandMatchRespVO toMatchResp(MarketDemandMatchDO match) {
        MarketDemandMatchRespVO vo = BeanUtils.toBean(match, MarketDemandMatchRespVO.class);
        DataAssetDO asset = displayHelper.assets(List.of(match.getAssetId())).get(match.getAssetId());
        if (asset != null) {
            vo.setAssetName(asset.getAssetName());
            vo.setAssetCode(asset.getAssetCode());
            vo.setAssetType(asset.getAssetType());
        }
        Map<Long, String> names = displayHelper.userNames(match.getMatcherUserId(), match.getConfirmUserId());
        vo.setMatcherName(displayHelper.userLabel(names, match.getMatcherUserId()));
        vo.setConfirmUserName(displayHelper.userLabel(names, match.getConfirmUserId()));
        return vo;
    }

    /**
     * 转换回复响应。
     */
    private MarketDemandReplyRespVO toReplyResp(MarketDemandReplyDO reply) {
        MarketDemandReplyRespVO vo = BeanUtils.toBean(reply, MarketDemandReplyRespVO.class);
        vo.setReplyUserName(displayHelper.userLabel(
                displayHelper.userNames(reply.getReplyUserId()), reply.getReplyUserId()));
        vo.setReplyDeptName(displayHelper.deptLabel(
                displayHelper.deptNames(reply.getReplyDeptId()), reply.getReplyDeptId()));
        return vo;
    }

    /**
     * 读取并校验市场需求记录存在，不存在时抛出业务异常。
     */
    private MarketDemandDO validateExists(Long id) {
        MarketDemandDO demand = demandMapper.selectById(id);
        if (demand == null) {
            throw exception(MARKET_DEMAND_NOT_EXISTS);
        }
        return demand;
    }

    /**
     * 校验当前用户具有市场需求对象的所属人权限。
     */
    private MarketDemandDO validateOwner(Long id) {
        MarketDemandDO demand = validateExists(id);
        if (!Objects.equals(demand.getApplyUserId(), objectGuard.requireUserId())) {
            throw exception(MARKET_DEMAND_FORBIDDEN);
        }
        return demand;
    }

    /**
     * 校验市场需求的待关闭，不满足约束时拒绝当前操作。
     */
    private MarketDemandDO validateOpen(Long id) {
        MarketDemandDO demand = validateExists(id);
        if (demand.getStatus() == null || !HALL_STATUSES.contains(demand.getStatus())) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        return demand;
    }

    /**
     * 解析市场需求的部门编号。
     */
    private Long resolveDeptId(Long requestedDeptId) {
        Long deptId = SecurityFrameworkUtils.getLoginUserDeptId();
        if (requestedDeptId != null && deptId != null && !Objects.equals(requestedDeptId, deptId)) {
            throw exception(MARKET_APPLY_DEPT_INVALID);
        }
        return deptId != null ? deptId : requestedDeptId;
    }

    /**
     * 补充市场需求的联系方式。
     */
    private void fillContact(MarketDemandDO demand) {
        try {
            AdminUserRespDTO user = adminUserApi.getUser(demand.getApplyUserId()).getCheckedData();
            if (user == null) {
                return;
            }
            if (demand.getApplyDeptId() == null) {
                demand.setApplyDeptId(user.getDeptId());
            }
            if (StrUtil.isBlank(demand.getPhone())) {
                demand.setPhone(user.getMobile());
            }
            if (StrUtil.isBlank(demand.getEmail())) {
                demand.setEmail(user.getEmail());
            }
        } catch (RuntimeException ex) {
            log.warn("[fillContact] 用户资料不可用：{}", ex.getMessage());
        }
    }

    /**
     * 生成下一个需求编号。
     */
    private String nextDemandNo() {
        Long sequence = demandMapper.nextDemandNoSeq();
        if (sequence == null || sequence <= 0) {
            throw exception(MARKET_DEMAND_STATUS_DENY);
        }
        return displayHelper.nextBizNo("DEM", sequence);
    }

    /**
     * 从需求标题或描述提取用于匹配的首个关键词。
     */
    private String firstKeyword(String... texts) {
        for (String text : texts) {
            if (StrUtil.isNotBlank(text)) {
                return StrUtil.sub(text.trim(), 0, 20);
            }
        }
        return null;
    }

}
