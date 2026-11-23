package dev.keyedge.quotes.domain;

/** What the client asked for. */
public record QuoteRequest(Sku sku, Quantity quantity, CurrencyCode currency) {}
