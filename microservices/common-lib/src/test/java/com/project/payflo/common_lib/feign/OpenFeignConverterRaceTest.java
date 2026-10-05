package com.project.payflo.common_lib.feign;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.http.converter.HttpMessageConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring Cloud OpenFeign 5.0.2 published its converter list empty before filling it, so a thread arriving during the
 * first build failed with {@code 'messageConverters' must not be empty}: nine settlements starting at once did it.
 * 5.0.3 fixed that, and this project dropped its own workaround. This test is what stays: it fails if the dependency is
 * ever downgraded to a release with the race.
 */
class OpenFeignConverterRaceTest {

    private static final int THREADS = 16;

    // A customizer that takes a while widens the window in which a half-built list could be seen.
    @SuppressWarnings("unchecked")
    private static ObjectProvider<ClientHttpMessageConvertersCustomizer> slowCustomizers() {
        ObjectProvider<ClientHttpMessageConvertersCustomizer> provider = mock(ObjectProvider.class);
        ClientHttpMessageConvertersCustomizer slow = builder -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        when(provider.orderedStream()).thenAnswer(call -> Stream.of(slow));
        return provider;
    }

    @Test
    @SuppressWarnings("unchecked")
    void noThreadEverSeesAnEmptyConverterListWhileItIsFirstBuilt() throws Exception {
        for (int round = 0; round < 3; round++) {
            FeignHttpMessageConverters converters = new FeignHttpMessageConverters(slowCustomizers(), mock(ObjectProvider.class));
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> sizes = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    sizes.add(pool.submit(() -> {
                        start.await();
                        List<HttpMessageConverter<?>> list = converters.getConverters();
                        return list.size();
                    }));
                }
                start.countDown();
                for (Future<Integer> size : sizes) {
                    assertThat(size.get()).as("a thread saw the list before it was complete").isPositive();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
