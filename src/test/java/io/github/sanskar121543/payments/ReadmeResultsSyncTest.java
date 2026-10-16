package io.github.sanskar121543.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Every number in the README's load-test table must come from the committed k6 result file. */
class ReadmeResultsSyncTest {

    private static final String START = "<!-- k6-results:start -->";
    private static final String END = "<!-- k6-results:end -->";

    @Test
    void readmeLoadTestTableIsCopiedVerbatimFromTheCommittedResultFile() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        int start = readme.indexOf(START);
        int end = readme.indexOf(END);
        assertThat(start).as("README is missing " + START).isNotNegative();
        assertThat(end).as("README is missing " + END).isGreaterThan(start);

        String block = readme.substring(start + START.length(), end).strip();
        String latest = Files.readString(Path.of("results", "k6", "latest.md")).strip();

        assertThat(block).isEqualTo(latest);
        assertThat(block).contains("| Hardware |");
    }
}
