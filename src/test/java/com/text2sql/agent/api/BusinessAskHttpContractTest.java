package com.text2sql.agent.api;

import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BusinessAskHttpContractTest {
    @Test
    void businessEndpointDoesNotSerializeDiagnosticFields() throws Exception {
        Text2SqlOrchestrator orchestrator = mock(Text2SqlOrchestrator.class);
        AgentResponse response = AgentResponse.success(
                "2018年销售额是多少", "SELECT secret", false,
                new AgentResponse.QueryOutcome(List.of("gmv_with_freight"), List.of(List.of("8593303.96")), false),
                LlmCallRecord.of("fixture", 12, 8, 1, 4, 20, 0.0004),
                2, List.of("orders"), 128, new AgentResponse.Timings(2, 10, 1, 3, 20))
                .withConversation("session-001", "2018年销售额是多少", false);
        when(orchestrator.ask(any(), any(), isNull())).thenReturn(response);

        MockMvc mvc = standaloneSetup(new AskController(orchestrator)).build();

        mvc.perform(post("/api/business/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"2018年销售额是多少\",\"sessionId\":\"session-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.title").value("成交总额 GMV"))
                .andExpect(jsonPath("$.summary").value("¥8,593,303.96"))
                .andExpect(jsonPath("$.sql").doesNotExist())
                .andExpect(jsonPath("$.llm").doesNotExist())
                .andExpect(jsonPath("$.timings").doesNotExist())
                .andExpect(jsonPath("$.provenance").doesNotExist());
    }
}
