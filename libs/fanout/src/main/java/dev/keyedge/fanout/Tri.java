package dev.keyedge.fanout;

/** The three results of a fan-out, in call order. */
public record Tri<A, B, C>(CallResult<A> first, CallResult<B> second, CallResult<C> third) {}
