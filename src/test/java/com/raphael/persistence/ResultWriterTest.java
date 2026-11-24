package com.raphael.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raphael.experiment.ArchitectureArms;
import com.raphael.experiment.BlastRadius;
import com.raphael.experiment.ExperimentResult;
import com.raphael.experiment.ExperimentRunner;
import com.raphael.scenario.MessageScenarios;
import com.raphael.scenario.Scenario;
import com.raphael.scheduling.budget.ServiceBudgetConfig;
import com.raphael.scheduling.sharding.ShardingConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultWriterTest {

  @TempDir
  Path temp;

  @Test
  void writesSevenValidFilesIntoAFreshDirectoryEachTime() throws Exception {
    Scenario scenario = MessageScenarios.shardFailureWorkload(300, 10);
    ShardingConfig shards = new ShardingConfig(4, 2, 42, List.of());
    List<ResultWriter.Entry> entries = new ArrayList<>();
    List<ExperimentResult> results = new ExperimentRunner(0, 1).runAll(scenario,
        ArchitectureArms.e0ToE5(10, ServiceBudgetConfig.defaults(), shards, 5_000_000));
    for (ExperimentResult r : results) {
      BlastRadius blast = r.config().experimentId().equals("E5")
          ? BlastRadius.compare("E5", results.get(4).result(), r.result(), Set.of("T00")) : null;
      entries.add(new ResultWriter.Entry(scenario, Map.of("seed", "none"), r, blast));
    }
    Path first = ResultWriter.write(temp, "test", Map.of("quick", "true"), entries);
    Path second = ResultWriter.write(temp, "test", Map.of("quick", "true"), entries);
    assertNotEquals(first, second);                          // 덮어쓰지 않는다

    for (String json : List.of("manifest.json", "experiment-config.json",
        "experiment-results.json")) {
      String text = Files.readString(first.resolve(json), StandardCharsets.UTF_8);
      new JsonValidator(text).validate();
    }
    String manifest = Files.readString(first.resolve("manifest.json"));
    assertTrue(manifest.contains("\"schema_version\": 1"));
    assertTrue(manifest.contains("\"simulator_version\": \"0.4.0\""));

    for (String csv : List.of("experiment-summary.csv", "tenant-metrics.csv",
        "message-type-metrics.csv", "scheduler-metrics.csv")) {
      List<String> lines = Files.readAllLines(first.resolve(csv), StandardCharsets.UTF_8);
      assertTrue(lines.size() > 1, csv);
      int width = columns(lines.get(0));
      for (String line : lines) {
        assertEquals(width, columns(line), csv + ": " + line);
      }
    }
    List<String> summary = Files.readAllLines(first.resolve("experiment-summary.csv"));
    assertEquals(7, summary.size());                         // 헤더 + E0..E5
    assertTrue(summary.get(6).startsWith("F1,E5,"));
  }

  /** 따옴표를 고려한 CSV 열 수. */
  private static int columns(String line) {
    int count = 1;
    boolean quoted = false;
    for (char c : line.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
      } else if (c == ',' && !quoted) {
        count++;
      }
    }
    return count;
  }

  /** 최소 JSON 문법 검증기 (RFC 8259 의 값·객체·배열·문자열·숫자·리터럴). */
  private static final class JsonValidator {
    private final String s;
    private int i;

    JsonValidator(String s) {
      this.s = s;
    }

    void validate() {
      value();
      skip();
      if (i != s.length()) {
        throw new IllegalStateException("Trailing content at " + i);
      }
    }

    private void value() {
      skip();
      char c = s.charAt(i);
      if (c == '{') {
        i++;
        skip();
        if (s.charAt(i) == '}') {
          i++;
          return;
        }
        while (true) {
          skip();
          string();
          skip();
          expect(':');
          value();
          skip();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect('}');
            return;
          }
        }
      } else if (c == '[') {
        i++;
        skip();
        if (s.charAt(i) == ']') {
          i++;
          return;
        }
        while (true) {
          value();
          skip();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect(']');
            return;
          }
        }
      } else if (c == '"') {
        string();
      } else if (s.startsWith("true", i) || s.startsWith("null", i)) {
        i += 4;
      } else if (s.startsWith("false", i)) {
        i += 5;
      } else {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
          i++;
        }
        Double.parseDouble(s.substring(start, i));
      }
    }

    private void string() {
      expect('"');
      while (s.charAt(i) != '"') {
        if (s.charAt(i) == '\\') {
          i++;
        }
        if (s.charAt(i) < 0x20) {
          throw new IllegalStateException("Control character in string at " + i);
        }
        i++;
      }
      i++;
    }

    private void expect(char c) {
      if (s.charAt(i) != c) {
        throw new IllegalStateException("Expected " + c + " at " + i + " but was " + s.charAt(i));
      }
      i++;
    }

    private void skip() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }
  }
}
