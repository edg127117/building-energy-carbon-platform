package com.platform.cache;

/**
 * Redis 登录态校验结果。
 *
 * <p>使用三态而不是布尔值，是为了区分“Token 确实已失效”和“Redis 暂时不可用”。
 * 后者允许 JWT 过滤器退化为仅校验签名及过期时间，避免缓存故障导致全站无法访问。</p>
 */
public enum TokenValidationResult {
    /** Token 与当前用户白名单中的有效 Token 一致。 */
    ACTIVE,
    /** Token 已被拉黑、被新登录替换，或不在当前白名单中。 */
    REJECTED,
    /** Redis 访问失败，调用方应执行约定的降级策略。 */
    CACHE_UNAVAILABLE
}
