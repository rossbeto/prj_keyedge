# jcstress found the race

*Draft. From the keyedge lab; every number links to its results file.*

The sidecar rate-limits every key. The first token bucket read its two fields (tokens, last refill), computed, and
wrote them back. Each field was `volatile`; the unit tests with several threads passed.

## The tests that passed were not looking

jcstress runs a tiny test body millions of times across thread interleavings and counts each outcome. We declared
"both threads got the last token" FORBIDDEN for the correct limiters and INTERESTING for the naive one, with time
frozen so a refill could never explain a second admit.

Over ~200 million samples, the naive bucket gave the single last token to both threads **10.6 %** of the time. The
GCRA limiter and the packed token bucket (one `AtomicLong`, compare-and-set) had **zero** forbidden outcomes in
268 jcstress runs ([results](../../results/labs/naive-bucket-race.json)).

## Correct, and cheap

JMH then priced it: a GCRA decision costs about 33 ns uncontended, within 0.81× of Bucket4j on the same plan, and
stays within ~6.5× when 16 threads hammer one key ([results](../../results/labs/limiter-cost.json)). The keyed
limiter for 100 000 keys costs more per call because of the map lookup, not the algorithm.

## The lesson

`volatile` makes each read and write visible; it does not make a read-compute-write atomic. Tests with threads
rarely hit the window; a tool built to enumerate interleavings hits it one time in ten. If a class is concurrent
by design, give it a jcstress test next to its unit tests.
