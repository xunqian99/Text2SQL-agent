import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.text2sql.agent.acceptance.DemoHttpAcceptanceProbe;
import com.text2sql.agent.budget.DeepSeekOffPeakPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** Adds Instant support to the frozen caller's JSON writer; does not replace its logic. */
public final class TimeAwareProbe {
    public static void main(String[] args) throws Exception {
        var field = DemoHttpAcceptanceProbe.class.getDeclaredField("JSON");
        field.setAccessible(true);
        var mapper = (ObjectMapper) field.get(null);
        mapper.registerModule(new JavaTimeModule());
        if (args.length == 1 && args[0].equals("self-check")) {
            for (String timestamp : new String[]{"2026-11-17T07:00:00Z", "2026-11-17T10:00:00Z"}) {
                var decision = new DeepSeekOffPeakPolicy(Clock.fixed(Instant.parse(timestamp), ZoneOffset.UTC))
                        .evaluate(Duration.ofMinutes(2));
                var restored = mapper.readValue(mapper.writeValueAsBytes(decision), DeepSeekOffPeakPolicy.Decision.class);
                if (!restored.equals(decision)) throw new AssertionError("Decision did not round-trip");
                if (restored.allowed() != timestamp.contains("T10:")) throw new AssertionError("Time gate changed");
            }
            System.out.println("TIME_DECISION_ROUNDTRIP 2/2; frozen caller/scorer unchanged; no HTTP/model request");
        } else {
            DemoHttpAcceptanceProbe.main(args);
        }
    }
}
