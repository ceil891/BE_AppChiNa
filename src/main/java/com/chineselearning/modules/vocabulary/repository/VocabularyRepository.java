package com.chineselearning.modules.vocabulary.repository;

import com.chineselearning.modules.lesson.dto.Word;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class VocabularyRepository {
    private final JdbcClient jdbc;
    public VocabularyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String FILTER = """
        FROM vocabulary v WHERE
        (:lesson = 0 OR EXISTS (SELECT 1 FROM lesson_vocabulary lv WHERE lv.vocabulary_id = v.id AND lv.lesson_id = :lesson))
        AND (:q = '' OR strpos(lower(v.hanzi), lower(:q)) > 0
          OR strpos(lower(v.meaning_vi), lower(:q)) > 0
          OR strpos(translate(lower(v.pinyin), 'āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü', 'aaaaeeeeiiiioooouuuuvvvvv'),
                    translate(lower(:q), 'āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü', 'aaaaeeeeiiiioooouuuuvvvvv')) > 0)
        """;
    private static final String FIELDS = "v.id, v.hanzi, v.pinyin, v.meaning_vi, v.example_hanzi, v.example_pinyin, v.example_meaning_vi";

    public List<Word> search(String query, long lesson, int page, int size) {
        return jdbc.sql("SELECT " + FIELDS + " " + FILTER + " ORDER BY v.id LIMIT :size OFFSET :offset")
            .param("q", query).param("lesson", lesson).param("size", size).param("offset", (long) page * size)
            .query(Word.class).list();
    }
    public long count(String query, long lesson) {
        return jdbc.sql("SELECT COUNT(*) " + FILTER).param("q", query).param("lesson", lesson).query(Long.class).single();
    }
    public Optional<Word> find(long id) {
        return jdbc.sql("SELECT " + FIELDS + " FROM vocabulary v WHERE v.id = :id").param("id", id).query(Word.class).optional();
    }
    public List<Long> lessonIds(long id) {
        return jdbc.sql("SELECT lesson_id FROM lesson_vocabulary WHERE vocabulary_id = :id")
            .param("id", id).query(Long.class).list();
    }
}
