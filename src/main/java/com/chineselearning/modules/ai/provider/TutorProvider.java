package com.chineselearning.modules.ai.provider;

import java.util.List;

public interface TutorProvider {
    record Message(String role,String text) {}
    record Reply(String text,int inputTokens,int outputTokens,int totalTokens) {}
    Reply generate(String system,List<Message> history);
    default Reply generate(String system,List<Message> history,String model) { return generate(system,history); }
    final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code) { super(code); this.code=code; }
        public String code() { return code; }
    }
}
