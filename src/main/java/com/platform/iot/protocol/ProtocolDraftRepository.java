package com.platform.iot.protocol;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** MySQL 草稿持久层；条件更新将修订检查和写入合为一次原子操作。 */
@Repository
public class ProtocolDraftRepository {
    private final JdbcTemplate jdbc;
    public ProtocolDraftRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Row(String id, long revision, String json, long updatedAt) {}
    private static final RowMapper<Row> ROW = (rs, n) -> new Row(
            rs.getString("draft_id"), rs.getLong("revision"), rs.getString("configuration_json"), rs.getLong("updated_at"));

    public void insert(Row row, Long operator) {
        jdbc.update("INSERT INTO biz_protocol_draft (draft_id,revision,configuration_json,updated_at,updated_by) VALUES (?,?,?,?,?)",
                row.id(),row.revision(),row.json(),row.updatedAt(),operator);
    }
    public int update(Row row, long expected, Long operator) {
        return jdbc.update("UPDATE biz_protocol_draft SET revision=?,configuration_json=?,updated_at=?,updated_by=? WHERE draft_id=? AND revision=?",
                row.revision(),row.json(),row.updatedAt(),operator,row.id(),expected);
    }
    public Optional<Row> find(String id, boolean lock) {
        return jdbc.query("SELECT * FROM biz_protocol_draft WHERE draft_id=?" + (lock ? " FOR UPDATE" : ""), ROW, id)
                .stream().findFirst();
    }
    public boolean hasFrozenVersions(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM biz_protocol_version WHERE draft_id=?)", Boolean.class, id));
    }
    public void delete(String id) {
        jdbc.update("DELETE FROM biz_protocol_draft WHERE draft_id=?", id);
    }

    public record Page(long total, List<Row> rows) {}

    public Page list(String keyword, String productId, int size, long offset) {
        var where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<>();
        if (keyword != null) {
            where.append("""
                     AND (LOCATE(LOWER(?), LOWER(JSON_UNQUOTE(JSON_EXTRACT(configuration_json,'$.name')))) > 0
                       OR LOCATE(LOWER(?), LOWER(JSON_UNQUOTE(JSON_EXTRACT(configuration_json,'$.profileCode')))) > 0)
                    """);
            args.add(keyword);
            args.add(keyword);
        }
        if (productId != null) {
            where.append(" AND JSON_CONTAINS(configuration_json,JSON_QUOTE(?),'$.productId') = 1");
            args.add(productId);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM biz_protocol_draft" + where, Long.class, args.toArray());
        args.add(size);
        args.add(offset);
        var rows = jdbc.query("SELECT * FROM biz_protocol_draft" + where + " ORDER BY updated_at DESC,draft_id LIMIT ? OFFSET ?",
                ROW, args.toArray());
        return new Page(total, rows);
    }
}
