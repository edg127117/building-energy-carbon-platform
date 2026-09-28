package com.platform.weather;

import com.platform.config.TdengineProperties;
import com.platform.weather.source.WeatherSourceModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.platform.weather.WeatherModels.*;

/** TDengine 子表按不可变数据集隔离；不使用同有效时间覆盖其他预报批次。 */
@Repository
public class WeatherTimeseries {
    private final JdbcTemplate jdbc;
    private final String database;
    private final WeatherCodec codec;
    public WeatherTimeseries(@Qualifier("taosJdbcTemplate") JdbcTemplate jdbc,TdengineProperties p,WeatherCodec codec) {
        this.jdbc=jdbc;this.database=identifier(p.getDatabase());this.codec=codec;
    }
    static String identifier(String s) {
        if(s==null || !s.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))throw new IllegalArgumentException("INVALID_SQL_IDENTIFIER");
        return s;
    }
    static String datasetId(String id) {
        if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("INVALID_DATASET_ID");
        return id;
    }
    private String table(Dataset d) { return database+"."+identifier(d.tableName()); }
    private String stable(Product p) {
        return database+"."+switch(p) {
            case CURRENT -> "st_weather_current_v1";
            case FORECAST_HOURLY,HISTORY_HOURLY -> "st_weather_hourly_v1";
            default -> "st_weather_daily_v1";
        };
    }
    public void write(Dataset data) {
        String stable=stable(data.product());
        boolean current=data.product()==Product.CURRENT;
        String tags=current?"binding_id NCHAR(32),source NCHAR(24)":"dataset_id NCHAR(32)";
        jdbc.execute("CREATE STABLE IF NOT EXISTS "+stable+" (ts TIMESTAMP,temperature DOUBLE,humidity DOUBLE,precipitation DOUBLE,radiation DOUBLE,wind_speed DOUBLE,wind_direction DOUBLE,payload NCHAR(4096)) TAGS("+tags+")");
        String tagValues=current?"'"+datasetId(data.bindingId())+"','"+data.source().name()+"'":"'"+datasetId(data.id())+"'";
        jdbc.execute("CREATE TABLE IF NOT EXISTS "+table(data)+" USING "+stable+" TAGS ("+tagValues+")");
        Set<Long> seen=new HashSet<>();
        for(Sample sample:data.result().samples()) {
            long time=(data.product()==Product.CURRENT?data.fetchedAt():sample.time()).toEpochMilli();
            if(!seen.add(time))throw new IllegalArgumentException("DUPLICATE_WEATHER_TIME");
            String payload=codec.json(sample);
            if(payload.length()>4096)throw new IllegalArgumentException("WEATHER_SAMPLE_TOO_LARGE");
            jdbc.update("INSERT INTO "+table(data)+" VALUES(?,?,?,?,?,?,?,?)",time,
                    sample.values().get("temperature_2m"),sample.values().get("relative_humidity_2m"),
                    sample.values().get("precipitation"),sample.values().get("shortwave_radiation"),
                    sample.values().get("wind_speed_10m"),sample.values().get("wind_direction_10m"),payload);
        }
        // 不只检查写调用成功：读回行数及载荷，避免半批数据被发布。
        var actual=read(data);
        if(!codec.hash(actual).equals(codec.hash(data.result().samples().stream().sorted(Comparator.comparing(Sample::time)).toList())))
            throw new IllegalStateException("WEATHER_WRITE_VERIFICATION_FAILED");
    }
    public List<Sample> read(Dataset data) {
        return jdbc.query("SELECT payload FROM "+table(data)+(data.product()==Product.CURRENT?" WHERE ts="+data.fetchedAt().toEpochMilli():"")+" ORDER BY ts",(r,n)->codec.read(r.getString(1),Sample.class));
    }
    public void drop(Dataset data) {
        // 当前天气按位置/月共享子表，只清理该批次行，避免误删同月其他有效数据。
        if(data.product()==Product.CURRENT)jdbc.execute("DELETE FROM "+table(data)+" WHERE ts="+data.fetchedAt().toEpochMilli());
        else jdbc.execute("DROP TABLE IF EXISTS "+table(data));
    }
}
