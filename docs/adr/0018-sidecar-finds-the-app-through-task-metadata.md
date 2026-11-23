# 18. The sidecar finds the app through the task metadata endpoint

Date: 2026-11-23 · Status: accepted

## Context
In the sidecar pattern the edge forwards to the app on `localhost`: on AWS Fargate (`awsvpc`) all containers of
a task share one network namespace. Floci 2.5 runs each task container as a separate Docker container (Phase
014): they share networks with each other and with the compose project, but **not** localhost, and the
container names do not resolve (parity P-04). A task definition that works on AWS therefore cannot reach its
app on Floci, and the lab must not carry an emulator-only template.

## Options
| Option | For | Against |
|---|---|---|
| `localhost` only | the AWS idiom | the edge never reaches the app on Floci: no ECS run at all |
| Separate template for the emulator (the app's compose name) | trivial | the deployed template is not the one that ships; parity proof void |
| Service discovery (Cloud Map) name | real AWS feature | resolves the *service*, i.e. any task, not this task's app; Floci does not serve the DNS |
| The container's IPv4 from the task metadata | standard field | tried first and **wrong**: like AWS, Floci reports the one task address for every container, and on Floci only the first container holds it (edge → its own address, refused) |
| **`ecs:<container>:<port>`: the container's short Docker id from the task metadata if it resolves, else loopback** | one template; Floci: Docker's DNS resolves the sibling; AWS (awsvpc): the id does not resolve → `127.0.0.1`, the AWS idiom | one metadata call and one DNS lookup per edge process; a small resolver to own |

## Decision
- The edge's `KEYEDGE_APP_URL` is either a URL (compose) or `ecs:<container>:<port>` (task definitions:
  `ecs:quotes:15081`, from `service.edgeAppUrl` in the spec). `AppAddress` reads the container's `DockerId` from
  the task metadata on the **first request**: if its 12-char short form resolves, the containers are Docker
  siblings (Floci) and that name is used; otherwise they share a namespace and it is `127.0.0.1`. Cached; a
  failure is retried on the next request.
  Lazy, because on AWS the app starts only after the edge is HEALTHY: an eager lookup would deadlock start-up.
- Services in a task bind `0.0.0.0` (`MICRONAUT_SERVER_HOST`, task env): reaching the app on its container
  address requires it. Only the edge's port is mapped; the security group still admits the ALB only.
- The AWS SDK honours `AWS_ENDPOINT_URL` (which Floci injects into tasks) for the `floci` target, after our own
  `KEYEDGE_AWS_ENDPOINT`.

## Consequences
- The same template runs on Floci and is the one CDK synthesises for AWS; `make ecs-*` proves it end to end.
- **Docker socket.** Running tasks needs Floci with `/var/run/docker.sock`: root-equivalent on this machine. The
  `floci-ecs` service is a separate compose profile, started only by `make ecs-up` (a warning is printed), bound to
  127.0.0.1, never in CI. Teardown deletes the stacks so Floci stops its own task containers; scripts never remove
  containers by name pattern (other projects share this Docker daemon) and report the count left behind.
  The AWS Emulator probe with the socket is opt-in the same way (`KEYEDGE_EMU_DOCKER_SOCKET=yes`, non-root user in
  the socket's group); it showed that emulator runs no task containers at all (parity P-06).
- On AWS the indirection costs one metadata call and one failed DNS lookup per edge process and ends at
  `127.0.0.1`, which a plain URL would also give: the price of one template for both.
