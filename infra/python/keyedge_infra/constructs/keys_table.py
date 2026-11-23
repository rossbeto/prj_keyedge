"""The single keys table (D-35): on-demand, PK/SK, GSI1, TTL; PITR and RETAIN only when durable."""

from dataclasses import dataclass

from aws_cdk import RemovalPolicy
from aws_cdk import aws_dynamodb as dynamodb
from constructs import Construct


@dataclass(frozen=True)
class KeysTableProps:
    table_name: str
    partition_key: str
    sort_key: str
    gsi_name: str
    gsi_partition_key: str
    gsi_sort_key: str
    ttl_attribute: str
    durable: bool


def _string(name: str) -> dynamodb.Attribute:
    return dynamodb.Attribute(name=name, type=dynamodb.AttributeType.STRING)


class KeysTable(Construct):
    def __init__(self, scope: Construct, construct_id: str, p: KeysTableProps) -> None:
        super().__init__(scope, construct_id)
        self.table = dynamodb.Table(
            self,
            "Table",
            table_name=p.table_name,
            partition_key=_string(p.partition_key),
            sort_key=_string(p.sort_key),
            billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
            time_to_live_attribute=p.ttl_attribute,
            point_in_time_recovery_specification=dynamodb.PointInTimeRecoverySpecification(
                point_in_time_recovery_enabled=p.durable
            ),
            removal_policy=RemovalPolicy.RETAIN if p.durable else RemovalPolicy.DESTROY,
        )
        self.table.add_global_secondary_index(
            index_name=p.gsi_name,
            partition_key=_string(p.gsi_partition_key),
            sort_key=_string(p.gsi_sort_key),
            projection_type=dynamodb.ProjectionType.ALL,
        )
