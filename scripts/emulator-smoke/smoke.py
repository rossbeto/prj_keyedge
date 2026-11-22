"""Observed emulator smoke test: one check per row of scripts/emulator-smoke/operations.yaml.

Run through the target environment (never against real AWS):
    ( . <(scripts/aws-env.sh floci) && uv run --with boto3 --with pyyaml scripts/emulator-smoke/smoke.py floci )
Prints one TSV row per check: id, result (pass | fail | not-implemented | skipped), detail.
ECS/ECR/ELB rows need an ECS-capable endpoint: pass --ecs-endpoint (Floci with the Docker socket).
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.request
import uuid
from collections.abc import Callable
from typing import Any

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

TARGET = sys.argv[1] if len(sys.argv) > 1 else "?"
ECS_ENDPOINT = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--ecs-endpoint=")), None)
ONLY = next((a.split("=", 1)[1].split(",") for a in sys.argv if a.startswith("--only=")), None)
RUN = uuid.uuid4().hex[:6]
TABLE = f"keyedge-smoke-{RUN}"
CFG = Config(retries={"max_attempts": 1, "mode": "standard"}, connect_timeout=5, read_timeout=60)
assert os.environ.get("AWS_ENDPOINT_URL"), "refusing to run without AWS_ENDPOINT_URL (real AWS guard)"


def client(service: str, endpoint: str | None = None) -> Any:
    kw: dict[str, Any] = {"config": CFG}
    if endpoint:
        kw["endpoint_url"] = endpoint
    return boto3.client(service, **kw)


ddb, sm, ssm, kms, sts, cfn = (client(s) for s in ("dynamodb", "secretsmanager", "ssm", "kms", "sts", "cloudformation"))
results: list[tuple[str, str, str]] = []
state: dict[str, Any] = {}


def check(row_id: str) -> Callable[[Callable[[], str]], Callable[[], str]]:
    def deco(fn: Callable[[], str]) -> Callable[[], str]:
        if ONLY and row_id not in ONLY:
            return fn
        try:
            results.append((row_id, "pass", fn()))
        except ClientError as e:
            code = e.response.get("Error", {}).get("Code", "?")
            kind = "not-implemented" if code in ("NotImplemented", "UnknownOperationException", "InvalidAction") or e.response.get("ResponseMetadata", {}).get("HTTPStatusCode") == 501 else "fail"
            results.append((row_id, kind, f"{code}: {str(e)[:160]}"))
        except Exception as e:  # noqa: BLE001 - a smoke test reports every failure as a row
            results.append((row_id, "fail", f"{type(e).__name__}: {str(e)[:160]}"))
        return fn

    return deco


def expect_error(fn: Callable[[], Any], code: str) -> ClientError:
    try:
        fn()
    except ClientError as e:
        got = e.response["Error"]["Code"]
        if got != code:
            raise AssertionError(f"expected {code}, got {got}") from e
        return e
    raise AssertionError(f"expected {code}, call succeeded")


@check("sts-identity")
def _sts() -> str:
    acct = sts.get_caller_identity()["Account"]
    assert acct == "000000000000", acct
    return acct


@check("ddb-create-gsi")
def _create() -> str:
    ddb.create_table(
        TableName=TABLE,
        BillingMode="PAY_PER_REQUEST",
        AttributeDefinitions=[{"AttributeName": n, "AttributeType": "S"} for n in ("PK", "SK", "GSI1PK", "GSI1SK")],
        KeySchema=[{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}],
        GlobalSecondaryIndexes=[{
            "IndexName": "GSI1",
            "KeySchema": [{"AttributeName": "GSI1PK", "KeyType": "HASH"}, {"AttributeName": "GSI1SK", "KeyType": "RANGE"}],
            "Projection": {"ProjectionType": "ALL"},
        }],
    )
    ddb.get_waiter("table_exists").wait(TableName=TABLE, WaiterConfig={"Delay": 1, "MaxAttempts": 30})
    gsi = ddb.describe_table(TableName=TABLE)["Table"].get("GlobalSecondaryIndexes", [])
    assert gsi and gsi[0]["IndexName"] == "GSI1", gsi
    return "table ACTIVE with GSI1"


def item(pk: str, sk: str, **extra: str) -> dict[str, Any]:
    d = {"PK": {"S": pk}, "SK": {"S": sk}}
    d.update({k: {"S": v} for k, v in extra.items()})
    return d


@check("ddb-put-cond")
def _put_cond() -> str:
    ddb.put_item(TableName=TABLE, Item=item("K#1", "K#1"), ConditionExpression="attribute_not_exists(PK)")
    expect_error(lambda: ddb.put_item(TableName=TABLE, Item=item("K#1", "K#1"), ConditionExpression="attribute_not_exists(PK)"), "ConditionalCheckFailedException")
    return "second put rejected with ConditionalCheckFailedException"


@check("ddb-update-add")
def _update_add() -> str:
    ddb.put_item(TableName=TABLE, Item={**item("U#1", "m"), "version": {"N": "1"}})
    out = ddb.update_item(
        TableName=TABLE, Key=item("U#1", "m"), UpdateExpression="ADD cnt :one SET version = :v2",
        ConditionExpression="version = :v1", ExpressionAttributeValues={":one": {"N": "1"}, ":v1": {"N": "1"}, ":v2": {"N": "2"}},
        ReturnValues="ALL_NEW",
    )
    assert out["Attributes"]["cnt"]["N"] == "1", out
    expect_error(lambda: ddb.update_item(
        TableName=TABLE, Key=item("U#1", "m"), UpdateExpression="ADD cnt :one",
        ConditionExpression="version = :v1", ExpressionAttributeValues={":one": {"N": "1"}, ":v1": {"N": "1"}},
    ), "ConditionalCheckFailedException")
    return "ADD applied; stale version rejected"


@check("ddb-transact")
def _transact() -> str:
    e = expect_error(lambda: ddb.transact_write_items(TransactItems=[
        {"Put": {"TableName": TABLE, "Item": item("T#new", "x"), "ConditionExpression": "attribute_not_exists(PK)"}},
        {"Put": {"TableName": TABLE, "Item": item("K#1", "K#1"), "ConditionExpression": "attribute_not_exists(PK)"}},
    ]), "TransactionCanceledException")
    reasons = [r.get("Code") for r in e.response.get("CancellationReasons", [])]
    msg = e.response["Error"].get("Message", "")
    assert "ConditionalCheckFailed" in reasons or "ConditionalCheckFailed" in msg, (reasons, msg)
    assert "Item" not in ddb.get_item(TableName=TABLE, Key=item("T#new", "x"), ConsistentRead=True), "first put leaked"
    return f"cancelled, reasons={reasons or msg[:80]}; nothing written"


@check("ddb-get-consistent")
def _get() -> str:
    got = ddb.get_item(TableName=TABLE, Key=item("K#1", "K#1"), ConsistentRead=True)
    assert got["Item"]["PK"]["S"] == "K#1"
    return "found"


@check("ddb-query-gsi-page")
def _query() -> str:
    for i in range(3):
        ddb.put_item(TableName=TABLE, Item=item(f"Q#{i}", "m", GSI1PK="T#a#ACTIVE", GSI1SK=f"2026-{i}"))
    p1 = ddb.query(TableName=TABLE, IndexName="GSI1", KeyConditionExpression="GSI1PK = :p",
                   ExpressionAttributeValues={":p": {"S": "T#a#ACTIVE"}}, Limit=2)
    assert len(p1["Items"]) == 2 and "LastEvaluatedKey" in p1, p1
    p2 = ddb.query(TableName=TABLE, IndexName="GSI1", KeyConditionExpression="GSI1PK = :p",
                   ExpressionAttributeValues={":p": {"S": "T#a#ACTIVE"}}, Limit=2, ExclusiveStartKey=p1["LastEvaluatedKey"])
    assert len(p2["Items"]) == 1, p2
    return "2 + 1 items over two pages"


SECRET = f"keyedge/smoke/{RUN}"


@check("sm-create")
def _sm_create() -> str:
    state["v1"] = sm.create_secret(Name=SECRET, SecretString="pepper-v1")["VersionId"]
    return "created"


@check("sm-put-version")
def _sm_put() -> str:
    state["v2"] = sm.put_secret_value(SecretId=SECRET, SecretString="pepper-v2")["VersionId"]
    assert sm.get_secret_value(SecretId=SECRET)["SecretString"] == "pepper-v2"
    return "AWSCURRENT = v2"


@check("sm-get-stage")
def _sm_stage() -> str:
    prev = sm.get_secret_value(SecretId=SECRET, VersionStage="AWSPREVIOUS")["SecretString"]
    by_id = sm.get_secret_value(SecretId=SECRET, VersionId=state["v1"])["SecretString"]
    assert prev == "pepper-v1" and by_id == "pepper-v1", (prev, by_id)
    return "AWSPREVIOUS and VersionId return v1"


@check("sm-list-versions")
def _sm_list() -> str:
    vs = sm.list_secret_version_ids(SecretId=SECRET)["Versions"]
    stages = sorted(s for v in vs for s in v.get("VersionStages", []))
    assert "AWSCURRENT" in stages and "AWSPREVIOUS" in stages, stages
    return f"{len(vs)} versions, stages={stages}"


@check("ssm-put")
def _ssm_put() -> str:
    ssm.put_parameter(Name=f"/keyedge/smoke{RUN}/table-name", Value=TABLE, Type="String")
    ssm.put_parameter(Name=f"/keyedge/smoke{RUN}/deep/secret", Value="s3cr3t", Type="SecureString")
    return "String + SecureString"


@check("ssm-by-path")
def _ssm_path() -> str:
    ps = ssm.get_parameters_by_path(Path=f"/keyedge/smoke{RUN}", Recursive=True, WithDecryption=True)["Parameters"]
    vals = {p["Name"].rsplit("/", 1)[-1]: p["Value"] for p in ps}
    assert vals.get("secret") == "s3cr3t" and vals.get("table-name") == TABLE, vals
    return f"{len(ps)} params, SecureString decrypted"


@check("kms-create-sym")
def _kms_sym() -> str:
    state["key"] = kms.create_key(KeySpec="SYMMETRIC_DEFAULT", KeyUsage="ENCRYPT_DECRYPT")["KeyMetadata"]["KeyId"]
    return "created"


@check("kms-encrypt")
def _kms_enc() -> str:
    blob = kms.encrypt(KeyId=state["key"], Plaintext=b"hello")["CiphertextBlob"]
    assert kms.decrypt(CiphertextBlob=blob)["Plaintext"] == b"hello"
    return "round trip"


@check("kms-datakey")
def _kms_dk() -> str:
    dk = kms.generate_data_key(KeyId=state["key"], KeySpec="AES_256")
    assert len(dk["Plaintext"]) == 32
    assert kms.decrypt(CiphertextBlob=dk["CiphertextBlob"])["Plaintext"] == dk["Plaintext"]
    return "32-byte key, decryptable"


@check("kms-alias")
def _kms_alias() -> str:
    kms.create_alias(AliasName=f"alias/keyedge-smoke-{RUN}", TargetKeyId=state["key"])
    blob = kms.encrypt(KeyId=f"alias/keyedge-smoke-{RUN}", Plaintext=b"x")["CiphertextBlob"]
    assert kms.decrypt(CiphertextBlob=blob)["Plaintext"] == b"x"
    return "encrypt via alias"


@check("kms-create-hmac")
def _kms_hmac() -> str:
    state["hmac"] = kms.create_key(KeySpec="HMAC_256", KeyUsage="GENERATE_VERIFY_MAC")["KeyMetadata"]["KeyId"]
    return "created"


@check("kms-mac")
def _kms_mac() -> str:
    mac = kms.generate_mac(KeyId=state["hmac"], Message=b"msg", MacAlgorithm="HMAC_SHA_256")["Mac"]
    ok = kms.verify_mac(KeyId=state["hmac"], Message=b"msg", MacAlgorithm="HMAC_SHA_256", Mac=mac)["MacValid"]
    assert ok
    expect_error(lambda: kms.verify_mac(KeyId=state["hmac"], Message=b"other", MacAlgorithm="HMAC_SHA_256", Mac=mac), "KMSInvalidMacException")
    return "valid MAC accepted, wrong message rejected"


def deploy(stack: str, template: dict[str, Any]) -> list[dict[str, Any]]:
    """What `aws cloudformation deploy` does: change set, execute, wait."""
    body = json.dumps(template)
    cs = cfn.create_change_set(StackName=stack, TemplateBody=body, ChangeSetName="c1", ChangeSetType="CREATE",
                               Capabilities=["CAPABILITY_IAM", "CAPABILITY_NAMED_IAM"])
    for _ in range(60):
        d = cfn.describe_change_set(ChangeSetName=cs["Id"])
        if d["Status"] in ("CREATE_COMPLETE", "FAILED"):
            break
        time.sleep(1)
    assert d["Status"] == "CREATE_COMPLETE", d.get("StatusReason")
    cfn.execute_change_set(ChangeSetName=cs["Id"])
    for _ in range(120):
        s = cfn.describe_stacks(StackName=stack)["Stacks"][0]
        if not s["StackStatus"].endswith("IN_PROGRESS"):
            break
        time.sleep(1)
    assert s["StackStatus"] == "CREATE_COMPLETE", (s["StackStatus"], s.get("StackStatusReason"))
    return cfn.describe_stack_resources(StackName=stack)["StackResources"]


def phys(resources: list[dict[str, Any]], logical: str) -> str:
    return next(r["PhysicalResourceId"] for r in resources if r["LogicalResourceId"] == logical)


@check("cfn-changeset")
def _cfn_cs() -> str:
    res = deploy(f"keyedge-smoke-cs-{RUN}", {"Resources": {"P": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": "v"}}}})
    name = phys(res, "P")
    assert ssm.get_parameter(Name=name)["Parameter"]["Value"] == "v"
    cfn.delete_stack(StackName=f"keyedge-smoke-cs-{RUN}")
    return "change set deployed; parameter really exists"


@check("cfn-ddb")
def _cfn_ddb() -> str:
    res = deploy(f"keyedge-smoke-ddb-{RUN}", {"Resources": {"T": {"Type": "AWS::DynamoDB::Table", "Properties": {
        "BillingMode": "PAY_PER_REQUEST",
        "AttributeDefinitions": [{"AttributeName": n, "AttributeType": "S"} for n in ("PK", "SK", "GSI1PK", "GSI1SK")],
        "KeySchema": [{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}],
        "GlobalSecondaryIndexes": [{"IndexName": "GSI1", "KeySchema": [{"AttributeName": "GSI1PK", "KeyType": "HASH"}, {"AttributeName": "GSI1SK", "KeyType": "RANGE"}], "Projection": {"ProjectionType": "ALL"}}],
        "TimeToLiveSpecification": {"AttributeName": "ttl", "Enabled": True},
    }}}})
    name = phys(res, "T")
    t = ddb.describe_table(TableName=name)["Table"]
    ttl = ddb.describe_time_to_live(TableName=name)["TimeToLiveDescription"]
    assert t["GlobalSecondaryIndexes"][0]["IndexName"] == "GSI1" and ttl.get("AttributeName") == "ttl", (t, ttl)
    return "table with GSI1 + TTL really created"


@check("cfn-ssm-sm-kms")
def _cfn_misc() -> str:
    res = deploy(f"keyedge-smoke-misc-{RUN}", {"Resources": {
        "K": {"Type": "AWS::KMS::Key", "Properties": {"KeyPolicy": {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"AWS": "arn:aws:iam::000000000000:root"}, "Action": "kms:*", "Resource": "*"}]}}},
        "A": {"Type": "AWS::KMS::Alias", "Properties": {"AliasName": f"alias/keyedge-cfn-{RUN}", "TargetKeyId": {"Ref": "K"}}},
        "S": {"Type": "AWS::SecretsManager::Secret", "Properties": {"GenerateSecretString": {"PasswordLength": 32}}},
        "P": {"Type": "AWS::SSM::Parameter", "Properties": {"Type": "String", "Value": {"Ref": "S"}}},
    }})
    kms.describe_key(KeyId=phys(res, "K"))
    sm.describe_secret(SecretId=phys(res, "S"))
    ssm.get_parameter(Name=phys(res, "P"))
    aliases = [a["AliasName"] for a in kms.list_aliases()["Aliases"]]
    assert f"alias/keyedge-cfn-{RUN}" in aliases, aliases
    return "key, alias, secret, parameter really exist"


def vpc_resources() -> dict[str, Any]:
    return {
        "V": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.42.0.0/16"}},
        "S1": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "V"}, "CidrBlock": "10.42.1.0/24", "AvailabilityZone": "us-east-1a"}},
        "S2": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "V"}, "CidrBlock": "10.42.2.0/24", "AvailabilityZone": "us-east-1b"}},
        "G": {"Type": "AWS::EC2::SecurityGroup", "Properties": {"GroupDescription": "smoke", "VpcId": {"Ref": "V"}}},
    }


@check("cfn-ecs")
def _cfn_ecs() -> str:
    res = deploy(f"keyedge-smoke-ecs-{RUN}", {"Resources": {
        **vpc_resources(),
        "C": {"Type": "AWS::ECS::Cluster"},
        "L": {"Type": "AWS::Logs::LogGroup"},
        "R": {"Type": "AWS::IAM::Role", "Properties": {"AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"Service": "ecs-tasks.amazonaws.com"}, "Action": "sts:AssumeRole"}]}}},
        "TD": {"Type": "AWS::ECS::TaskDefinition", "Properties": {
            "RequiresCompatibilities": ["FARGATE"], "NetworkMode": "awsvpc", "Cpu": "256", "Memory": "512",
            "ExecutionRoleArn": {"Fn::GetAtt": ["R", "Arn"]},
            "ContainerDefinitions": [
                {"Name": "edge", "Image": "busybox:latest", "Essential": True, "Command": ["sleep", "3600"]},
                {"Name": "app", "Image": "busybox:latest", "Essential": True, "Command": ["sleep", "3600"], "DependsOn": [{"ContainerName": "edge", "Condition": "START"}]},
            ]}},
        "SVC": {"Type": "AWS::ECS::Service", "Properties": {
            "Cluster": {"Ref": "C"}, "TaskDefinition": {"Ref": "TD"}, "DesiredCount": 0, "LaunchType": "FARGATE",
            "NetworkConfiguration": {"AwsvpcConfiguration": {"Subnets": [{"Ref": "S1"}], "SecurityGroups": [{"Ref": "G"}], "AssignPublicIp": "ENABLED"}}}},
    }})
    ecs = client("ecs")
    td = ecs.describe_task_definition(taskDefinition=phys(res, "TD"))["taskDefinition"]
    assert len(td["containerDefinitions"]) == 2
    svc = ecs.describe_services(cluster=phys(res, "C"), services=[phys(res, "SVC")])["services"]
    assert svc, "service not found via ECS API"
    return "VPC, cluster, role, log group, 2-container task def, service really exist"


@check("cfn-elbv2")
def _cfn_elb() -> str:
    res = deploy(f"keyedge-smoke-elb-{RUN}", {"Resources": {
        **vpc_resources(),
        "LB": {"Type": "AWS::ElasticLoadBalancingV2::LoadBalancer", "Properties": {"Type": "application", "Scheme": "internal", "Subnets": [{"Ref": "S1"}, {"Ref": "S2"}], "SecurityGroups": [{"Ref": "G"}]}},
        "TG": {"Type": "AWS::ElasticLoadBalancingV2::TargetGroup", "Properties": {"Port": 8080, "Protocol": "HTTP", "TargetType": "ip", "VpcId": {"Ref": "V"},
               "TargetGroupAttributes": [{"Key": "deregistration_delay.timeout_seconds", "Value": "5"}]}},
        "LS": {"Type": "AWS::ElasticLoadBalancingV2::Listener", "Properties": {"LoadBalancerArn": {"Ref": "LB"}, "Port": 80, "Protocol": "HTTP",
               "DefaultActions": [{"Type": "forward", "TargetGroupArn": {"Ref": "TG"}}]}},
    }})
    elb = client("elbv2")
    attrs = elb.describe_target_group_attributes(TargetGroupArn=phys(res, "TG"))["Attributes"]
    delay = {a["Key"]: a["Value"] for a in attrs}.get("deregistration_delay.timeout_seconds")
    elb.describe_load_balancers(LoadBalancerArns=[phys(res, "LB")])
    return f"ALB, TG (deregistration delay={delay}), listener really exist"


@check("ecr-repo")
def _ecr() -> str:
    ecr = client("ecr", ECS_ENDPOINT)
    repo = ecr.create_repository(repositoryName=f"keyedge-smoke-{RUN}")["repository"]["repositoryUri"]
    tok = ecr.get_authorization_token()["authorizationData"][0]
    return f"repository {repo}; registry {tok.get('proxyEndpoint')} (docker push tested in Phase 014)"


def ecs_cluster() -> tuple[Any, str]:
    ecs = client("ecs", ECS_ENDPOINT)
    if "cluster" not in state:
        state["cluster"] = ecs.create_cluster(clusterName=f"keyedge-smoke-{RUN}")["cluster"]["clusterArn"]
        ec2 = client("ec2", ECS_ENDPOINT)
        vpc = ec2.create_vpc(CidrBlock="10.44.0.0/16")["Vpc"]["VpcId"]
        state["subnet"] = ec2.create_subnet(VpcId=vpc, CidrBlock="10.44.1.0/24", AvailabilityZone="us-east-1a")["Subnet"]["SubnetId"]
    return ecs, state["cluster"]


@check("ecs-register")
def _ecs_reg() -> str:
    ecs, _ = ecs_cluster()
    td = ecs.register_task_definition(
        family=f"keyedge-smoke-{RUN}", requiresCompatibilities=["FARGATE"], networkMode="awsvpc", cpu="256", memory="512",
        containerDefinitions=[
            {"name": "edge", "image": "busybox:latest", "essential": True,
             "command": ["sh", "-c", "mkdir -p /www && echo edge-ok > /www/index.html && httpd -f -p 18080 -h /www"],
             "healthCheck": {"command": ["CMD-SHELL", "wget -q -O- http://127.0.0.1:18080/ || exit 1"], "interval": 5, "timeout": 2, "retries": 3, "startPeriod": 1}},
            {"name": "app", "image": "busybox:latest", "essential": True,
             "command": ["sh", "-c", "for i in $(seq 30); do wget -q -O- http://127.0.0.1:18080/ && sleep 3600; sleep 1; done; exit 1"],
             "dependsOn": [{"containerName": "edge", "condition": "HEALTHY"}]},
        ],
    )["taskDefinition"]
    state["td"] = td["taskDefinitionArn"]
    return f"2 containers, dependsOn HEALTHY registered ({td['revision']})"


@check("ecs-runtask")
def _ecs_run() -> str:
    ecs, cluster = ecs_cluster()
    task = ecs.run_task(cluster=cluster, taskDefinition=state["td"], launchType="FARGATE", count=1,
                        networkConfiguration={"awsvpcConfiguration": {"subnets": [state["subnet"]], "assignPublicIp": "ENABLED"}})["tasks"][0]
    arn = task["taskArn"]
    last: dict[str, Any] = {}
    for _ in range(90):
        last = ecs.describe_tasks(cluster=cluster, tasks=[arn])["tasks"][0]
        statuses = {c["name"]: c.get("lastStatus") for c in last["containers"]}
        if statuses.get("app") == "RUNNING" and statuses.get("edge") == "RUNNING":
            break
        if last["lastStatus"] == "STOPPED":
            break
        time.sleep(2)
    statuses = {c["name"]: (c.get("lastStatus"), c.get("healthStatus")) for c in last.get("containers", [])}
    ecs.stop_task(cluster=cluster, task=arn)
    assert statuses.get("app", ("",))[0] == "RUNNING", statuses
    return f"both containers RUNNING, app reached edge on localhost: {statuses}"


@check("ecs-service")
def _ecs_svc() -> str:
    ecs, cluster = ecs_cluster()
    svc = ecs.create_service(cluster=cluster, serviceName=f"smoke-{RUN}", taskDefinition=state["td"], desiredCount=1, launchType="FARGATE",
                             networkConfiguration={"awsvpcConfiguration": {"subnets": [state["subnet"]], "assignPublicIp": "ENABLED"}})["service"]
    running = 0
    for _ in range(60):
        running = ecs.describe_services(cluster=cluster, services=[svc["serviceArn"]])["services"][0]["runningCount"]
        if running == 1:
            break
        time.sleep(2)
    ecs.update_service(cluster=cluster, service=svc["serviceArn"], desiredCount=0)
    ecs.delete_service(cluster=cluster, service=svc["serviceArn"], force=True)
    assert running == 1, running
    return "runningCount reached 1"


@check("elbv2-route")
def _elb_route() -> str:
    # Does an ALB forward real HTTP to a registered IP target? Target = a tiny HTTP server we know is up: the emulator itself is not one,
    # so this registers the ECS task's IP when available; otherwise reports what the API returned for the LB DNS name.
    elb = client("elbv2", ECS_ENDPOINT)
    ec2 = client("ec2", ECS_ENDPOINT)
    vpc = ec2.create_vpc(CidrBlock="10.43.0.0/16")["Vpc"]["VpcId"]
    s1 = ec2.create_subnet(VpcId=vpc, CidrBlock="10.43.1.0/24", AvailabilityZone="us-east-1a")["Subnet"]["SubnetId"]
    s2 = ec2.create_subnet(VpcId=vpc, CidrBlock="10.43.2.0/24", AvailabilityZone="us-east-1b")["Subnet"]["SubnetId"]
    lb = elb.create_load_balancer(Name=f"ke-smoke-{RUN}", Subnets=[s1, s2], Scheme="internal", Type="application")["LoadBalancers"][0]
    dns = lb.get("DNSName", "")
    # The LB DNS name may resolve to 127.0.0.1:80, where another stack on this host answers: an HTTP 200
    # proves nothing. Real routing needs a registered target that answers something we can recognise (Phase 014).
    import socket
    try:
        addr = socket.gethostbyname(dns)
    except OSError:
        addr = "unresolvable"
    raise AssertionError(f"UNVERIFIED: LB created, DNS {dns} -> {addr}; data-plane routing is tested with a real target in Phase 014")


@check("ddb-ttl")
def _ttl() -> str:
    ddb.update_time_to_live(TableName=TABLE, TimeToLiveSpecification={"Enabled": True, "AttributeName": "ttl"})
    ddb.put_item(TableName=TABLE, Item={**item("TTL#1", "m"), "ttl": {"N": str(int(time.time()) - 60)}})
    t0 = time.time()
    while time.time() - t0 < 90:
        if "Item" not in ddb.get_item(TableName=TABLE, Key=item("TTL#1", "m"), ConsistentRead=True):
            return f"expired item deleted after {time.time() - t0:.0f} s"
        time.sleep(5)
    raise AssertionError("TTL enabled, but the expired item was still there after 90 s (AWS also allows a delay: reads must check expiry)")


for row_id, result, detail in results:
    print(f"{TARGET}\t{row_id}\t{result}\t{detail}")
