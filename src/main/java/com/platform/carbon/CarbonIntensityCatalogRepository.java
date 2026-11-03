package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import static com.platform.carbon.CarbonIntensityContracts.*;

/** 版本和条目只追加；旧版本可按ID读取，不能被一次目录维护覆盖。 */
@Repository
class CarbonIntensityCatalogRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    CarbonIntensityCatalogRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    List<Version> versions() {
        return jdbc.query("SELECT * FROM biz_carbon_intensity_standard_version ORDER BY catalog_id,revision_no DESC LIMIT 1001",
                (r,n)->new Version(r.getString("catalog_id"),r.getString("version_id"),r.getInt("revision_no"),
                        r.getString("standard_name"),r.getString("document_version"),r.getString("source_reference"),
                        r.getDate("published_on")==null?null:r.getDate("published_on").toLocalDate(),
                        r.getLong("created_by"),r.getString("created_at"),r.getString("evidence_hash"),entries(r.getString("version_id"))));
    }
    Version latestForUpdate(String catalogId) {
        var ids=jdbc.queryForList("SELECT version_id FROM biz_carbon_intensity_standard_version WHERE catalog_id=? ORDER BY revision_no DESC LIMIT 1 FOR UPDATE",String.class,catalogId);
        return ids.isEmpty()?null:versions().stream().filter(v->v.versionId().equals(ids.getFirst())).findFirst().orElseThrow();
    }
    private List<Rule> entries(String version) {
        return jdbc.query("SELECT rule_json FROM biz_carbon_intensity_standard_entry WHERE version_id=? ORDER BY entry_no",(r,n)->{
            try { return json.readValue(r.getString(1),Rule.class); }
            catch(java.io.IOException e) { throw new IllegalStateException("碳强度目录条目无法读取",e); }
        },version);
    }
    void insert(Version version) {
        jdbc.update("INSERT INTO biz_carbon_intensity_standard_version (catalog_id,version_id,revision_no,standard_name,document_version,source_reference,published_on,created_by,evidence_hash) VALUES (?,?,?,?,?,?,?,?,?)",
                version.catalogId(),version.versionId(),version.revision(),version.name(),version.documentVersion(),version.sourceReference(),version.publishedOn(),version.createdBy(),version.evidenceHash());
        for(int i=0;i<version.entries().size();i++) {
            Rule rule=version.entries().get(i);
            jdbc.update("INSERT INTO biz_carbon_intensity_standard_entry (version_id,entry_no,region_prefix,building_category,time_status,applicable_from,applicable_to,rule_json) VALUES (?,?,?,?,?,?,?,?)",
                    version.versionId(),i,rule.regionPrefix(),rule.buildingCategory(),rule.timeStatus(),rule.applicableFrom(),rule.applicableTo(),CarbonEvidence.canonical(CarbonEvidence.tree(rule)));
        }
    }
}
