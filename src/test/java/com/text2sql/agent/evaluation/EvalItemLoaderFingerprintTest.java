package com.text2sql.agent.evaluation;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalItemLoaderFingerprintTest {

    @TempDir
    Path tempDir;

    private final EvalItemLoader loader = new EvalItemLoader(new AgentProperties());

    @Test
    @DisplayName("dataset fingerprint covers sorted evaluation YAML contents only")
    void fingerprintsEvaluationFilesAndIgnoresUnrelatedFiles() throws IOException {
        Files.writeString(tempDir.resolve("T2_sample.yaml"), "- id: T2-001\n");
        Files.writeString(tempDir.resolve("T1_sample.yml"), "- id: T1-001\n");
        String initial = loader.datasetSha256(tempDir.toString());

        Files.writeString(tempDir.resolve("notes.txt"), "not part of the benchmark");
        assertThat(loader.datasetSha256(tempDir.toString())).isEqualTo(initial);

        Files.writeString(tempDir.resolve("T1_sample.yml"), "- id: T1-002\n");
        assertThat(loader.datasetSha256(tempDir.toString())).isNotEqualTo(initial);
        assertThat(initial).hasSize(64);
    }

    @Test
    @DisplayName("selected sample fingerprint is stable across item ordering")
    void fingerprintsSortedSampleIds() {
        EvalItem first = item("T1-001");
        EvalItem second = item("T2-003");

        String forward = EvalItemLoader.sampleIdsSha256(List.of(first, second));
        String reverse = EvalItemLoader.sampleIdsSha256(List.of(second, first));

        assertThat(forward).isEqualTo(reverse).hasSize(64);
        assertThat(EvalItemLoader.sampleIdsSha256(List.of(first)))
                .isNotEqualTo(forward);
    }

    private static EvalItem item(String id) {
        return new EvalItem(id, "layer", List.of(), "question", "SELECT 1", null);
    }
}
