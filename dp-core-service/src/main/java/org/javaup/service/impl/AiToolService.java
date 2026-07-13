package org.javaup.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.dto.Result;
import org.javaup.entity.*;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.service.*;
import org.javaup.utils.UserHolder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 智能助手工具函数服务
 * <p>
 * 所有 @Tool 方法会被 Spring AI 自动发现并注册为 LLM 可调用的 Function。
 * 每个方法直接复用已有 Service，不重复实现业务逻辑。
 * <p>
 * 扩展新工具：只需在本类中添加新的 @Tool 方法并注入对应 Service 即可。
 * @author: DP-Plus
 */
@Slf4j
@Component
public class AiToolService {

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private IUserService userService;

    // ==================== 商铺相关工具 ====================

    @Tool(description = "获取所有商铺类型列表，例如美食、KTV、酒店等")
    public List<ShopType> getShopTypes() {
        log.info("[AI工具] 查询商铺类型列表");
        return shopTypeService.query().orderByAsc("sort").list();
    }

    @Tool(description = "根据商铺类型ID查询该类型下的商铺列表。typeId: 1=美食, 2=KTV, 3=酒店 等")
    public List<Shop> getShopsByType(
            @ToolParam(description = "商铺类型ID，从 getShopTypes 返回的列表中获取") Integer typeId) {
        log.info("[AI工具] 按类型查询商铺 typeId={}", typeId);
        Result result = shopService.queryShopByType(typeId, 1, null, null);
        if (result.getData() instanceof List) {
            return (List<Shop>) result.getData();
        }
        return List.of();
    }

    @Tool(description = "根据关键字搜索商铺名称")
    public List<Shop> searchShopsByName(
            @ToolParam(description = "商铺名称关键字，例如'麦当劳'、'星巴克'") String name) {
        log.info("[AI工具] 按名称搜索商铺 name={}", name);
        return shopService.query().like("name", name).list();
    }

    @Tool(description = "根据商铺ID查询商铺详细信息，包括地址、评分、营业时间等")
    public Shop getShopById(
            @ToolParam(description = "商铺ID") Long shopId) {
        log.info("[AI工具] 查询商铺详情 shopId={}", shopId);
        Result result = shopService.queryById(shopId);
        return (Shop) result.getData();
    }

    // ==================== 优惠券相关工具 ====================

    @Tool(description = "查询某个商铺的所有可用优惠券（包括普通券和秒杀券）")
    public List<Voucher> getVouchersByShop(
            @ToolParam(description = "商铺ID") Long shopId) {
        log.info("[AI工具] 查询商铺优惠券 shopId={}", shopId);
        Result result = voucherService.queryVoucherOfShop(shopId);
        if (result.getData() instanceof List) {
            return (List<Voucher>) result.getData();
        }
        return List.of();
    }

    @Tool(description = "查询某个秒杀优惠券的详细信息，包括库存数量、秒杀开始/结束时间、适用等级等")
    public SeckillVoucherFullModel getSeckillVoucherDetail(
            @ToolParam(description = "优惠券ID") Long voucherId) {
        log.info("[AI工具] 查询秒杀券详情 voucherId={}", voucherId);
        return seckillVoucherService.queryByVoucherId(voucherId);
    }

    // ==================== 订单相关工具 ====================

    @Tool(description = "查询当前登录用户是否购买了某个优惠券，以及订单状态")
    public String getMyOrderStatus(
            @ToolParam(description = "优惠券ID") Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        log.info("[AI工具] 查询订单状态 userId={} voucherId={}", userId, voucherId);
        try {
            VoucherOrder order = voucherOrderService.query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .one();
            if (order == null) {
                return "用户未购买该优惠券（voucherId=" + voucherId + "）";
            }
            String statusText = switch (order.getStatus()) {
                case 0 -> "未使用";
                case 1 -> "已使用";
                case 2 -> "已取消";
                default -> "未知状态(" + order.getStatus() + ")";
            };
            return "订单详情：订单ID=" + order.getId()
                    + "，优惠券ID=" + order.getVoucherId()
                    + "，状态=" + statusText
                    + "，创建时间=" + order.getCreateTime();
        } catch (Exception e) {
            log.error("[AI工具] 查询订单失败 userId={} voucherId={}", userId, voucherId, e);
            return "查询订单失败：" + e.getMessage();
        }
    }

    // ==================== 用户相关工具 ====================

    @Tool(description = "获取当前登录用户的基本信息，包括昵称、手机号等")
    public String getCurrentUserInfo() {
        var userDTO = UserHolder.getUser();
        log.info("[AI工具] 查询当前用户信息 userId={}", userDTO.getId());
        if (userDTO == null) {
            return "未能获取当前用户信息，请先登录";
        }
        return "当前用户信息：用户ID=" + userDTO.getId()
                + "，昵称=" + userDTO.getNickName()
                + "，头像=" + (userDTO.getIcon() != null ? userDTO.getIcon() : "未设置");
    }

    @Tool(description = "根据用户ID查询用户的详细信息，包括等级、城市、粉丝数、关注数等")
    public String getUserProfile(
            @ToolParam(description = "用户ID") Long userId) {
        log.info("[AI工具] 查询用户详情 userId={}", userId);
        UserInfo info = userInfoService.getByUserId(userId);
        if (info == null) {
            return "用户详情不存在（userId=" + userId + "）";
        }
        User user = userService.getById(userId);
        return "用户详情：用户ID=" + userId
                + "，昵称=" + (user != null ? user.getNickName() : "未知")
                + "，等级=" + info.getLevel()
                + "，城市=" + (info.getCity() != null ? info.getCity() : "未设置")
                + "，粉丝数=" + info.getFans()
                + "，关注数=" + info.getFollowee();
    }
}
