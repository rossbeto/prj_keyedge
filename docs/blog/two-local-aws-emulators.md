# Testing AWS code on two local emulators

*Draft. From the keyedge lab.*

keyedge never touches a real AWS account. Every AWS call goes to a local emulator, chosen by one setting:
Floci or AWS Emulator. The point of two is that neither is trusted alone.

## One contract suite, two runs

The same JUnit suite (DynamoDB transactions and TTL, Secrets Manager, SSM, KMS, STS) runs against both. It found
fewer differences than expected: both enforce transaction cancellation reasons and KMS encryption context. The
measured difference was TTL timing (about a minute on one, seconds on the other), so the code never waits for TTL
deletion: reads check `expiresAt` ([parity page](../parity.md)).

## The CDK templates too

The same bootstrap-less templates deploy to both, and a verify script checks each resource with its own describe
call, because an emulator that does not support a type may still report `CREATE_COMPLETE` for a stub. One emulator
rejects a task definition with `DependsOn ... HEALTHY`, which is valid on AWS: that is a parity row and an upstream
report, not a template change.

## Where they really differ: running things

- Floci runs ECS tasks as real Docker containers, routes ALB traffic, ships logs to CloudWatch, but containers in
  one task do not share `localhost`, health checks are not run, and the old target leaves the load balancer the
  moment the new task starts.
- AWS Emulator's ECS, even with the Docker socket, reports tasks and containers RUNNING while nothing runs.

Both are written down as rows with evidence. The experiments that need real behaviour (start order, draining)
report **refuted** or **inconclusive** with the reason ([notebook](../lab-notebook.md#drain-on-deploy-phase-014)).

## What you get

Fast, free, offline tests for the code paths that matter, two independent opinions on every API the design uses,
and an honest list of what still needs a real account: IAM and key policies, Fargate timings, real draining.
