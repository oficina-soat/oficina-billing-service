package br.com.oficina.billing.interfaces.controllers;

import br.com.oficina.billing.interfaces.presenters.view_model.StatusViewModel;
import java.util.concurrent.CompletableFuture;

public class StatusController {
    public CompletableFuture<StatusViewModel> status(StatusRequest request) {
        return CompletableFuture.completedFuture(new StatusViewModel(
                request.service(),
                request.environment(),
                request.status()));
    }

    public record StatusRequest(String service, String environment, String status) {
    }
}
