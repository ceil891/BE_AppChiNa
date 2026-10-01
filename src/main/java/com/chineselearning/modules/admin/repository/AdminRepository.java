package com.chineselearning.modules.admin.repository;
import com.chineselearning.modules.admin.dto.AdminRequests.*;
import com.chineselearning.modules.lesson.dto.Word;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class AdminRepository {
    private final JdbcClient jdbc;
    public AdminRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    private static final String WORD_COLUMNS="id,hanzi,pinyin,meaning_vi,example_hanzi,example_pinyin,example_meaning_vi";
    public List<Word> words() { return jdbc.sql("SELECT "+WORD_COLUMNS+" FROM vocabulary ORDER BY id").query(Word.class).list(); }
    public Optional<Word> word(long id) { return jdbc.sql("SELECT "+WORD_COLUMNS+" FROM vocabulary WHERE id=:id").param("id",id).query(Word.class).optional(); }
    public Word createWord(VocabularyInput v) {
        return wordParams(jdbc.sql("INSERT INTO vocabulary(hanzi,pinyin,meaning_vi,example_hanzi,example_pinyin,example_meaning_vi) VALUES (:hanzi,:pinyin,:meaning,:example,:examplePinyin,:exampleMeaning) RETURNING "+WORD_COLUMNS),v).query(Word.class).single();
    }
    public Optional<Word> updateWord(long id,VocabularyInput v) {
        return wordParams(jdbc.sql("UPDATE vocabulary SET hanzi=:hanzi,pinyin=:pinyin,meaning_vi=:meaning,example_hanzi=:example,example_pinyin=:examplePinyin,example_meaning_vi=:exampleMeaning WHERE id=:id RETURNING "+WORD_COLUMNS).param("id",id),v).query(Word.class).optional();
    }
    private JdbcClient.StatementSpec wordParams(JdbcClient.StatementSpec query,VocabularyInput v) {
        return query.param("hanzi",v.hanzi().strip()).param("pinyin",v.pinyin().strip()).param("meaning",v.meaningVi().strip())
            .param("example",v.exampleHanzi().strip()).param("examplePinyin",v.examplePinyin().strip()).param("exampleMeaning",v.exampleMeaningVi().strip());
    }
    public int deleteWord(long id) { return jdbc.sql("DELETE FROM vocabulary WHERE id=:id").param("id",id).update(); }
    public long createLesson(LessonInput l) {
        return jdbc.sql("INSERT INTO lessons(title,description,position) VALUES (:title,:description,:position) RETURNING id")
            .param("title",l.title().strip()).param("description",l.description().strip()).param("position",l.position()).query(Long.class).single();
    }
    public boolean lockLesson(long id) {
        return jdbc.sql("SELECT id FROM lessons WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional().isPresent();
    }
    public void updateLesson(long id,LessonInput l) {
        jdbc.sql("UPDATE lessons SET title=:title,description=:description,position=:position WHERE id=:id")
            .param("title",l.title().strip()).param("description",l.description().strip()).param("position",l.position()).param("id",id).update();
    }
    public void clearWords(long lesson) { jdbc.sql("DELETE FROM lesson_vocabulary WHERE lesson_id=:id").param("id",lesson).update(); }
    public void attachWords(long lesson,List<Long> words) {
        for(int i=0;i<words.size();i++) jdbc.sql("INSERT INTO lesson_vocabulary(lesson_id,vocabulary_id,position) VALUES (:lesson,:word,:position)")
            .param("lesson",lesson).param("word",words.get(i)).param("position",i+1).update();
    }
    public void deleteLesson(long id) { jdbc.sql("DELETE FROM lessons WHERE id=:id").param("id",id).update(); }
}

