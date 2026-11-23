"""One SNS topic per severity and a factory for alarms that notify one of them."""

from aws_cdk import aws_cloudwatch as cw
from aws_cdk import aws_cloudwatch_actions as cw_actions
from aws_cdk import aws_sns as sns
from constructs import Construct


class SeverityAlarms(Construct):
    def __init__(self, scope: Construct, construct_id: str, prefix: str, severities: list[str]) -> None:
        super().__init__(scope, construct_id)
        self.topics: dict[str, sns.Topic] = {
            s: sns.Topic(self, s, topic_name=f"{prefix}-{s}", enforce_ssl=True) for s in severities
        }

    def alarm(
        self, alarm_id: str, severity: str, metric: cw.IMetric, threshold: float, periods: int, what: str
    ) -> cw.Alarm:
        topic = self.topics.get(severity)
        if topic is None:
            raise ValueError(f"unknown severity {severity}")
        alarm = cw.Alarm(
            self,
            alarm_id,
            metric=metric,
            threshold=threshold,
            evaluation_periods=periods,
            comparison_operator=cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
            treat_missing_data=cw.TreatMissingData.NOT_BREACHING,
            alarm_description=f"[{severity}] {what}",
        )
        alarm.add_alarm_action(cw_actions.SnsAction(topic))
        return alarm
