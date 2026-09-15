package com.platform.iot.protocol;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/** MySQL 草稿持久层；条件更新将修订检查和写入合为一次原子操作。 */
@Repository
public class ProtocolDraftRepository {
    private final JdbcTemplate jdbc;
    public ProtocolDraftRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Row(String id, long revision, String json, long updatedAt) {}
    public void insert(Row row, Long operator) {
        jdbc.update("INSERT INTO biz_protocol_draft (draft_id,revision,configuration_json,updated_at,updated_by) VALUES (?,?,?,?,?)",
                row.id(),row.revision(),row.json(),row.updatedAt(),operator);
    }
    public int update(Row row, long expected, Long operator) {
        return jdbc.update("UPDATE biz_protocol_draft SET revision=?,configuration_json=?,updated_at=?,updated_by=? WHERE draft_id=? AND revision=?",
                row.revision(),row.json(),row.updatedAt(),operator,row.id(),expected);
    }
    public Optional<Row> find(String id) {
        return jdbc.query("SELECT * FROM biz_protocol_draft WHERE draft_id=?",
                (rs,n)->new Row(rs.getString("draft_id"),rs.getLong("revision"),rs.getString("configuration_json"),rs.getLong("updated_at")),id).stream().findFirst();
    }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM biz_protocol_draft",Long.class); }
    public List<Row> list(int size,long offset) {
        return jdbc.query("SELECT * FROM biz_protocol_draft ORDER BY updated_at DESC,draft_id LIMIT ? OFFSET ?",
                (rs,n)->new Row(rs.getString("draft_id"),rs.getLong("revision"),rs.getString("configuration_json"),rs.getLong("updated_at")),size,offset);
    }
}
