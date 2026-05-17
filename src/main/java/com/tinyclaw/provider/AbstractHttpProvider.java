package com.tinyclaw.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP Provider 公共基类。
 * <p>
 * 封装 HttpClient 生命周期、JSON 序列化、API 错误处理与超时配置。
 * 子类只需实现协议翻译逻辑。
 */
public abstract class AbstractHttpProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractHttpProvider.class);

    /**
     * 统一的 JSON 处理器，子类共享
     */
    protected final ObjectMapper mapper;

    /**
     * HTTP 客户端，支持 HTTP/2，连接超时 30s，请求超时 120s（慢思考需要更长时间）
     */
    protected final HttpClient httpClient;

    /**
     * 模型名称（例如 "glm-4.5-air"）
     */
    protected final String model;

    /**
     * 大模型 API 的完整端点地址
     */
    protected final String endpoint;

    /**
     * API 密钥
     */
    protected final String apiKey;

    /**
     * @param model    模型名称
     * @param apiKey   API 密钥
     * @param baseUrl  API 基础地址（如 "https://open.bigmodel.cn/api/paas/v4/"）
     * @param path     具体的 API 路径（如 "chat/completions" 或 "messages"）
     */
    protected AbstractHttpProvider(String model, String apiKey, String baseUrl, String path) {
        this.model = model;
        this.apiKey = apiKey;
        this.endpoint = baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * @param messages       当前上下文历史
     * @param availableTools 当前挂载的工具定义（空列表时表示 Thinking 阶段）
     * @return 模型返回的消息（含纯文本或工具调用）
     */
    @Override
    public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
        // 1. 构造请求体 JSON
        ObjectNode requestBody = buildRequestBody(messages, availableTools);

        // 2. 发起 HTTP POST
        ObjectNode responseBody;
        try {
            responseBody = httpPost(requestBody);
        } catch (IOException e) {
            throw new ProviderException("API 请求失败: " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("API 请求被中断", e);
        }

        // 3. 检查 API 错误
        if (responseBody.has("error")) {
            JsonNode error = responseBody.get("error");
            String errorMsg = error.has("message") ? error.get("message").asText() : error.toString();
            throw new ProviderException(errorMsg);
        }

        // 4. 解析响应为内部 Message
        return parseResponse(responseBody);
    }

    /**
     * 子类实现：构造与具体协议对应的请求体 JSON。
     *
     * @param messages       当前上下文历史
     * @param availableTools 当前挂载的工具定义（空列表时表示 Thinking 阶段）
     * @return 协议对应的 JSON 请求体
     */
    protected abstract ObjectNode buildRequestBody(List<Message> messages, List<ToolDefinition> availableTools);

    /**
     * 子类实现：解析具体协议的响应体为内部 Message。
     *
     * @param responseBody API 返回的 JSON 响应体
     * @return 解析后的内部 Message（含纯文本或工具调用）
     */
    protected abstract Message parseResponse(ObjectNode responseBody);

    /**
     * 便捷方法：将 tool_use.input / function.arguments (JsonNode) 反序列化为 Map。
     *
     * @param arguments 工具调用的 JSON 参数节点
     * @return 解析后的键值对映射，解析失败返回空 Map
     */
    protected Map<String, Object> argsToMap(JsonNode arguments) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.convertValue(arguments, Map.class);
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 便捷方法：发送 HTTP POST 请求，返回解析后的 JSON ObjectNode。
     *
     * @param requestBody 已构造好的 JSON 请求体
     * @return API 返回的 JSON 响应体
     * @throws IOException          网络异常
     * @throws InterruptedException 请求被中断
     */
    private ObjectNode httpPost(ObjectNode requestBody) throws IOException, InterruptedException {
        String bodyJson = mapper.writeValueAsString(requestBody);
        log.debug("→ POST {} body: {}", endpoint, bodyJson);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120));

        // 子类可覆盖 authHeader() 提供不同的认证方式
        for (Map.Entry<String, String> header : authHeaders().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(bodyJson)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        String responseBody = response.body();
        log.debug("← {} status={} body: {}", response.statusCode(), endpoint, responseBody);

        if (response.statusCode() >= 400) {
            throw new ProviderException("API 返回 HTTP " + response.statusCode() + ": " + responseBody);
        }

        return (ObjectNode) mapper.readTree(responseBody);
    }

    /**
     * 子类可覆盖：提供认证请求头。
     * 默认使用 Bearer Token（OpenAI 兼容协议）。
     */
    protected Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer " + apiKey);
    }
}
