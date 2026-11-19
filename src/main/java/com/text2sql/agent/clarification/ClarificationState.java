package com.text2sql.agent.clarification;

import java.util.ArrayList;
import java.util.List;

/** Server-owned, session-local choices. Latest selection replaces only the same rule/group. */
public record ClarificationState(String question, List<Confirmation> confirmations) {
    public ClarificationState {
        confirmations = List.copyOf(confirmations);
    }

    public record Confirmation(String term, String group, String reply, String canonical) { }

    public ClarificationState with(Confirmation confirmation) {
        var next = new ArrayList<>(confirmations);
        next.removeIf(c -> c.term().equals(confirmation.term()) && c.group().equals(confirmation.group()));
        next.add(confirmation);
        return new ClarificationState(question, next);
    }

    public String effectiveQuestion() {
        if (confirmations.isEmpty()) return question;
        return question + "\n用户已确认的口径（覆盖原问题中相应候选口径，不改变其他要求）："
                + String.join("；", confirmations.stream().map(Confirmation::canonical).toList());
    }
}
