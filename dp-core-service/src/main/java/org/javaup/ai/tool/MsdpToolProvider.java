package org.javaup.ai.tool;

import org.javaup.entity.*;
import org.javaup.service.*;
import org.javaup.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MSDP 业务工具桥接层。
 * 将 MSDP 已有的店铺/优惠券/订单/用户等业务服务，包装为 Agent 可感知的工具描述和数据。
 */
@Service
public class MsdpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MsdpToolProvider.class);

    private final IShopService shopService;
    private final IShopTypeService shopTypeService;
    private final IVoucherService voucherService;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;
    private final IUserService userService;
    private final IUserInfoService userInfoService;

    public MsdpToolProvider(IShopService shopService, IShopTypeService shopTypeService,
                             IVoucherService voucherService, ISeckillVoucherService seckillVoucherService,
                             IVoucherOrderService voucherOrderService, IUserService userService,
                             IUserInfoService userInfoService) {
        this.shopService = shopService;
        this.shopTypeService = shopTypeService;
        this.voucherService = voucherService;
        this.seckillVoucherService = seckillVoucherService;
        this.voucherOrderService = voucherOrderService;
        this.userService = userService;
        this.userInfoService = userInfoService;
    }

    /**
     * 获取所有可用工具的 JSON Schema 描述（供 LLM Function Calling 使用）。
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return List.of(
            toolDef("getShopTypes", "获取所有店铺分类列表", Map.of()),
            toolDef("getShopsByType", "根据分类ID查询店铺列表",
                    Map.of("typeId", Map.of("type", "integer", "description", "店铺分类ID"))),
            toolDef("searchShopsByName", "根据关键词搜索店铺",
                    Map.of("name", Map.of("type", "string", "description", "店铺名称关键词"))),
            toolDef("getShopById", "查询指定店铺的详细信息",
                    Map.of("shopId", Map.of("type", "integer", "description", "店铺ID"))),
            toolDef("getVouchersByShop", "查询指定店铺的所有优惠券",
                    Map.of("shopId", Map.of("type", "integer", "description", "店铺ID"))),
            toolDef("getSeckillVoucherDetail", "查询秒杀优惠券的详细信息",
                    Map.of("voucherId", Map.of("type", "integer", "description", "优惠券ID"))),
            toolDef("getMyOrderStatus", "查询当前用户是否已购买指定优惠券",
                    Map.of("voucherId", Map.of("type", "integer", "description", "优惠券ID"))),
            toolDef("getCurrentUserInfo", "获取当前登录用户的基本信息", Map.of()),
            toolDef("searchKnowledge", "搜索知识库中的文档（退款政策、操作指南等）",
                    Map.of("query", Map.of("type", "string", "description", "搜索关键词")))
        );
    }

    /**
     * 执行工具调用，返回结果文本。
     */
    public String executeTool(String toolName, Map<String, Object> params) {
        try {
            return switch (toolName) {
                case "getShopTypes" -> formatShopTypes(shopTypeService.list());
                case "getShopsByType" -> {
                    Integer typeId = toInt(params.get("typeId"));
                    yield formatShops(shopService.query().eq("type_id", typeId).list());
                }
                case "searchShopsByName" -> {
                    String name = Objects.toString(params.get("name"), "");
                    yield formatShops(shopService.query().like("name", name).list());
                }
                case "getShopById" -> {
                    Integer id = toInt(params.get("shopId"));
                    Shop shop = shopService.getById(id);
                    yield shop == null ? "未找到该店铺" : formatShop(shop);
                }
                case "getVouchersByShop" -> {
                    Integer shopId = toInt(params.get("shopId"));
                    yield formatVouchers(voucherService.query().eq("shop_id", shopId).list());
                }
                case "getSeckillVoucherDetail" -> {
                    Integer vId = toInt(params.get("voucherId"));
                    SeckillVoucher sv = seckillVoucherService.getById(vId);
                    yield sv == null ? "未找到该秒杀优惠券" : formatSeckillVoucher(sv);
                }
                case "getMyOrderStatus" -> {
                    Long userId = UserHolder.getUser().getId();
                    Integer vId = toInt(params.get("voucherId"));
                    var orders = voucherOrderService.query()
                            .eq("user_id", userId)
                            .eq("voucher_id", vId).list();
                    yield orders.isEmpty() ? "您尚未购买此优惠券" : "您已购买此优惠券，订单状态: " + orders.getFirst().getStatus();
                }
                case "getCurrentUserInfo" -> {
                    Long userId = UserHolder.getUser().getId();
                    User user = userService.getById(userId);
                    yield user == null ? "未找到用户信息" : "用户昵称: " + user.getNickName() + ", 手机: " + maskPhone(user.getPhone());
                }
                default -> "未知工具: " + toolName;
            };
        } catch (Exception ex) {
            log.warn("工具执行失败: tool={}, {}", toolName, ex.getMessage());
            return "工具执行异常: " + ex.getMessage();
        }
    }

    // ── 格式化 ─────────────────────────────────────────────────────

    private String formatShopTypes(List<ShopType> types) {
        return types.stream().map(t -> t.getId() + ". " + t.getName()).collect(Collectors.joining("\n"));
    }

    private String formatShops(List<Shop> shops) {
        return shops.stream().map(this::formatShop).collect(Collectors.joining("\n---\n"));
    }

    private String formatShop(Shop s) {
        return String.format("店铺: %s (ID:%d)\n地址: %s\n评分: %.1f 均消: %d\n营业时间: %s",
                s.getName(), s.getId(), s.getAddress(), s.getScore(), s.getAvgPrice(), s.getOpenHours());
    }

    private String formatVouchers(List<Voucher> vouchers) {
        return vouchers.stream().map(v -> String.format("优惠券: %s (ID:%d)\n描述: %s\n面值: %d 支付: %d",
                v.getTitle(), v.getId(), v.getSubTitle(), v.getActualValue(), v.getPayValue()))
                .collect(Collectors.joining("\n---\n"));
    }

    private String formatSeckillVoucher(SeckillVoucher sv) {
        return String.format("秒杀优惠券 ID:%d\n库存: %d/%d\n开始: %s 结束: %s",
                sv.getVoucherId(), sv.getStock(), sv.getInitStock(),
                sv.getBeginTime(), sv.getEndTime());
    }

    private Integer toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolDef(String name, String description, Map<String, Object> properties) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", name);
        def.put("description", description);
        def.put("parameters", Map.of("type", "object", "properties", properties));
        return def;
    }
}
