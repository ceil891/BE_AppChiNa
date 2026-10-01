package com.chineselearning.modules.flashcard.repository;

import com.chineselearning.modules.flashcard.dto.FlashcardDtos.*;
import com.chineselearning.modules.lesson.dto.Word;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class FlashcardRepository {
    public record State(UUID id,long vocabularyId,boolean archived,OffsetDateTime nextReviewAt,int successStreak,int reviewCount,long version) {}
    private final JdbcClient jdbc;
    public FlashcardRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    private static final String JOIN=" FROM user_vocabulary c JOIN vocabulary w ON w.id=c.vocabulary_id ";
    private static final String FILTER="""
        WHERE c.user_id=:owner AND NOT c.archived
        AND (strpos(lower(w.hanzi),lower(:q))>0 OR strpos(lower(w.pinyin),lower(:q))>0 OR strpos(lower(w.meaning_vi),lower(:q))>0)
        AND (:lesson=0 OR EXISTS(SELECT 1 FROM lesson_vocabulary lv WHERE lv.vocabulary_id=w.id AND lv.lesson_id=:lesson))
        """;
    private static final RowMapper<Card> CARD=(rs,index)->new Card(rs.getObject("id",UUID.class),
        new Word(rs.getLong("vocabulary_id"),rs.getString("hanzi"),rs.getString("pinyin"),rs.getString("meaning_vi"),
            rs.getString("example_hanzi"),rs.getString("example_pinyin"),rs.getString("example_meaning_vi")),
        rs.getObject("next_review_at",OffsetDateTime.class),rs.getObject("last_reviewed_at",OffsetDateTime.class),
        rs.getInt("success_streak"),rs.getInt("review_count"),rs.getLong("version"));
    private static final String FIELDS="c.*,w.hanzi,w.pinyin,w.meaning_vi,w.example_hanzi,w.example_pinyin,w.example_meaning_vi";
    private JdbcClient.StatementSpec filtered(String sql,UUID owner,String q,long lesson){
        return jdbc.sql(sql).param("owner",owner).param("q",q).param("lesson",lesson);
    }
    public List<Card> list(UUID owner,String q,long lesson,boolean dueOnly,int page,int size,OffsetDateTime now){
        return filtered("SELECT "+FIELDS+JOIN+FILTER+" AND (NOT :due OR c.next_review_at<=:now) ORDER BY c.next_review_at,c.id LIMIT :size OFFSET :offset",owner,q,lesson)
            .param("due",dueOnly).param("now",now).param("size",size).param("offset",(long)page*size).query(CARD).list();
    }
    public Stats stats(UUID owner,String q,long lesson,OffsetDateTime now){
        return filtered("SELECT COUNT(*) AS saved, COUNT(*) FILTER(WHERE c.next_review_at<=:now) AS due, COUNT(*) FILTER(WHERE c.review_count>0) AS reviewed, MIN(c.next_review_at) FILTER(WHERE c.next_review_at>:now) AS next_due_at"+JOIN+FILTER,owner,q,lesson)
            .param("now",now).query(Stats.class).single();
    }
    public Optional<Card> get(UUID owner,UUID id){
        return jdbc.sql("SELECT "+FIELDS+JOIN+" WHERE c.user_id=:owner AND c.id=:id AND NOT c.archived").param("owner",owner).param("id",id).query(CARD).optional();
    }
    public Optional<Card> byWord(UUID owner,long word){
        return jdbc.sql("SELECT "+FIELDS+JOIN+" WHERE c.user_id=:owner AND c.vocabulary_id=:word AND NOT c.archived").param("owner",owner).param("word",word).query(CARD).optional();
    }
    public UUID save(UUID owner,long word,OffsetDateTime now){
        return jdbc.sql("""
            INSERT INTO user_vocabulary(id,user_id,vocabulary_id,next_review_at) VALUES (:id,:owner,:word,:now)
            ON CONFLICT(user_id,vocabulary_id) DO UPDATE
            SET archived=FALSE,version=user_vocabulary.version+CASE WHEN user_vocabulary.archived THEN 1 ELSE 0 END
            RETURNING id
            """).param("id",UUID.randomUUID()).param("owner",owner).param("word",word).param("now",now).query(UUID.class).single();
    }
    public Optional<State> lock(UUID owner,UUID id){
        return jdbc.sql("SELECT id,vocabulary_id,archived,next_review_at,success_streak,review_count,version FROM user_vocabulary WHERE user_id=:owner AND id=:id FOR UPDATE")
            .param("owner",owner).param("id",id).query(State.class).optional();
    }
    public void archive(UUID owner,UUID id){
        jdbc.sql("UPDATE user_vocabulary SET archived=TRUE,version=version+1 WHERE user_id=:owner AND id=:id AND NOT archived").param("owner",owner).param("id",id).update();
    }
    public Optional<Review> review(UUID owner,UUID requestId){
        return jdbc.sql("SELECT * FROM flashcard_reviews WHERE user_id=:owner AND request_id=:request").param("owner",owner).param("request",requestId).query(Review.class).optional();
    }
    public void record(UUID owner,Review review){
        jdbc.sql("""
            INSERT INTO flashcard_reviews(user_id,request_id,card_id,vocabulary_id,hanzi,pinyin,meaning_vi,rating,
                request_version,result_version,reviewed_at,next_review_at,success_streak,review_count)
            VALUES (:owner,:request,:card,:word,:hanzi,:pinyin,:meaning,:rating,:before,:after,:now,:due,:streak,:count)
            """).param("owner",owner).param("request",review.requestId()).param("card",review.cardId()).param("word",review.vocabularyId())
            .param("hanzi",review.hanzi()).param("pinyin",review.pinyin()).param("meaning",review.meaningVi()).param("rating",review.rating().name())
            .param("before",review.requestVersion()).param("after",review.resultVersion()).param("now",review.reviewedAt()).param("due",review.nextReviewAt())
            .param("streak",review.successStreak()).param("count",review.reviewCount()).update();
        jdbc.sql("UPDATE user_vocabulary SET next_review_at=:due,last_reviewed_at=:now,success_streak=:streak,review_count=:count,version=:version WHERE user_id=:owner AND id=:id")
            .param("owner",owner).param("id",review.cardId()).param("due",review.nextReviewAt()).param("now",review.reviewedAt())
            .param("streak",review.successStreak()).param("count",review.reviewCount()).param("version",review.resultVersion()).update();
    }
    public List<Review> history(UUID owner,int page,int size){
        return jdbc.sql("SELECT * FROM flashcard_reviews WHERE user_id=:owner ORDER BY reviewed_at DESC,request_id LIMIT :size OFFSET :offset")
            .param("owner",owner).param("size",size).param("offset",(long)page*size).query(Review.class).list();
    }
    public long historyCount(UUID owner){return jdbc.sql("SELECT COUNT(*) FROM flashcard_reviews WHERE user_id=:owner").param("owner",owner).query(Long.class).single();}
}
