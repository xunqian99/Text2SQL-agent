import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.semantic.MetricRegistry;
import com.text2sql.agent.generation.PromptFingerprint;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
public class MetricDefinitionWitness {
 public static void main(String[] args) throws Exception {
  var json = new ObjectMapper();
  var pairs = json.readTree(Path.of(args[0]).toFile());
  var prior = json.readTree(Path.of(args[1]).toFile());
  var pair = java.util.stream.StreamSupport.stream(pairs.path("items").spliterator(),false)
   .filter(i -> i.path("id").asText().equals("T6-005")).findFirst().orElseThrow();
  var old = java.util.stream.StreamSupport.stream(prior.path("items").spliterator(),false)
   .filter(i -> i.path("id").asText().equals("T6-005")).findFirst().orElseThrow();
  var tables = new LinkedHashSet<String>();
  pair.path("tables").forEach(t -> tables.add(t.asText()));
  var registry = new MetricRegistry();
  ReflectionTestUtils.invokeMethod(registry,"load");
  var metrics = registry.findApplicable("会员用户贡献的订单占比是多少？（会员渗透率）", tables);
  String rendered = metrics.stream().map(m -> m.render()+"\n\n").collect(java.util.stream.Collectors.joining()).strip();
  var witness = new LinkedHashMap<String,Object>();
  witness.put("questionId","T6-005");
  witness.put("tablesFromFrozenPairs",tables);
  witness.put("applicableMetrics",metrics);
  witness.put("renderedMetricsSha256",PromptFingerprint.textSha256(rendered));
  witness.put("priorCapturedMetricsSha256",old.path("metricsSha256").asText());
  witness.put("fullFirstPromptMatchesPriorCapture",pair.path("defaultPromptSha256").asText().equals(old.path("promptSha256").asText()));
  witness.put("renderedMetricsMatchPriorCapture",PromptFingerprint.textSha256(rendered).equals(old.path("metricsSha256").asText()));
  witness.put("modelCalls",0);
  json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),witness);
 }
}
