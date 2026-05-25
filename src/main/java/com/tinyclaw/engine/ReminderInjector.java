package com.tinyclaw.engine;

import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负责在运行时监控上下文，并在模型陷入死循环时动态注入强力打断信息。
 * <p>
 * 维护连续失败的工具调用指纹计数器，监控模型是否在用完全相同的参数反复尝试同一个已失败的工具。
 * 连续 {@link #DOOM_LOOP_THRESHOLD} 次相同特征失败时，以 User 消息的形式向上下文尾部注入一条
 * System Reminder，利用近因偏差（Recency Bias）打破模型的局部执念。
 * <p>
 * 设计原理：System Prompt 本身防不住死循环——随着上下文末尾堆积大量结构相似的 Error 信息，
 * 内容分布偏移使模型被近期输入强力牵引。必须在大模型下一次推理的前一刻（Point of Decision）
 * 将高优先级指令直接怼到上下文最末端。
 */
public class ReminderInjector {

    private static final Logger log = LoggerFactory.getLogger(ReminderInjector.class);

    /**
     * 触发死循环干预的连续失败次数阈值
     */
    private static final int DOOM_LOOP_THRESHOLD = 3;

    /**
     * 连续失败的工具调用指纹计数器。
     * <p>
     * Key = MD5(toolName + arguments)，Value = 连续失败次数。
     * 工具执行成功时清空全部计数器。
     */
    private final Map<String, Integer> consecutiveFailures;

    public ReminderInjector() {
        this.consecutiveFailures = new ConcurrentHashMap<>();
    }

    /**
     * 生成工具调用的唯一指纹，用于判断大模型是否在重复相同的动作。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数字节数组
     * @return MD5 哈希十六进制字符串
     */
    private static String generateFingerprint(String toolName, byte[] arguments) {
        try {
            // 将 工具名 + 参数 拼接后通过MD5哈希加密
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(toolName.getBytes(StandardCharsets.UTF_8));
            messageDigest.update(arguments);
            byte[] digest = messageDigest.digest();
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * 分析本轮的执行结果，决定是否要在 Context 尾部追加 Reminder。
     * <p>
     * 返回的 Message 将作为最新的用户输入追加到 Session 末尾，
     * 在下一次 API 请求时凭借最高的近因效应权重强制模型优先阅读。
     *
     * @param toolCall   本轮执行的工具调用
     * @param toolResult 工具执行结果
     * @return 注入的提醒消息，未触发干预时返回 null
     */
    public Message checkAndInject(ToolCall toolCall, ToolResult toolResult) {
        String fingerprint = generateFingerprint(
                toolCall.name(),
                toolCall.arguments().toString().getBytes(StandardCharsets.UTF_8)
        );

        // 工具执行成功 → Agent 在这条路径上走通了，清空所有失败计数器
        if (!toolResult.isError()) {
            consecutiveFailures.clear();
            return null;
        }

        // 累加该特征的失败次数
        int failCount = consecutiveFailures.merge(fingerprint, 1, Integer::sum);
        log.warn("[Reminder] 监控到工具 {} 执行失败，该参数特征连续失败次数: {}", toolCall.name(), failCount);

        // 触发死循环打断机制
        if (failCount >= DOOM_LOOP_THRESHOLD) {
            log.warn("[Reminder] ⚠️ 触发死循环干预！注入强力修正指令。");

            String nudgeMsg = String.format(
                    """
                            [SYSTEM REMINDER 警告]
                            你似乎陷入了死循环。你刚刚连续 %d 次使用相同的参数调用了 '%s' 工具，并且都失败了。
                            请立即停止这种无效的重试！你的注意力被当前的报错过度吸引了。
                            你需要：
                            1. 停止猜测参数。跳出当前的局部思维。
                            2. 彻底改变你的策略。
                            3. 如果你确实无法通过系统工具解决当前问题，请直接结束任务并向用户说明你需要什么人工帮助，而不是继续盲目消耗 API 资源尝试。""",
                    failCount, toolCall.name());

            // 【核心】必须是 Role.USER，以保证在下一次 API 请求时拥有最高的近因效应权重
            return new Message(Role.USER, nudgeMsg);
        }

        return null;
    }
}
