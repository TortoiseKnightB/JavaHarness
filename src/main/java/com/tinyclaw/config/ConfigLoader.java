package com.tinyclaw.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器。
 * <p>
 * 从 classpath:application.yml 读取配置，并解析 ${ENV_VAR:default} 占位符。
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    /**
     * 匹配 ${ENV_VAR:default} 或 ${ENV_VAR} 占位符
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)(?::([^}]*))?}");

    private static final String CONFIG_FILE = "application.yml";

    private ConfigLoader() {
    }

    /**
     * 加载应用配置。
     */
    public static AppConfig load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);

        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                throw new IllegalStateException("配置文件 " + CONFIG_FILE + " 未找到");
            }

            // 先读为原始 JsonNode，逐字段解析占位符
            JsonNode raw = mapper.readTree(in);
            JsonNode resolved = resolvePlaceholders(raw);

            // 反序列化到强类型配置对象（走 readValue 以确保命名策略生效）
            String resolvedYaml = mapper.writeValueAsString(resolved);
            return mapper.readValue(resolvedYaml, AppConfig.class);

        } catch (IOException e) {
            throw new IllegalStateException("无法加载配置文件 " + CONFIG_FILE, e);
        }
    }

    /**
     * 递归解析 JsonNode 中所有文本字段的 ${ENV_VAR:default} 占位符。
     */
    private static JsonNode resolvePlaceholders(JsonNode node) {
        if (node.isTextual()) {
            String resolved = resolveText(node.asText());
            return resolved.equals(node.asText()) ? node : TextNode.valueOf(resolved);
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> iter = obj.fields();
            // 收集字段后再替换，避免 ConcurrentModification
            ArrayList<Map.Entry<String, JsonNode>> replacements = new ArrayList<>();
            while (iter.hasNext()) {
                Map.Entry<String, JsonNode> entry = iter.next();
                replacements.add(Map.entry(entry.getKey(), resolvePlaceholders(entry.getValue())));
            }
            for (Map.Entry<String, JsonNode> entry : replacements) {
                obj.replace(entry.getKey(), entry.getValue());
            }
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, resolvePlaceholders(arr.get(i)));
            }
        }
        return node;
    }

    /**
     * 解析单个文本值中的占位符。
     */
    private static String resolveText(String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String defaultValue = matcher.group(2) != null ? matcher.group(2) : "";
            String envValue = System.getenv(envVar);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    envValue != null ? envValue : defaultValue));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
