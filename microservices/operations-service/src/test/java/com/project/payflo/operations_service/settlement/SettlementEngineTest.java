package com.project.payflo.operations_service.settlement;

import com.project.payflo.operations_service.client.MerchantServiceClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementEngineTest {

    private final MerchantServiceClient merchantServiceClient = mock(MerchantServiceClient.class);
    private final SettlementTransactionExecutor executor = mock(SettlementTransactionExecutor.class);
    private final SettlementProperties properties = new SettlementProperties();
    private final SettlementEngine engine = new SettlementEngine(merchantServiceClient, executor, properties);

    private List<UUID> merchants(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add(UUID.randomUUID());
        when(merchantServiceClient.listActiveMerchantIds()).thenReturn(ids);
        return ids;
    }

    @Test
    void everyActiveMerchantIsSettled() {
        List<UUID> ids = merchants(5);

        engine.run();

        for (UUID id : ids) {
            verify(executor).processForMerchant(eq(id), any());
        }
    }

    @Test
    void oneMerchantFailingDoesNotStopTheOthers() {
        List<UUID> ids = merchants(5);
        doThrow(new RuntimeException("payment-service timed out")).when(executor).processForMerchant(eq(ids.get(1)), any());
        doThrow(new IllegalStateException("bad data")).when(executor).processForMerchant(eq(ids.get(3)), any());

        assertThatCode(engine::run).doesNotThrowAnyException();

        verify(executor, times(5)).processForMerchant(any(), any());
    }

    @Test
    void noMoreMerchantsThanTheConcurrencyLimitAreSettledAtOnce() {
        properties.setConcurrency(2);
        merchants(12);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger highest = new AtomicInteger();
        doAnswer(inv -> {
            int now = running.incrementAndGet();
            highest.accumulateAndGet(now, Math::max);
            Thread.sleep(30);
            running.decrementAndGet();
            return null;
        }).when(executor).processForMerchant(any(), any());

        engine.run();

        assertThat(highest.get()).isLessThanOrEqualTo(2);
        verify(executor, times(12)).processForMerchant(any(), any());
    }

    @Test
    void noActiveMerchantsIsFine() {
        when(merchantServiceClient.listActiveMerchantIds()).thenReturn(List.of());

        assertThatCode(engine::run).doesNotThrowAnyException();
    }
}
