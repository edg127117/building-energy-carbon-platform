package com.platform.tools;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class D01RoomSensorBootstrapTest {
    private static final String SCRIPT = """
            DELIMITER //
            CREATE PROCEDURE configure_b314_room_sensors(IN target_building VARBINARY(32), IN target_space VARBINARY(32))
            BEGIN
                START TRANSACTION;
                COMMIT;
            END//
            DELIMITER ;
            CALL configure_b314_room_sensors(@building_id,@space_id);
            DROP PROCEDURE configure_b314_room_sensors;
            """;

    @Test
    void createsCallsWithBoundIdsAndDropsOnlyAfterSuccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        D01RoomSensorBootstrap.bootstrap(jdbc, SCRIPT, "B314_BUILDING", "B314_SPACE");

        verify(jdbc).execute("CREATE PROCEDURE configure_b314_room_sensors(IN target_building VARBINARY(32), IN target_space VARBINARY(32))\n"
                + "BEGIN\n    START TRANSACTION;\n    COMMIT;\nEND");
        verify(jdbc).update("CALL configure_b314_room_sensors(?,?)", "B314_BUILDING", "B314_SPACE");
        verify(jdbc).execute("DROP PROCEDURE configure_b314_room_sensors");
    }

    @Test
    void callFailureLeavesProcedureForInspectionWithoutRetryOrCleanup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq("B314_BUILDING"), eq("B314_SPACE")))
                .thenThrow(new DataAccessResourceFailureException("fixture"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> D01RoomSensorBootstrap.bootstrap(jdbc, SCRIPT, "B314_BUILDING", "B314_SPACE"));

        verify(jdbc).execute(org.mockito.ArgumentMatchers.startsWith("CREATE PROCEDURE "));
        verify(jdbc, never()).execute("DROP PROCEDURE configure_b314_room_sensors");
    }

    @Test
    void invalidIdsAndNonmatchingScriptAreRejectedBeforeSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThrows(IllegalArgumentException.class,
                () -> D01RoomSensorBootstrap.bootstrap(jdbc, SCRIPT, "bad id", "B314_SPACE"));
        assertThrows(IllegalArgumentException.class,
                () -> D01RoomSensorBootstrap.bootstrap(jdbc, SCRIPT + "DELETE FROM building;", "B314_BUILDING", "B314_SPACE"));
        assertThrows(IllegalArgumentException.class,
                () -> D01RoomSensorBootstrap.bootstrap(jdbc, SCRIPT.replace("configure_b314_room_sensors", "other_proc"), "B314_BUILDING", "B314_SPACE"));

        verifyNoInteractions(jdbc);
    }
}
