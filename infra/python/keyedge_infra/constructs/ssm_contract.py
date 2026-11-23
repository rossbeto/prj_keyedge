"""The names the services read at start-up (D-34), as SSM String parameters under /keyedge/<env>/."""

from aws_cdk import aws_ssm as ssm
from constructs import Construct


class SsmContract(Construct):
    def __init__(self, scope: Construct, construct_id: str, parameters: dict[str, str]) -> None:
        super().__init__(scope, construct_id)
        self.written: dict[str, str] = {}
        for name in sorted(parameters):  # same order as Java's TreeMap
            ssm.StringParameter(
                self,
                name[name.rfind("/") + 1 :],
                parameter_name=name,
                string_value=parameters[name],
                description="keyedge contract",
            )
            self.written[name] = parameters[name]
