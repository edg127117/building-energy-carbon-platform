package com.platform.tools;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 受控绑定已登记的 B314 传感器资产；不创建空间、改动旧资产或接入 TDengine。 */
public final class D01RoomSensorBootstrap {
    private static final String PROCEDURE = "configure_b314_room_sensors";
    private static final String RESOURCE = "db/room-sensor/configure-b314.sql";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern SCRIPT = Pattern.compile("(?is)^\\s*DELIMITER\\s+//\\s*"
            + "(CREATE\\s+PROCEDURE\\s+configure_b314_room_sensors\\s*\\(.*?\\bEND)//\\s*"
            + "DELIMITER\\s+;\\s*CALL\\s+configure_b314_room_sensors\\s*\\(\\s*@building_id\\s*,\\s*@space_id\\s*\\)\\s*;\\s*"
            + "DROP\\s+PROCEDURE\\s+configure_b314_room_sensors\\s*;\\s*$");

    private D01RoomSensorBootstrap() { }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("D01_ROOM_SENSOR_NO_ARGUMENTS_ALLOWED");
        try {
            run(System.getenv());
        } catch (RuntimeException ex) {
            // JDBC异常可能携带连接信息或SQL值，只保留稳定错误类别。
            throw new IllegalStateException("D01_ROOM_SENSOR_BOOTSTRAP_FAILED:" + ex.getClass().getSimpleName());
        }
    }

    static void run(Map<String, String> environment) {
        String url = required(environment, "D01_JDBC_URL");
        if (!url.startsWith("jdbc:mysql://") || url.matches("(?is).*[?&](user|password)=.*")) {
            throw new IllegalArgumentException("D01_JDBC_URL_WITHOUT_CREDENTIALS_REQUIRED");
        }
        required(environment, "D01_JDBC_USER");
        required(environment, "D01_JDBC_PASSWORD");
        if (!"true".equals(environment.get("D01_WRITERS_FROZEN"))) {
            throw new IllegalStateException("D01_WRITERS_MUST_BE_FROZEN");
        }
        String building = validId(environment, "D01_ROOM_BUILDING_ID");
        String space = validId(environment, "D01_ROOM_SPACE_ID");

        var source = new DriverManagerDataSource(url, environment.get("D01_JDBC_USER"), environment.get("D01_JDBC_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        D01SchemaMigration.run("validate121", environment);
        bootstrap(jdbc, readScript(), building, space);
    }

    static void bootstrap(JdbcTemplate jdbc, String script, String building, String space) {
        requireId(building, "D01_ROOM_BUILDING_ID");
        requireId(space, "D01_ROOM_SPACE_ID");
        Matcher matcher = SCRIPT.matcher(stripLineComments(script));
        if (!matcher.matches()) throw new IllegalArgumentException("D01_ROOM_SENSOR_SCRIPT_INVALID");

        jdbc.execute(matcher.group(1).trim());
        jdbc.update("CALL " + PROCEDURE + "(?,?)", building, space);
        jdbc.execute("DROP PROCEDURE " + PROCEDURE);
    }

    private static String readScript() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("D01_ROOM_SENSOR_SCRIPT_UNAVAILABLE");
        }
    }

    private static String stripLineComments(String script) {
        return script.replaceAll("(?m)^\\s*--[^\\r\\n]*$", "");
    }

    private static String validId(Map<String, String> environment, String key) {
        String value = required(environment, key);
        requireId(value, key);
        return value;
    }

    private static void requireId(String value, String key) {
        if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("D01_INVALID_" + key);
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("D01_REQUIRED_" + key);
        return value;
    }
}
