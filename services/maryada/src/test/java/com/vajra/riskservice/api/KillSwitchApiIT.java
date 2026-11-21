package com.vajra.riskservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vajra.riskservice.HttpTestClient;
import com.vajra.riskservice.TestDatabase;
import com.vajra.riskservice.killswitch.CoreClient;
import com.vajra.riskservice.killswitch.CoreUnavailableException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KillSwitchApiIT {

    private static final String TOKEN = "test-risk-token";
    private static final String PATH = "/api/v1/kill-switch";
    private static final String REASON = "{\"reason\":\"manual stop\"}";

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("RISK_API_TOKEN", () -> TOKEN);
        registry.add("CORE_INTERNAL_TOKEN", () -> "core-token");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    CoreClient core;

    @MockitoSpyBean
    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        reset(core);
        when(core.kill(anyString())).thenReturn(new CoreClient.KillResult(2, 1, true));
        jdbc.sql("delete from kill_switch_event").update();
        jdbc.sql("delete from risk_decision").update();
        jdbc.sql("update kill_switch set halted = false where id = 1").update();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return HttpTestClient.postJson(port, path, json, TOKEN);
    }

    private String approve(String source, String side, long size, boolean reduceOnly, long position, String id)
            throws Exception {
        String json = """
                {"clientOrderId":"%s","venue":"PAPER","symbol":"BTCUSD","side":"%s","size":%d,
                 "orderType":"MARKET","reduceOnly":%b,"source":"%s","currentPosition":%d}"""
                .formatted(id, side, size, reduceOnly, source, position);
        return post("/api/v1/approvals", json).body();
    }

    private List<String> events() {
        return jdbc.sql("select action from kill_switch_event order by id").query(String.class).list();
    }

    private boolean halted() {
        return jdbc.sql("select halted from kill_switch where id = 1").query(Boolean.class).single();
    }

    @Test
    void killHaltsAndFlattens() throws Exception {
        HttpResponse<String> response = post(PATH, REASON);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"halted\":true").contains("\"flattened\":true");
        verify(core, times(1)).kill("manual stop");
        assertThat(events()).containsExactly("KILL", "FLATTEN");
        Map<String, Object> flatten = jdbc.sql("select flattened from kill_switch_event where action = 'FLATTEN'")
                .query().singleRow();
        assertThat(flatten.get("flattened")).isEqualTo(true);
        assertThat(HttpTestClient.getWithToken(port, PATH, TOKEN).body())
                .contains("\"halted\":true").contains("\"KILL\"").contains("\"FLATTEN\"");
    }

    @Test
    void approvalsRejectedWhileHalted() throws Exception {
        post(PATH, REASON);

        assertThat(approve("MANUAL", "BUY", 1, false, 0, "h-1")).contains("HALTED");
        assertThat(approve("KILL_SWITCH", "SELL", 5, true, 5, "h-2")).contains("\"decision\":\"APPROVED\"");
    }

    @Test
    void killWhenCoreUnreachableStaysHalted() throws Exception {
        when(core.kill(anyString())).thenThrow(new CoreUnavailableException("down", null));

        HttpResponse<String> response = post(PATH, REASON);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"halted\":true").contains("\"flattened\":false");
        assertThat(halted()).isTrue();
        assertThat(approve("MANUAL", "BUY", 1, false, 0, "u-1")).contains("HALTED");
    }

    @Test
    void killAgainFinishesFlatten() throws Exception {
        when(core.kill(anyString()))
                .thenThrow(new CoreUnavailableException("down", null))
                .thenReturn(new CoreClient.KillResult(0, 3, true));

        assertThat(post(PATH, REASON).body()).contains("\"flattened\":false");
        assertThat(post(PATH, REASON).body()).contains("\"halted\":true").contains("\"flattened\":true");
        assertThat(halted()).isTrue();
        assertThat(flattenEvents()).containsExactly(
                "KILL:null", "FLATTEN:false", "KILL:null", "FLATTEN:true");
    }

    private List<String> flattenEvents() {
        return jdbc.sql("select action || ':' || coalesce(flattened::text, 'null') from kill_switch_event order by id")
                .query(String.class).list();
    }

    @Test
    void everyKillPressIsRecorded() throws Exception {
        post(PATH, REASON);
        post(PATH, REASON);

        assertThat(events()).containsExactly("KILL", "FLATTEN", "KILL", "FLATTEN");
    }

    @Test
    void resumeFailureWritesNoResumeEvent() throws Exception {
        post(PATH, REASON);
        doThrow(new CoreUnavailableException("down", null)).when(core).resume();

        assertThat(post(PATH + "/resume", REASON).statusCode()).isEqualTo(503);

        assertThat(events()).doesNotContain("RESUME");
        assertThat(halted()).isTrue();
    }

    @Test
    void killDuringResumeWins() throws Exception {
        post(PATH, REASON);
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            resumeEntered.countDown();
            release.await();
            return null;
        }).when(core).resume();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> resume = pool.submit(() -> post(PATH + "/resume", REASON));
            assertThat(resumeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<HttpResponse<String>> kill = pool.submit(() -> post(PATH, REASON));
            long deadline = System.currentTimeMillis() + 5000;
            while (events().stream().filter("KILL"::equals).count() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(events().stream().filter("KILL"::equals).count()).isEqualTo(2);
            assertThat(halted()).isTrue();
            release.countDown();

            HttpResponse<String> resumed = resume.get(10, TimeUnit.SECONDS);
            HttpResponse<String> killed = kill.get(10, TimeUnit.SECONDS);

            assertThat(resumed.statusCode()).isEqualTo(409);
            assertThat(resumed.body()).contains("kill pressed during resume; still halted");
            assertThat(killed.statusCode()).isEqualTo(200);
            assertThat(killed.body()).contains("\"halted\":true");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        InOrder order = inOrder(core);
        order.verify(core).resume();
        order.verify(core, atLeastOnce()).kill("manual stop");
        assertThat(HttpTestClient.getWithToken(port, PATH, TOKEN).body()).contains("\"halted\":true");
        assertThat(events()).doesNotContain("RESUME");
        assertThat(events().subList(events().size() - 2, events().size())).containsExactly("KILL", "FLATTEN");
    }

    @Test
    void resumeBetweenKillCommitAndLockSupersedesKill() throws Exception {
        CountDownLatch haltCommitted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(inv -> {
            inv.callRealMethod();
            if (armed.compareAndSet(true, false)) {
                haltCommitted.countDown();
                proceed.await();
            }
            return null;
        }).when(tx).executeWithoutResult(any());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> kill = pool.submit(() -> post(PATH, REASON));
            assertThat(haltCommitted.await(5, TimeUnit.SECONDS)).isTrue();

            HttpResponse<String> resumed = post(PATH + "/resume", REASON);
            assertThat(resumed.statusCode()).isEqualTo(200);
            proceed.countDown();
            HttpResponse<String> killed = kill.get(10, TimeUnit.SECONDS);

            assertThat(killed.statusCode()).isEqualTo(200);
            assertThat(killed.body()).contains("\"halted\":false").contains("superseded by a later resume");
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
        verify(core, never()).kill(anyString());
        assertThat(events()).containsExactly("KILL", "RESUME");
        assertThat(halted()).isFalse();
        assertThat(HttpTestClient.getWithToken(port, PATH, TOKEN).body()).contains("\"halted\":false");
    }

    @Test
    void resumeClearsHaltOnlyAfterCoreResumes() throws Exception {
        post(PATH, REASON);
        doThrow(new CoreUnavailableException("down", null)).doNothing().when(core).resume();

        HttpResponse<String> failed = post(PATH + "/resume", REASON);
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.body()).contains("core unreachable; still halted");
        assertThat(halted()).isTrue();

        HttpResponse<String> ok = post(PATH + "/resume", REASON);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"halted\":false");
        assertThat(halted()).isFalse();
        assertThat(events()).contains("RESUME");
    }

    @Test
    void killNeedsTokenAndReason() throws Exception {
        assertThat(HttpTestClient.postJson(port, PATH, REASON, null).statusCode()).isEqualTo(401);
        assertThat(post(PATH, "{\"reason\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(post(PATH, "{\"reason\":\"" + "x".repeat(201) + "\"}").statusCode()).isEqualTo(400);
        assertThat(halted()).isFalse();
    }
}
