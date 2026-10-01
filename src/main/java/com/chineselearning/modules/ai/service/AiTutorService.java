package com.chineselearning.modules.ai.service;

import com.chineselearning.modules.ai.dto.AiDtos.*;
import com.chineselearning.modules.ai.provider.TutorProvider;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class AiTutorService {
    private final AiTransactions transactions;private final TutorProvider provider;
    public AiTutorService(AiTransactions transactions,TutorProvider provider) { this.transactions=transactions;this.provider=provider; }
    // Provider I/O is outside the database transaction; a persisted reservation prevents duplicate calls.
    public Turn send(String email,UUID conversation,Send input) {
        var r=transactions.reserve(email,conversation,input);
        if(!r.dispatch())return r.turn();
        TutorProvider.Reply reply;
        try { reply=provider.generate(r.system(),r.history(),r.model()); }
        catch(TutorProvider.Failure ex) { return transactions.finish(r,null,ex.code()); }
        catch(RuntimeException ex) { return transactions.finish(r,null,"PROVIDER_ERROR"); }
        return transactions.finish(r,reply,null);
    }
}
