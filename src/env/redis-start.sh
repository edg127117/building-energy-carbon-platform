#!/bin/sh
set -eu
umask 077

# ACL 只写入临时文件中的密码哈希；真实密码由受保护环境注入，禁止记录到命令行或日志。
: "${REDIS_PASSWORD:?Redis password is required}"
REDIS_USER=${REDIS_USER:-platform_app}
case "$REDIS_USER" in default|*[!a-zA-Z0-9_-]*|'') echo INVALID_REDIS_USER >&2; exit 1;; esac
[ "${#REDIS_PASSWORD}" -ge 16 ] || { echo INVALID_REDIS_PASSWORD >&2; exit 1; }
hash=$(printf %s "$REDIS_PASSWORD" | sha256sum | cut -d' ' -f1)
acl=$(mktemp /tmp/platform-redis-acl.XXXXXX)
# Spring Data 的带 TTL 写入实际使用 SETEX/PSETEX，不能只允许基础 SET。
permissions=$(tr -d '\r' < /usr/local/etc/platform-redis-permissions)
printf 'user default off\nuser %s reset on #%s %s\n' "$REDIS_USER" "$hash" "$permissions" > "$acl"
# 数据目录及 AOF 沿用既有卷；Lua 内部 GET/DEL 也须列入白名单，供 S01 比较删除使用。
if [ "$(id -u)" = 0 ]; then chown redis:redis "$acl"; fi
# 沿用官方入口的数据目录属主检查和降权，不能因生成 ACL 而让 Redis 以 root 常驻。
exec /usr/local/bin/docker-entrypoint.sh redis-server --appendonly yes --aclfile "$acl"
