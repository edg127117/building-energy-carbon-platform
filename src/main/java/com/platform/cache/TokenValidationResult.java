package com.platform.cache;

/**
 * Redis 登录态校验结果。
 *
 * <p>使用三态而不是布尔值，是为了区分“Token 确实已失效”和“Redis 暂时不可用”。
 * 两种结果都不能建立身份；调用方可据此区分重新登录与依赖故障。</p>
 */
public enum TokenValidationResult {
    /** Token 与当前用户白名单中的有效 Token 一致。 */
    ACTIVE,
    /** Token 已被拉黑、被新登录替换，或不在当前白名单中。 */
    REJECTED,
    /** Redis 访问失败，调用方必须拒绝授权。 */
    CACHE_UNAVAILABLE
}
