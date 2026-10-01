package com.chineselearning;

import com.chineselearning.modules.ai.service.TutorPrompt;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ExternalTutorPromptTests {
    @TempDir Path directory;

    @Test void loadsExternalPrompt() throws Exception {
        var file=directory.resolve("prompt.txt");
        Files.writeString(file,"External instructions");
        assertThat(new TutorPrompt(file.toString()).system("lesson"))
            .startsWith("External instructions");
    }

    @Test void rejectsMissingAndEmptyExternalPrompt() throws Exception {
        var file=directory.resolve("prompt.txt");
        assertThatThrownBy(()->new TutorPrompt(file.toString())).isInstanceOf(java.io.IOException.class);
        Files.writeString(file,"  \n");
        assertThatThrownBy(()->new TutorPrompt(file.toString())).isInstanceOf(java.io.IOException.class);
    }
}
