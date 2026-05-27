package com.tinyclaw.feishu;

import com.tinyclaw.engine.FeishuReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 审批管理器：跨线程的审批信号传递中枢。
 * <p>
 * 当 Middleware 检测到高危操作时，通过 {@link #waitForApproval} 阻塞当前引擎线程，
 * 直到飞书用户在聊天中回复 approve/reject 口令，由 {@link #resolveApproval} 唤醒。
 * 使用 {@link CompletableFuture} ——future.join() 阻塞等待，
 * future.complete() 解除阻塞。
 */
public class ApprovalManager {

    private static final Logger log = LoggerFactory.getLogger(ApprovalManager.class);

    /**
     * 全局单例，方便在 Middleware 和飞书 Webhook 之间共享状态
     */
    public static final ApprovalManager INSTANCE = new ApprovalManager();

    /**
     * 待审批任务，Key 为 TaskID，Value 为等待审批结果的 Future
     */
    private final ConcurrentHashMap<String, CompletableFuture<ApprovalResult>> pendingTasks = new ConcurrentHashMap<>();

    /**
     * 高危命令正则匹配模式
     */
    private static final Pattern[] DANGEROUS_PATTERNS = {
            Pattern.compile("rm\\s+-r"),      // 级联删除
            Pattern.compile("sudo\\s+"),      // 提权
            Pattern.compile("drop\\s+"),      // 数据库删除
            Pattern.compile(">.*\\.java"),    // 恶意覆盖源代码
    };

    /**
     * 发送飞书审批通知，并阻塞当前线程等待审批结果。
     *
     * @param taskId   审批任务唯一标识（使用大模型生成的 ToolCallID）
     * @param toolName 工具名称
     * @param args     工具参数
     * @param reporter 飞书 Reporter，用于发送审批消息
     * @return 审批结果
     */
    public ApprovalResult waitForApproval(String taskId, String toolName, String args, FeishuReporter reporter) {
        CompletableFuture<ApprovalResult> future = new CompletableFuture<>();
        pendingTasks.put(taskId, future);

        String noticeMsg = "⚠️ **高危操作审批请求**\n"
                + "Agent 试图执行以下动作:\n"
                + "- 工具: " + toolName + "\n"
                + "- 参数: " + args + "\n"
                + "任务 ID: **" + taskId + "**\n"
                + "👉 请在此消息下方回复 \"approve " + taskId + "\" 或 \"reject " + taskId + "\" 来决定是否放行。";

        if (reporter != null) {
            reporter.sendMsg(noticeMsg);
        } else {
            System.out.println("\n\033[31m[需要审批 TaskID: " + taskId + "]\033[0m " + noticeMsg);
        }

        log.info("[Approval] 已发送审批请求 (TaskID: {})，线程挂起等待...", taskId);

        // 【驾驭核心】：阻塞等待飞书 Webhook 唤醒
        ApprovalResult result;
        try {
            // 注意，这里是一个"空"的 Future，它永远不会自动完成，直到resolveApproval方法调用future.complete()后才能拿到结果
            result = future.join();
        } catch (Exception e) {
            log.error("[Approval] 等待审批时发生异常: {}", e.getMessage());
            result = new ApprovalResult(false, "审批超时或异常，已自动拒绝");
        }

        pendingTasks.remove(taskId);
        return result;
    }

    /**
     * 由飞书 Webhook 回调触发，向 Future 发送信号解开阻塞。
     *
     * @param taskId  审批任务 ID
     * @param allowed 是否批准
     * @param reason  审批理由
     */
    public void resolveApproval(String taskId, boolean allowed, String reason) {
        CompletableFuture<ApprovalResult> future = pendingTasks.get(taskId);
        if (future != null) {
            log.info("[Approval] 收到审批结果 (TaskID: {}, Allowed: {})", taskId, allowed);
            future.complete(new ApprovalResult(allowed, reason));
        } else {
            log.warn("[Approval] 找不到对应的 TaskID: {}，可能已超时或处理完毕", taskId);
        }
    }

    /**
     * 检查给定的工具调用是否为高危操作。
     * <p>
     * 对于纯读取工具默认放行；对于 bash 等高危工具匹配黑名单正则。
     *
     * @param toolName 工具名称
     * @param args     工具参数 JSON 字符串
     * @return true 表示需要审批
     */
    public static boolean isDangerousCommand(String toolName, String args) {
        if (!"bash".equals(toolName) && !"write_file".equals(toolName) && !"edit_file".equals(toolName)) {
            return false;
        }
        if ("bash".equals(toolName)) {
            for (Pattern p : DANGEROUS_PATTERNS) {
                if (p.matcher(args).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 审批结果。
     *
     * @param allowed 是否允许执行
     * @param reason  审批理由
     */
    public record ApprovalResult(boolean allowed, String reason) {
    }
}
