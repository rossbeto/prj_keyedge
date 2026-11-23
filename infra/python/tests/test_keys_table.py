"""The keys table, asserted the same way as Java's StacksTest#keysTable."""

from aws_cdk.assertions import Match
from conftest import templates

from keyedge_infra.config import Mode


def test_keys_table(mode: Mode) -> None:
    t = templates(mode)["KeyedgeData"]
    t.has_resource_properties(
        "AWS::DynamoDB::Table",
        {
            "TableName": "keyedge-keys",
            "BillingMode": "PAY_PER_REQUEST",
            "KeySchema": [
                {"AttributeName": "PK", "KeyType": "HASH"},
                {"AttributeName": "SK", "KeyType": "RANGE"},
            ],
            "TimeToLiveSpecification": {"AttributeName": "ttl", "Enabled": True},
            "GlobalSecondaryIndexes": [
                Match.object_like({"IndexName": "GSI1", "Projection": {"ProjectionType": "ALL"}})
            ],
            "PointInTimeRecoverySpecification": {"PointInTimeRecoveryEnabled": mode is Mode.PRODLIKE},
        },
    )
    t.has_resource(
        "AWS::DynamoDB::Table", {"DeletionPolicy": "Retain" if mode is Mode.PRODLIKE else "Delete"}
    )
