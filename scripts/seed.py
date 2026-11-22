"""Seed the selected emulator for local runs (until the CDK stacks do it in Phase 012).

    make seed [TARGET=floci|aws-emulator]
Creates (idempotently) the keys table, the pepper secret {"current":1,"peppers":{"1":...}} and the
internal-caller secret {"edge-quotes": ...} and the sidecar→app principal key {"key": ...}. Random values come from the OS; nothing is printed except
names. Refuses to run without an emulator endpoint (real-AWS guard).
"""

from __future__ import annotations

import base64
import json
import os
import secrets
import sys

import boto3
from botocore.exceptions import ClientError

assert os.environ.get("AWS_ENDPOINT_URL"), "refusing to seed without AWS_ENDPOINT_URL (real-AWS guard)"
TABLE = os.environ.get("KEYEDGE_KEYS_TABLE", "keyedge-keys")
PEPPERS = os.environ.get("KEYEDGE_PEPPER_SECRET", "keyedge/peppers")
CALLERS = os.environ.get("KEYEDGE_CALLER_SECRET", "keyedge/callers")
PRINCIPAL = os.environ.get("KEYEDGE_PRINCIPAL_ID", "keyedge/principal")
ddb = boto3.client("dynamodb")
sm = boto3.client("secretsmanager")


def table() -> str:
    try:
        ddb.describe_table(TableName=TABLE)
        return "exists"
    except ClientError as e:
        if e.response["Error"]["Code"] != "ResourceNotFoundException":
            raise
    attrs = [{"AttributeName": n, "AttributeType": "S"} for n in ("PK", "SK", "GSI1PK", "GSI1SK")]
    ddb.create_table(
        TableName=TABLE, BillingMode="PAY_PER_REQUEST", AttributeDefinitions=attrs,
        KeySchema=[{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}],
        GlobalSecondaryIndexes=[{"IndexName": "GSI1", "Projection": {"ProjectionType": "ALL"},
                                 "KeySchema": [{"AttributeName": "GSI1PK", "KeyType": "HASH"},
                                               {"AttributeName": "GSI1SK", "KeyType": "RANGE"}]}])
    ddb.get_waiter("table_exists").wait(TableName=TABLE)
    ddb.update_time_to_live(TableName=TABLE, TimeToLiveSpecification={"Enabled": True, "AttributeName": "ttl"})
    return "created"


def secret(name: str, value: dict) -> str:
    try:
        sm.describe_secret(SecretId=name)
        return "exists"
    except ClientError as e:
        if e.response["Error"]["Code"] != "ResourceNotFoundException":
            raise
    sm.create_secret(Name=name, SecretString=json.dumps(value))
    return "created"


def b64() -> str:
    return base64.b64encode(secrets.token_bytes(32)).decode()


print(f"table {TABLE}: {table()}")
print(f"secret {PEPPERS}: {secret(PEPPERS, {'current': 1, 'peppers': {'1': b64()}})}")
print(f"secret {CALLERS}: {secret(CALLERS, {'edge-quotes': b64()})}")
print(f"secret {PRINCIPAL}: {secret(PRINCIPAL, {'key': b64()})}")
sys.exit(0)
