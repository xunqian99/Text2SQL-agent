package com.text2sql.agent.budget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.net.URI;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekOffPeakPolicyTest {
    static Instant at(String local) { return LocalDateTime.parse(local).atZone(DeepSeekOffPeakPolicy.BEIJING).toInstant(); }
    static DeepSeekOffPeakPolicy policy(String local) {
        return new DeepSeekOffPeakPolicy(Clock.fixed(at(local), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-17T08:57:59,true,OFF_PEAK", "2026-11-17T08:58:00,false,PEAK_BOUNDARY_RESERVE",
            "2026-11-17T09:00:00,false,PEAK_HOURS", "2026-11-17T11:59:59,false,PEAK_HOURS",
            "2026-11-17T12:00:00,true,OFF_PEAK", "2026-11-17T13:57:59,true,OFF_PEAK",
            "2026-11-17T13:58:00,false,PEAK_BOUNDARY_RESERVE", "2026-11-17T14:00:00,false,PEAK_HOURS",
            "2026-11-17T17:59:59,false,PEAK_HOURS", "2026-11-17T18:00:00,true,OFF_PEAK",
            "2026-11-21T09:30:00,true,OFF_PEAK", "2026-11-22T15:30:00,true,OFF_PEAK",
            "2026-11-20T23:59:59,true,OFF_PEAK", "2026-11-23T08:58:00,false,PEAK_BOUNDARY_RESERVE",
            "2026-10-01T10:00:00,false,PEAK_HOURS"})
    void exactBeijingBoundariesAndConservativeHoliday(String time, boolean allowed, String reason) {
        var decision = policy(time).evaluate(Duration.ofMinutes(2));
        assertThat(decision.allowed()).isEqualTo(allowed);
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.policy()).isEqualTo(DeepSeekOffPeakPolicy.VERSION);
        assertThat(decision.checkedAt()).isEqualTo(at(time));
    }

    @Test void hostTimezoneCannotChangeTheBeijingDecision() {
        var instant = at("2026-11-17T14:01:00");
        var utc = new DeepSeekOffPeakPolicy(Clock.fixed(instant, ZoneOffset.UTC));
        var la = new DeepSeekOffPeakPolicy(Clock.fixed(instant, ZoneId.of("America/Los_Angeles")));
        assertThat(utc.evaluate(Duration.ofMinutes(2))).isEqualTo(la.evaluate(Duration.ofMinutes(2)));
    }

    @Test void futurePeakAndLongTimeoutsCannotBeHiddenByAnOffPeakStart() {
        assertThat(policy("2026-11-17T13:50:00").evaluate(DeepSeekOffPeakPolicy.horizon(600)).allowed()).isFalse();
        assertThat(policy("2026-11-17T09:00:00").evaluate(Duration.ofMinutes(2)).nextAllowedAt())
                .isEqualTo(at("2026-11-17T12:00:00"));
        assertThat(policy("2026-11-17T13:58:00").evaluate(Duration.ofMinutes(2)).nextAllowedAt())
                .isEqualTo(at("2026-11-17T18:00:00"));
    }

    @ParameterizedTest @CsvSource({
            "https://api.deepseek.com,other,true", "https://API.DEEPSEEK.COM.,other,true",
            "https://api.deepseek.com.attacker.invalid,other,false", "https://proxy.invalid,deepseek-flash,true",
            "https://api.openai.com,gpt-test,false", "http://127.0.0.1:1234,deepseek-flash,false",
            "http://localhost:1234,deepseek-flash,false", "http://[::1]:1234,deepseek-flash,false"})
    void destinationIdentitySeparatesRealEndpointsFromLocalFixtures(String url, String model, boolean expected) {
        assertThat(DeepSeekOffPeakPolicy.appliesTo(URI.create(url), model)).isEqualTo(expected);
    }

    @Test void requestCreatedOffPeakIsCheckedAgainBeforeSending() throws Exception {
        var time = new AtomicReference<>(at("2026-11-17T13:55:00"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var delegate = mock(ClientHttpRequestFactory.class);
        var wire = mock(ClientHttpRequest.class);
        var uri = URI.create("https://api.deepseek.com/v1/chat/completions");
        when(delegate.createRequest(uri, HttpMethod.POST)).thenReturn(wire);
        var factory = new BudgetHttpRequestFactory(60, false, clock, delegate);
        var request = factory.createRequest(uri, HttpMethod.POST);
        time.set(at("2026-11-17T14:00:00"));
        assertThatThrownBy(request::execute).isInstanceOf(DeepSeekOffPeakPolicy.Rejected.class);
        verify(wire, never()).execute();
    }

    @Test void peakIsRefusedBeforeConstructingTransportButFixturesStillWork() throws Exception {
        var delegate = mock(ClientHttpRequestFactory.class);
        var wire = mock(ClientHttpRequest.class);
        var response = mock(ClientHttpResponse.class);
        when(wire.execute()).thenReturn(response);
        when(delegate.createRequest(any(), any())).thenReturn(wire);
        var factory = new BudgetHttpRequestFactory(60, false,
                Clock.fixed(at("2026-11-17T15:00:00"), ZoneOffset.UTC), delegate);
        assertThatThrownBy(() -> factory.createRequest(URI.create("https://api.deepseek.com/v1/chat/completions"), HttpMethod.POST))
                .isInstanceOf(DeepSeekOffPeakPolicy.Rejected.class);
        verifyNoInteractions(delegate);
        assertThat(factory.createRequest(URI.create("http://127.0.0.1:1234/model"), HttpMethod.POST).execute()).isSameAs(response);
        verify(wire).execute();
    }

    @Test void offPeakTransportAndRemoteProxyEnforcementAreBothCovered() throws Exception {
        var delegate = mock(ClientHttpRequestFactory.class);
        var wire = mock(ClientHttpRequest.class);
        when(delegate.createRequest(any(), any())).thenReturn(wire);
        var offPeak = new BudgetHttpRequestFactory(60, false,
                Clock.fixed(at("2026-11-17T18:00:00"), ZoneOffset.UTC), delegate);
        offPeak.createRequest(URI.create("https://api.deepseek.com/v1/chat/completions"), HttpMethod.POST).execute();
        verify(wire).execute();
        var peakProxy = new BudgetHttpRequestFactory(60, true,
                Clock.fixed(at("2026-11-17T15:00:00"), ZoneOffset.UTC), delegate);
        assertThatThrownBy(() -> peakProxy.createRequest(URI.create("https://proxy.invalid/model"), HttpMethod.POST))
                .isInstanceOf(DeepSeekOffPeakPolicy.Rejected.class);
        verify(delegate, times(1)).createRequest(any(), any());
    }

    @ParameterizedTest @CsvSource({"0", "-1", "86401"})
    void invalidGuardCannotDisableThePolicy(long seconds) {
        assertThatThrownBy(() -> policy("2026-11-17T18:00:00").evaluate(Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
