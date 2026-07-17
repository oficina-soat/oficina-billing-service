package br.com.oficina.billing.framework.service;

public record NotificacaoEmailRequest(String emailDestino, String assunto, String conteudo) {
}
