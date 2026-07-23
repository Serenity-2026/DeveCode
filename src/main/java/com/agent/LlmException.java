package com.agent;
//用静态内部类而不是独立文件，因为这些异常类型只在 LlmException 的上下文中有意义。基类 LlmException 本身也能直接实例化，
// 充当通用错误，不属于上述四种的错误都归到基类。这样异常体系既有分类又有兜底，上层 catch 时可以精确匹配也可以统一处理。
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class AuthenticationException extends LlmException {

        public AuthenticationException(String message) {
            super(message);
        }
    }

    public static class RateLimitException extends LlmException {
        private final String retryAfter;

        public RateLimitException(String message, String retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public String getRetryAfter() { return retryAfter; }
    }

    public static class ContextTooLongException extends LlmException {
        public ContextTooLongException(String message) {
            super(message);
        }
    }

    public static class NetworkException extends LlmException {
        public NetworkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

