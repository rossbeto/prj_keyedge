# Next lab: what keyedge points at

keyedge stops where a real account starts. The follow-ups it found, in the order I would take them:

1. **Size limits from latency under load (B-35, B-38).** Both "surprises" in the bench were limits we set ourselves:
   the edge's gradient limiter read host noise as overload, and the quotes bulkheads rejected before the CPU was
   busy. A small lab on adaptive limits (gradient vs AIMD vs Vegas) under a controlled noisy neighbour.
2. **A real account, for a day.** IAM and KMS key policies tested for real, Fargate task start order and ALB
   draining measured, the same templates (`prodlike` mode) deployed and destroyed by one command.
3. **Global rate limits.** Per-task limits admit N × the rate with N tasks; a token server or a shared counter, and
   what it costs on the hot path.
4. **Timelines, not totals.** Outage and brown-out runs as per-second timelines (B-36), so recovery shape is visible.
