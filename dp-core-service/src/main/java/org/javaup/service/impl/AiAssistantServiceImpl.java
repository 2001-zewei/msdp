package org.javaup.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.dto.AiChatResponse;
import org.javaup.dto.AiChatResponse.ChatMessage;
import org.javaup.service.IAiAssistantService;
import org.javaup.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * AI 智能助手服务实现
 * <p>
 * 使用 RestClient 直接调用 DashScope API，手动实现 Function Calling。
 * @author: DP-Plus
 */
@Slf4j
@Service
public class AiAssistantServiceImpl implements IAiAssistantService {

    private static final String SYSTEM_PROMPT = """
            你是 DP-Plus 智能助手，一个电商点评平台的AI客服。你可以使用工具查询实时数据来回答用户问题。
            请遵循以下规则：
            1. 使用简洁友好的中文回答用户问题。
            2. 如果用户询问附近商铺但没有指定位置，请主动询问用户想找什么类型的商铺。
            3. 对于优惠券相关问题，请先确认用户想查询哪个商铺的优惠券。
            4. 当需要获取信息时，主动调用对应的工具函数。
            5. 如果无法获取所需信息，请如实告知用户。
            """;

    // Function calling 工具定义
    private static final List<Map<String, Object>> TOOLS = List.of(
            tool("getShopTypes", "获取所有商铺类型列表，例如美食、KTV、酒店等",
                    Map.of()),
            tool("getShopsByType", "根据商铺类型ID查询该类型下的商铺列表",
                    Map.of("typeId", Map.of("type", "integer", "description", "商铺类型ID"))),
            tool("searchShopsByName", "根据关键字搜索商铺名称",
                    Map.of("name", Map.of("type", "string", "description", "商铺名称关键字，例如'麦当劳'"))),
            tool("getShopById", "根据商铺ID查询商铺详细信息，包括地址、评分、营业时间等",
                    Map.of("shopId", Map.of("type", "integer", "description", "商铺ID"))),
            tool("getVouchersByShop", "查询某个商铺的所有可用优惠券",
                    Map.of("shopId", Map.of("type", "integer", "description", "商铺ID"))),
            tool("getSeckillVoucherDetail", "查询某个秒杀优惠券的详细信息，包括库存数量、有效期等",
                    Map.of("voucherId", Map.of("type", "integer", "description", "优惠券ID"))),
            tool("getMyOrderStatus", "查询当前登录用户是否购买了某个优惠券，以及订单状态",
                    Map.of("voucherId", Map.of("type", "integer", "description", "优惠券ID"))),
            tool("getCurrentUserInfo", "获取当前登录用户的基本信息，包括昵称、手机号等",
                    Map.of())
    );

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", new ArrayList<>(properties.keySet())
                        )
                )
        );
    }

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:qwen-plus}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private Double temperature;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AiToolService aiToolService;

    private RestClient restClient;

    private RestClient getRestClient() {
        if (restClient == null) {
            String chatUrl = baseUrl + "/v1/chat/completions";
            log.info("[AI助手] 初始化 RestClient, chatUrl={}, model={}", chatUrl, model);
            this.restClient = RestClient.builder()
                    .baseUrl(chatUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .defaultHeader("Content-Type", "application/json")
                    .build();
        }
        return restClient;
    }

    @Override
    public AiChatResponse chat(Long userId, String message) {
        String historyKey = RedisConstants.AI_CHAT_HISTORY_KEY + userId;

        // 1. 构建消息列表（不含历史中的tool消息，简化处理）
        List<Map<String, Object>> allMessages = new ArrayList<>();
        allMessages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        allMessages.add(Map.of("role", "user", "content", message));

        log.info("[AI助手] userId={} 发送消息: {}", userId, message);

        // 2. Function Calling 循环（最多5轮）
        String reply;
        try {
            for (int round = 0; round < 5; round++) {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", model);
                requestBody.put("messages", allMessages);
                requestBody.put("temperature", temperature);
                requestBody.put("max_tokens", 2048);
                requestBody.put("tools", TOOLS);

                String requestJson = JSONUtil.toJsonStr(requestBody);
                log.debug("[AI助手] 第{}轮请求", round + 1);

                String responseBody = getRestClient().post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestJson)
                        .retrieve()
                        .body(String.class);

                Map<String, Object> responseMap = JSONUtil.toBean(responseBody, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> responseMsg = (Map<String, Object>) choice.get("message");
                String finishReason = (String) choice.get("finish_reason");

                // 如果 LLM 要调用工具
                if ("tool_calls".equals(finishReason) && responseMsg.containsKey("tool_calls")) {
                    // 添加 assistant 消息（含 tool_calls）
                    allMessages.add(responseMsg);

                    List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) responseMsg.get("tool_calls");
                    for (Map<String, Object> toolCall : toolCalls) {
                        String fnName = (String) ((Map<String, Object>) toolCall.get("function")).get("name");
                        String fnArgs = (String) ((Map<String, Object>) toolCall.get("function")).get("arguments");
                        String toolCallId = (String) toolCall.get("id");

                        log.info("[AI助手] LLM 调用工具: {}({})", fnName, fnArgs);

                        // 执行工具
                        String toolResult = executeTool(fnName, fnArgs);

                        // 添加 tool 消息
                        allMessages.add(Map.of(
                                "role", "tool",
                                "tool_call_id", toolCallId,
                                "content", toolResult
                        ));
                    }
                    // 继续循环，让 LLM 处理工具结果
                    continue;
                }

                // 正常文本回复
                reply = (String) responseMsg.get("content");
                log.info("[AI助手] userId={} 收到回复: {}", userId, reply);

                // 保存对话到 Redis
                long now = System.currentTimeMillis();
                saveMessageToRedis(historyKey, "user", message, now);
                saveMessageToRedis(historyKey, "assistant", reply, now);

                List<ChatMessage> fullHistory = getFullHistory(historyKey);
                return new AiChatResponse(reply, fullHistory);
            }

            reply = "抱歉，处理您的请求时遇到了问题，请稍后再试。";
        } catch (Exception e) {
            log.error("[AI助手] LLM 调用失败 userId={}", userId, e);
            reply = "抱歉，AI 服务暂时不可用，请稍后再试。";
        }

        // 保存对话到 Redis
        long now = System.currentTimeMillis();
        saveMessageToRedis(historyKey, "user", message, now);
        saveMessageToRedis(historyKey, "assistant", reply, now);

        List<ChatMessage> fullHistory = getFullHistory(historyKey);
        return new AiChatResponse(reply, fullHistory);
    }

    /**
     * 根据工具名称和参数执行对应的 AiToolService 方法
     */
    private String executeTool(String name, String arguments) {
        try {
            JSONObject args = (arguments != null && !arguments.isEmpty())
                    ? JSONUtil.parseObj(arguments) : new JSONObject();

            return switch (name) {
                case "getShopTypes" -> JSONUtil.toJsonStr(aiToolService.getShopTypes());
                case "getShopsByType" -> {
                    Integer typeId = args.getInt("typeId");
                    yield JSONUtil.toJsonStr(aiToolService.getShopsByType(typeId));
                }
                case "searchShopsByName" -> JSONUtil.toJsonStr(
                        aiToolService.searchShopsByName(args.getStr("name")));
                case "getShopById" -> JSONUtil.toJsonStr(
                        aiToolService.getShopById(args.getLong("shopId")));
                case "getVouchersByShop" -> JSONUtil.toJsonStr(
                        aiToolService.getVouchersByShop(args.getLong("shopId")));
                case "getSeckillVoucherDetail" -> JSONUtil.toJsonStr(
                        aiToolService.getSeckillVoucherDetail(args.getLong("voucherId")));
                case "getMyOrderStatus" -> aiToolService.getMyOrderStatus(args.getLong("voucherId"));
                case "getCurrentUserInfo" -> aiToolService.getCurrentUserInfo();
                default -> "未知工具: " + name;
            };
        } catch (Exception e) {
            log.error("[AI助手] 工具执行失败 name={} args={}", name, arguments, e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    private void saveMessageToRedis(String historyKey, String role, String content, long timestamp) {
        ChatMessage chatMsg = new ChatMessage(role, content, timestamp);
        String json = JSONUtil.toJsonStr(chatMsg);
        stringRedisTemplate.opsForList().rightPush(historyKey, json);
        stringRedisTemplate.opsForList().trim(historyKey, -RedisConstants.AI_CHAT_MAX_HISTORY, -1);
        stringRedisTemplate.expire(historyKey, java.time.Duration.ofMinutes(RedisConstants.AI_CHAT_HISTORY_TTL));
    }

    private List<ChatMessage> getFullHistory(String historyKey) {
        List<ChatMessage> history = new ArrayList<>();
        List<String> historyJsonList = stringRedisTemplate.opsForList().range(historyKey, 0, -1);
        if (historyJsonList != null) {
            for (String json : historyJsonList) {
                try {
                    history.add(JSONUtil.toBean(json, ChatMessage.class));
                } catch (Exception e) {
                    log.warn("[AI助手] 解析历史消息失败: {}", json, e);
                }
            }
        }
        return history;
    }
}
