package br.com.oficina.billing.framework.web;

import io.smallrye.mutiny.Uni;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class ResourceUniAdapter {
    private ResourceUniAdapter() {
    }

    static <T> Uni<T> toUni(Supplier<CompletableFuture<T>> supplier) {
        try {
            return Uni.createFrom().completionStage(supplier.get());
        } catch (RuntimeException exception) {
            return Uni.createFrom().failure(exception);
        }
    }
}
