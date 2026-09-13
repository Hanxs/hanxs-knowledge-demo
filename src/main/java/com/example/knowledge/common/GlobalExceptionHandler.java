package com.example.knowledge.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：避免内部异常细节直接暴露给调用方。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 开发环境透出真实异常详情，便于定位问题；生产环境务必保持关闭 */
    @Value("${app.error.show-detail:false}")
    private boolean showDetail;

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail(400, "缺少必要参数: " + e.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        String friendly = friendlyMessage(e);
        // 鉴权失败属于可预期的配置问题，无需打印完整堆栈，避免日志被刷屏
        if (friendly.startsWith("模型服务鉴权失败")) {
            log.warn("请求失败：{}", friendly);
        } else {
            log.error("请求处理失败", e);
        }
        return ApiResponse.fail(500, showDetail ? friendly + " ｜ 详情：" + detail(e) : friendly);
    }

    /** 取异常链最深一层的类型与消息，作为排查线索 */
    private String detail(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        String text = (msg == null || msg.isBlank())
                ? cur.toString()
                : cur.getClass().getSimpleName() + ": " + msg;
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /**
     * 沿异常链识别常见的模型服务故障，返回可操作的中文提示。
     * Spring AI 常把真实原因包装在 IllegalStateException("Stream processing failed") 之下，
     * 因此需要逐级回溯 cause。
     */
    private String friendlyMessage(Throwable t) {
        Throwable cur = t;
        for (int i = 0; cur != null && i < 10; i++) {
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("401") || m.contains("api key") || m.contains("unauthorized")) {
                    return "模型服务鉴权失败：未配置或无效的 API Key。请在 src/main/resources/application-local.yml 中填写密钥，或设置环境变量 DASHSCOPE_API_KEY，然后重启应用。";
                }
                if (m.contains("404") || m.contains("not found")) {
                    return "请求的资源不存在（404），请检查 spring.ai.openai.base-url 与模型名称。";
                }
                if (m.contains("400") || m.contains("invalid_request") || m.contains("bad request")) {
                    return "模型服务拒绝请求（400），请检查模型名称或请求参数。";
                }
                if (m.contains("failed to connect") || m.contains("unable to resolve") || m.contains("unknownhost")) {
                    return "无法连接模型服务，请检查网络或 spring.ai.openai.base-url 配置。";
                }
                if (m.contains("quota") || m.contains("insufficient") || m.contains("arrearage")) {
                    return "模型服务额度不足，请检查 DashScope 账户余额。";
                }
                if (m.contains("rate limit") || m.contains("429")) {
                    return "模型服务限流，请稍后重试。";
                }
                if (m.contains("connection refused") || m.contains("timed out") || m.contains("timeout")) {
                    return "无法连接模型服务，请检查网络或 spring.ai.openai.base-url 配置。";
                }
            }
            cur = cur.getCause();
        }
        return "服务内部错误，请稍后重试";
    }
}
