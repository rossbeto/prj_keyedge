# 4. RFC 9457 Problem Details as the only error format

Date: 2026-11-23 · Status: accepted

## Context
The reference services had about 40 near-identical exception handlers and several error shapes.

## Decision
Every error body is `application/problem+json` built from one `Problem` value and one Micronaut adapter
(`libs/problem-micronaut`). Type URIs are URNs (`urn:keyedge:problem:<slug>`): RFC 9457 does not require
them to resolve, and a URN does not depend on owning a domain. Slugs are pinned by a snapshot test.
Use cases return sealed results; only adapters throw `ProblemException`. Unexpected exceptions become
`internal` with the request id and never the exception text.

## Consequences
Clients switch on stable slugs; adding a problem type is a reviewed snapshot change.
