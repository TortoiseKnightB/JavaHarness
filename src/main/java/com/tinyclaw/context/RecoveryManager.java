package com.tinyclaw.context;

/**
 * 负责在工具执行失败时，根据报错特征分析并注入恢复建议（锦囊妙计）。
 * <p>
 * 基于字符串关键字匹配。生产环境建议改用 POSIX 标准错误码或领域错误码。
 */
public class RecoveryManager {

    /**
     * 接收原始报错，匹配已知特征模式，返回增强后的报错信息。
     * <p>
     * 在原报错后追加一条高优先级的系统级恢复指示，引导大模型走向正确的排障路径。
     *
     * @param toolName 触发错误的工具名称
     * @param rawError 原始错误信息
     * @return 增强后的报错信息（原错误 + 恢复提示），无匹配时返回原始错误
     */
    public String analyzeAndInject(String toolName, String rawError) {
        String hint = null;
        String lowerError = rawError.toLowerCase();

        switch (toolName) {
            case "edit_file" -> {
                if (rawError.contains("在文件中未找到 old_text") || rawError.contains("找不到该代码片段")) {
                    hint = "你提供的 old_text 与文件当前内容不一致，或者缺少必要的缩进。请先使用 `read_file` 工具重新读取该文件，获取最新、准确的内容后，再重新发起编辑。";
                } else if (rawError.contains("匹配到了多处") || rawError.contains("提供更多上下文") || rawError.contains("请提供更多上下行代码")) {
                    hint = "你的 old_text 不够具体，命中了多个相同代码块。请在 old_text 中增加上下相邻的几行代码，以确保替换的唯一性。";
                }
            }
            case "read_file", "write_file" -> {
                // 匹配原生 os 包抛出的 POSIX 标准错误
                if (lowerError.contains("no such file or directory")) {
                    hint = "路径似乎不正确。请不要凭空猜测，先使用 `bash` 执行 `ls -la` 或 `find . -name` 命令查找正确的目录结构和文件名。";
                } else if (lowerError.contains("permission denied")) {
                    hint = "你没有权限操作该文件。请检查工作区限制，或者思考是否需要修改其他文件。";
                }
            }
            case "bash" -> {
                if (lowerError.contains("command not found")) {
                    hint = "系统中未安装该命令。请先思考：是否有替代命令？或者你需要先编写脚本进行安装？";
                } else if (rawError.contains("超时") || rawError.contains("DeadlineExceeded")) {
                    // 匹配我们手写的 30s context.WithTimeout 报错
                    hint = "该命令执行被超时强杀。如果它是一个常驻服务（如 server 或 watch），请将其转入后台执行（例如使用 `nohup ... &`），不要阻塞主线程。";
                } else if (lowerError.contains("undefined")
                        || lowerError.contains("compilation error")
                        || lowerError.contains("cannot find symbol")
                        || rawError.contains("exit code")) {
                    hint = "请仔细阅读上面的编译错误输出，定位具体文件和行号。使用 `read_file` 查看出错的代码，分析是语法错误、包导入缺失还是变量未声明，然后修正。";
                }
            }
        }

        // 如果没有匹配到特定特征，原样返回原始错误；
        // 如果匹配到了，拼接成强有力的、带有浓厚“系统指导意味”的行动指南。
        if (hint == null) {
            return rawError;
        }

        return rawError + "\n\n⛑️ [系统级错误自愈提示]:\n" + hint
                + "\n(重要程度: HIGH。请严格遵循上述建议，不要尝试重复同样的操作)";
    }
}
