"""Narrow, idempotent policy rollout for shared-account company handsets.

Run inside ``ak shell`` with ML_ANDROID_POLICY_SOURCE pointing at a copy of
bootstrap_endpoint_devices.py. Default action is read-only ``check``. Set
ML_ANDROID_POLICY_ACTION=apply only after saving the three live expressions.
For rollback, point ML_ANDROID_POLICY_BACKUP at that JSON snapshot and set
ML_ANDROID_POLICY_ACTION=rollback. This script changes no flow bindings,
providers, stages, groups, users, or Authentik core code.
"""

import ast
import hashlib
import json
import os
from pathlib import Path

from django.db import transaction

from authentik.policies.expression.models import ExpressionPolicy


POLICIES = {
    "Mission Leben Zentral Android - Gerätezugriff verweigern": (
        "DENY_DEVICE_ACCESS_EXPRESSION",
        "2ed2276785e4155f8682f655e364278fb45ffdf6a0d2e2940c00c06e484f4ca0",
        "ad4848f9f112959ffab731c7ad8baa3ccf9985b6daf3d46e7867a89ad9fd868d",
    ),
    "Mission Leben Zentral Android - OIDC-Gerätezugriff verweigern": (
        "DENY_OIDC_DEVICE_ACCESS_EXPRESSION",
        "5676dd258099adc3369caadfcea94ae512ec117d550fee16ac2de89c495edb91",
        "4eaf14dadee5d22c6b5e3049778580d78dd8f2197ae2d388bc3b2bc1b1c4c824",
    ),
    "Mission Leben Zentral Android - Vorhandenes TOTP bei Wiederanmeldung": (
        "PERSONAL_REAUTHENTICATION_TOTP_EXPRESSION",
        "5d42d711494738e41f3bb7e38adcd45d947adc1299ef6d54d4c9ea75315b3178",
        "6e7c2a81f14fb5fbe82355e8e70e0b59902d040bf6d0209d6ad55478c1b837a3",
    ),
}


def digest(expression: str) -> str:
    return hashlib.sha256(expression.encode("utf-8")).hexdigest()


def source_expressions() -> dict[str, str]:
    source = Path(os.environ["ML_ANDROID_POLICY_SOURCE"])
    tree = ast.parse(source.read_text(encoding="utf-8"))
    wanted = {item[0] for item in POLICIES.values()}
    expressions = {
        node.targets[0].id: ast.literal_eval(node.value)
        for node in tree.body
        if isinstance(node, ast.Assign)
        and len(node.targets) == 1
        and isinstance(node.targets[0], ast.Name)
        and node.targets[0].id in wanted
    }
    if set(expressions) != wanted:
        raise RuntimeError("Android policy source does not contain exactly the expected expressions")
    result = {name: expressions[variable] for name, (variable, _, _) in POLICIES.items()}
    for name, expression in result.items():
        if digest(expression) != POLICIES[name][2]:
            raise RuntimeError(f"Android policy source drifted: {name}")
    return result


def rollback_expressions() -> tuple[dict[str, str], dict[str, str]]:
    records = json.loads(Path(os.environ["ML_ANDROID_POLICY_BACKUP"]).read_text(encoding="utf-8"))
    if len(records) != len(POLICIES) or {item["name"] for item in records} != set(POLICIES):
        raise RuntimeError("Rollback snapshot has unexpected policy names")
    result = {item["name"]: item["expression"] for item in records}
    for name, expression in result.items():
        if digest(expression) != POLICIES[name][1]:
            raise RuntimeError(f"Rollback snapshot drifted: {name}")
    return result, {item["name"]: str(item["pk"]) for item in records}


action = os.environ.get("ML_ANDROID_POLICY_ACTION", "check")
if action not in {"check", "apply", "rollback"}:
    raise RuntimeError("ML_ANDROID_POLICY_ACTION must be check, apply or rollback")
if action == "rollback":
    target_expressions, backup_ids = rollback_expressions()
else:
    target_expressions = source_expressions()
    backup_ids = {}

with transaction.atomic():
    live = {
        policy.name: policy
        for policy in ExpressionPolicy.objects.select_for_update().filter(name__in=POLICIES)
    }
    if set(live) != set(POLICIES):
        raise RuntimeError("One or more Android policies are missing")
    for name, (_, old_hash, new_hash) in POLICIES.items():
        if action == "rollback" and str(live[name].pk) != backup_ids[name]:
            raise RuntimeError(f"Rollback policy id mismatch: {name}")
        current_hash = digest(live[name].expression)
        if current_hash not in {old_hash, new_hash}:
            raise RuntimeError(f"Live Android policy drifted: {name}")
        if action == "check":
            print(f"{name}: {current_hash} (check only)")
            continue
        target_expression = target_expressions[name]
        if live[name].expression != target_expression:
            live[name].expression = target_expression
            live[name].save(update_fields=["expression"])
        print(f"{name}: {current_hash} -> {digest(target_expression)} ({action})")
