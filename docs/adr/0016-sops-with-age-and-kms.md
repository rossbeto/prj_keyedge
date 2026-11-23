# 16. SOPS with two recipients: age and the emulator's KMS key

Date: 2026-11-23 · Status: accepted

## Context
Secret values for non-local environments must live in git encrypted, decryptable by a developer and by CI, and
the KMS path must be exercised locally — where every emulator restart loses the KMS key.

## Decision
- `secrets/<target>/local.enc.yaml`, encrypted by SOPS (v3.13.3, pinned in `.tools/`) to **two recipients**: an
  age key in `~/.config/keyedge/age.key` (outside the repo, 0600) and the KmsStack key whose ARN the stack writes
  to `/keyedge/<env>/sops-kms-arn`. Rules in `secrets/.sops.yaml` (one per target, `path_regex` relative to
  `secrets/`).
- `make secrets-encrypt` generates values into a pipe; `secrets-decrypt-check VIA=age|kms` decrypts into a pipe
  and validates; `secrets-rekey` points the KMS recipient at the current key (`sops updatekeys`);
  `secrets-sync` puts the documents into Secrets Manager. Plaintext never touches the disk.
- In local mode CloudFormation generates the values (flat documents); `secrets-sync` replaces them with the SOPS
  documents (the pepper adapter reads both shapes).

## Consequences
- Measured on Floci: after an emulator restart, age decrypts and KMS does not; after `make secrets-rekey`, KMS
  decrypts again. On AWS Emulator the KMS path works over its TLS endpoint (sops honours the SDK's endpoint and
  CA settings).
- Keyless CI decryption (KMS via OIDC role) is the user's setup (USER-ACTIONS U-02/U-08).
