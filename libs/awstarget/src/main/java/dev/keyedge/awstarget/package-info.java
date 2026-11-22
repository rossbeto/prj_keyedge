/**
 * Resolves {@code KEYEDGE_AWS_TARGET} (floci | aws-emulator | aws) into one {@link
 * dev.keyedge.awstarget.AwsTarget} that configures every AWS SDK client the same way: endpoint,
 * region, credentials, and (for AWS Emulator) a trust store for its per-run CA, held in memory.
 */
@NullMarked
package dev.keyedge.awstarget;

import org.jspecify.annotations.NullMarked;
