package com.chineselearning;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProgressMigrationTests {
    @Autowired DataSource dataSource;
    @Test void v11ImportsExistingActivitiesWithOriginalVietnamDatesAndNoDuplicates() {
        String schema="progress_migration_"+UUID.randomUUID().toString().replace("-","");
        var sql=JdbcClient.create(dataSource);UUID owner=UUID.randomUUID();UUID other=UUID.randomUUID();
        String ownerValue="'"+owner+"'::uuid", otherValue="'"+other+"'::uuid";
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("10").load().migrate();
            sql.sql("INSERT INTO "+schema+".users(id,email,password_hash,display_name) VALUES ("+ownerValue+",'backfill-a@example.test','fixture','A'),("+otherValue+",'backfill-b@example.test','fixture','B')").update();
            sql.sql("INSERT INTO "+schema+".learning_records(id,user_id,lesson_id,completed_at) VALUES (gen_random_uuid(),"+ownerValue+",1,'2026-09-14T16:59:59Z'),(gen_random_uuid(),"+ownerValue+",2,NULL),(gen_random_uuid(),"+otherValue+",2,'2026-09-14T17:00:00Z')").update();
            UUID card=UUID.randomUUID();
            sql.sql("INSERT INTO "+schema+".user_vocabulary(id,user_id,vocabulary_id) VALUES (:id,:owner,1)").param("id",card).param("owner",owner).update();
            sql.sql("""
                INSERT INTO %s.flashcard_reviews(request_id,user_id,card_id,vocabulary_id,hanzi,pinyin,meaning_vi,rating,
                    request_version,result_version,reviewed_at,next_review_at,success_streak,review_count)
                VALUES (gen_random_uuid(),:owner,:card,1,'你好','nǐ hǎo','Xin chào','AGAIN',0,1,'2026-09-14T17:00:00Z','2026-09-14T17:10:00Z',0,1)
                """.formatted(schema)).param("owner",owner).param("card",card).update();
            sql.sql("""
                INSERT INTO %s.quiz_attempts(id,user_id,request_id,source_set_id,title,question_count,created_at,submitted_at,correct_count,score)
                VALUES (gen_random_uuid(),:owner,gen_random_uuid(),1,'Quiz snapshot',5,'2026-09-14T16:00:00Z','2026-09-14T17:00:01Z',4,80),
                    (gen_random_uuid(),:owner,gen_random_uuid(),1,'Unsubmitted',5,'2026-09-14T16:00:00Z',NULL,NULL,NULL)
                """.formatted(schema)).param("owner",owner).update();
            var migration=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("11").load();
            migration.migrate();
            assertThat(sql.sql("SELECT COUNT(*) FROM "+schema+".learning_events").query(Long.class).single()).isEqualTo(4);
            assertThat(sql.sql("SELECT COUNT(*) FROM "+schema+".learning_events WHERE user_id=:owner").param("owner",owner).query(Long.class).single()).isEqualTo(3);
            assertThat(sql.sql("SELECT time_zone FROM "+schema+".users WHERE id=:owner").param("owner",owner).query(String.class).single()).isEqualTo("Asia/Ho_Chi_Minh");
            assertThat(sql.sql("SELECT study_date FROM "+schema+".learning_events WHERE user_id=:owner AND kind='LESSON_COMPLETED'").param("owner",owner).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026,9,14));
            assertThat(sql.sql("SELECT study_date FROM "+schema+".learning_events WHERE user_id=:owner AND kind='FLASHCARD_REVIEWED'").param("owner",owner).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026,9,15));
            assertThat(sql.sql("SELECT score FROM "+schema+".learning_events WHERE user_id=:owner AND kind='QUIZ_SUBMITTED'").param("owner",owner).query(Integer.class).single()).isEqualTo(80);
            assertThat(sql.sql("SELECT COUNT(*) FROM "+schema+".learning_events WHERE study_date<>(occurred_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date").query(Long.class).single()).isZero();
            migration.migrate();assertThat(sql.sql("SELECT COUNT(*) FROM "+schema+".learning_events").query(Long.class).single()).isEqualTo(4);
        } finally {
            // Only the uniquely named schema created by this test is eligible for cleanup.
            if(!schema.matches("progress_migration_[0-9a-f]{32}")) throw new IllegalStateException("Unexpected test schema");
            sql.sql("DROP SCHEMA IF EXISTS "+schema+" CASCADE").update();
        }
    }
}
