package com.platform.energy.classification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Executes SQL semantics on H2; this is not evidence of a MySQL access plan or physical I/O. */
class MonthlyCandidateProjectionTest {
    @Test void overlapBeforeLimitMatchesFullHistoricalPaginationIncludingCrossMonthAndTies() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID())) {
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            jdbc.execute("CREATE TABLE biz_energy_manual_reading (fact_id VARCHAR(64) PRIMARY KEY, building_id VARCHAR(64) NOT NULL, start_inclusive BIGINT NOT NULL, end_exclusive BIGINT)");
            for(int i=0;i<7;i++)insert(jdbc,"history-"+i,"B",i,100);
            jdbc.update("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,NULL)","null-end","B",8);
            insert(jdbc,"cross-month","B",10,101);
            insert(jdbc,"tie-a","B",110,120);
            insert(jdbc,"tie-b","B",110,121);
            insert(jdbc,"invalid-overlap","B",150,140); // Must reach the existing window integrity check.
            insert(jdbc,"outside","B",200,201);
            insert(jdbc,"other-building","OTHER",120,130);
            var repository=new ClassificationRepository(jdbc);
            var from=Instant.ofEpochMilli(100);var to=Instant.ofEpochMilli(200);
            List<ClassificationRepository.WindowCandidate> oldRows=new ArrayList<>(),newRows=new ArrayList<>();
            int oldCalls=collect(repository,from,to,false,oldRows),newCalls=collect(repository,from,to,true,newRows);
            assertThat(newRows.stream().filter(row->row.endExclusive().isAfter(from)).toList())
                    .containsExactlyElementsOf(oldRows.stream().filter(row->row.endExclusive().isAfter(from)).toList());
            assertThat(newRows.getFirst().endExclusive()).isEqualTo(Instant.EPOCH); // Same getLong(NULL) mapping as the original path.
            assertThat(newRows).extracting(ClassificationRepository.WindowCandidate::id)
                    .containsExactly("null-end","cross-month","tie-a","tie-b","invalid-overlap");
            assertThat(oldCalls).isEqualTo(7);assertThat(newCalls).isEqualTo(3);
            assertThat(repository.overlappingWindowCandidatesPage("B",Instant.ofEpochMilli(300),Instant.ofEpochMilli(400),null,null,2,()->Duration.ofSeconds(1)))
                    .extracting(ClassificationRepository.WindowCandidate::id).containsExactly("null-end");
        }
    }

    @Test void rejectsInvalidBoundsAndPreservesExpiredBudget() {
        var repository=new ClassificationRepository(new JdbcTemplate());
        var start=Instant.ofEpochMilli(100);
        assertThatThrownBy(()->repository.overlappingWindowCandidatesPage("B",start,start,null,null,2,()->Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.overlappingWindowCandidatesPage("B",start,start.plusMillis(1),null,null,1001,()->Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.overlappingWindowCandidatesPage("B",start,start.plusMillis(1),null,null,2,()->Duration.ZERO))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    private static int collect(ClassificationRepository repository,Instant from,Instant to,boolean overlap,
                               List<ClassificationRepository.WindowCandidate> result) {
        Instant cursor=null;String id=null;int calls=0;
        while(true) {
            assertThat(++calls).isLessThan(20);
            var page=overlap?repository.overlappingWindowCandidatesPage("B",from,to,cursor,id,2,()->Duration.ofSeconds(1))
                    :repository.windowCandidatesPage("B",to,cursor,id,2,()->Duration.ofSeconds(1));
            result.addAll(page);
            if(page.size()<2)return calls;
            var last=page.getLast();cursor=last.startInclusive();id=last.id();
        }
    }
    private static void insert(JdbcTemplate jdbc,String id,String building,long from,long to) {
        jdbc.update("INSERT INTO biz_energy_manual_reading VALUES (?,?,?,?)",id,building,from,to);
    }
}
