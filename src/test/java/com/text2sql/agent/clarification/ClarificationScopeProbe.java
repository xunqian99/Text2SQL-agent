package com.text2sql.agent.clarification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;

/** Offline original-200 rule audit; this never constructs or calls a model. */
public final class ClarificationScopeProbe {
    public static void main(String[] args) throws Exception {
        var props = new AgentProperties(); props.getEval().setSplit("all");
        var loader = new EvalItemLoader(props); var detector = new AmbiguityDetector(); detector.load();
        var items = new ArrayList<Object>();
        for (var item : loader.load()) items.add(Map.of("id", item.id(), "question", item.question(),
                "needsClarification", detector.clarificationFor(item.question()).isPresent(),
                "decisions", detector.decisionsFor(item.question())));
        Files.writeString(Path.of(args[0]), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "datasetSha256", loader.datasetSha256(props.getEval().getDir()), "items", items,
                "modelCalls", 0, "modelCostYuan", 0)) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
