package com.text2sql.agent.semantic;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded lexical evidence next to a value; never a claim of general language understanding. */
public final class AdjacentContext {
    private AdjacentContext() { }

    public record Evidence(String term, int start, int end) { }

    /** Offsets address the original string. Only spacing, assignment and enumeration may bridge a cue. */
    public static Evidence find(String question, int start, int end, List<String> cues, List<String> siblingValues) {
        for (String cue : cues.stream().filter(c -> c != null && !c.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            var matcher = Pattern.compile(Pattern.quote(cue), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(question);
            while (matcher.find()) {
                int at = matcher.start(), cueEnd = matcher.end();
                String gap;
                if (cueEnd <= start) gap = question.substring(cueEnd, start);
                else if (at >= end) {
                    gap = question.substring(end, at);
                    // A trailing cue cannot reach backwards across a clause/enumeration boundary.
                    // Otherwise "GMV中，优先级为高" would also authorize priority=中.
                    if (!gap.matches("[\\s的]*")) continue;
                }
                else continue; // A substring inside the matched label is not independent context evidence.
                if (gap.length() > 32) continue;
                for (String sibling : siblingValues.stream().filter(v -> v != null && !v.isBlank())
                        .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                    gap = Pattern.compile(Pattern.quote(sibling), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(gap).replaceAll("");
                }
                if (gap.matches("[\\s的为是等于属于包含包括中有值取且和或及与、，,：:=‘’'“”\"（）()\\[\\]]*")) {
                    return new Evidence(question.substring(at, cueEnd), at, cueEnd);
                }
            }
        }
        return null;
    }
}
