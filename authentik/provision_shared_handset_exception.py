"""Provision one explicitly approved exception DAG; no grants, tokens or groups.

Run inside reviewed Authentik, with a private JSON spec. Prepare captures the
exact scoped state; apply refuses drift. Generic PolicyBinding write permission
is never assigned to the portal. Group governance owns membership/read grants.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
from uuid import UUID


def validate_spec(spec):
    required = {"account_pk", "account_uuid", "entitlement_uuid", "positive_policy_uuid", "positive_policy_sha256",
                "dag_uuid", "direct_binding_uuid", "positive_binding_uuid"}
    if set(spec) != required or type(spec["account_pk"]) is not int or spec["account_pk"] < 1:
        raise RuntimeError("Invalid provisioning spec")
    for key in required - {"account_pk", "positive_policy_sha256"}:
        if str(UUID(spec[key])) != spec[key]:
            raise RuntimeError("Spec identifiers must be canonical UUIDs")
    if len({spec["dag_uuid"], spec["direct_binding_uuid"], spec["positive_binding_uuid"]}) != 3:
        raise RuntimeError("Provisioned object identifiers must be distinct")
    source_hash = spec["positive_policy_sha256"]
    if not isinstance(source_hash, str) or len(source_hash) != 64 or any(c not in "0123456789abcdef" for c in source_hash):
        raise RuntimeError("Invalid pinned policy source hash")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def snapshot(spec):
    from authentik.core.models import User, Group
    from authentik.endpoints.models import DeviceAccessGroup, Device, DeviceUserBinding
    from authentik.policies.models import PolicyBinding
    from authentik.policies.expression.models import ExpressionPolicy
    user = User.objects.get(uuid=spec["account_uuid"])
    entitlement = Group.objects.get(pk=spec["entitlement_uuid"])
    policy = ExpressionPolicy.objects.get(pk=spec["positive_policy_uuid"])
    groups = list(DeviceAccessGroup.objects.filter(attributes__contains={"mission-leben.de/purpose": "android-portal", "mission-leben.de/mode": "personal"}))
    bound_ids = set(DeviceUserBinding.objects.filter(target__in=groups, user=user, enabled=True, negate=False,
                                                   group=None, policy=None).values_list("target_id", flat=True))
    matching = [g for g in groups if g.pk in bound_ids or (g.attributes or {}).get("mission-leben.de/user-uuid") == str(user.uuid)
                or str(g.pk) == spec["dag_uuid"] or g.name == "Mission Leben Android - Personal - " + user.username]
    state = {"spec": spec, "user": {"pk": user.pk, "uuid": str(user.uuid), "username": user.username,
             "type": user.type, "active": user.is_active, "attributes": {key: (user.attributes or {}).get(key) for key in
             ("iam_account_kind", "iam_directory_class", "iam_interactive_login_allowed", "iam_noninteractive_account")}},
             "entitlement": {"uuid": str(entitlement.pk), "name": entitlement.name, "is_superuser": entitlement.is_superuser,
                             "direct_members": list(entitlement.users.values_list("pk", flat=True).order_by("pk"))},
             "positive_policy": {"uuid": str(policy.pk), "name": policy.name,
                                 "expression_sha256": hashlib.sha256(policy.expression.encode()).hexdigest()},
             "groups": [{"uuid": str(g.pk), "name": g.name, "mode": g.policy_engine_mode, "attributes": g.attributes} for g in matching],
             "bindings": list(PolicyBinding.objects.filter(target__in=matching).order_by("pk").values("pk", "target_id", "user_id", "group_id", "policy_id", "enabled", "negate", "order", "failure_result", "timeout", "expiring", "expires")),
             "direct_binding_primary": list(DeviceUserBinding.objects.filter(target__in=matching).order_by("pk").values("pk", "is_primary")),
             "devices": list(Device.objects.filter(access_group__in=matching).values("device_uuid", "access_group_id", "expiring", "expires", "attributes")),
             "planned_id_collisions": list(PolicyBinding.objects.filter(pk__in=[spec["direct_binding_uuid"], spec["positive_binding_uuid"]]).values_list("pk", flat=True))}
    return json.loads(json.dumps(state, default=str))


def assert_prerequisites(state):
    spec, user, ent, policy = state["spec"], state["user"], state["entitlement"], state["positive_policy"]
    if (user["pk"] != spec["account_pk"] or not user["active"] or user["type"] != "internal"
        or user["attributes"] != {"iam_account_kind": "shared", "iam_directory_class": "mailbox",
                                  "iam_interactive_login_allowed": False, "iam_noninteractive_account": True}):
        raise RuntimeError("Target account no longer matches the approved exception")
    if ent["name"] != "ENT_SHARED_ACCOUNT_HANDSET" or ent["is_superuser"] or ent["direct_members"]:
        raise RuntimeError("Canonical entitlement is not staged empty")
    if policy["name"] != "Mission Leben Zentral Android - Shared-Diensthandy-Ausnahme positiv" or policy["expression_sha256"] != spec["positive_policy_sha256"]:
        raise RuntimeError("Positive policy is missing or differs from the approved source")
    if state["groups"] or state["devices"] or state["planned_id_collisions"]:
        raise RuntimeError("Existing profile or binding collision; do not migrate or overwrite")


def assert_provisioned(state):
    spec = state["spec"]
    if len(state["groups"]) != 1 or state["groups"][0]["uuid"] != spec["dag_uuid"] or state["groups"][0]["mode"] != "all":
        raise RuntimeError("Provisioned native DAG is not the exact ALL target")
    bindings = {binding["pk"]: binding for binding in state["bindings"]}
    if set(bindings) != {spec["direct_binding_uuid"], spec["positive_binding_uuid"]}:
        raise RuntimeError("Provisioned native graph is not exactly the two required bindings")
    direct, positive = bindings[spec["direct_binding_uuid"]], bindings[spec["positive_binding_uuid"]]
    if any(b["target_id"] != spec["dag_uuid"] or b["enabled"] is not True or b["negate"] is not False
           or b["expiring"] is not False or b["expires"] is not None or b["group_id"] is not None for b in bindings.values()):
        raise RuntimeError("Invalid binding controls")
    if (direct["user_id"] != spec["account_pk"] or direct["policy_id"] is not None or direct["order"] != 10
        or state["direct_binding_primary"] != [{"pk": spec["direct_binding_uuid"], "is_primary": True}]):
        raise RuntimeError("Invalid direct primary user binding")
    if (positive["policy_id"] != spec["positive_policy_uuid"] or positive["user_id"] is not None
        or positive["order"] != 20 or positive["failure_result"] is not False or positive["timeout"] != 2):
        raise RuntimeError("Invalid positive policy binding")
    if state["devices"]:
        raise RuntimeError("Provisioning must not create a device or token")


def provision(spec, username):
    from authentik.endpoints.models import DeviceAccessGroup, DeviceUserBinding
    from authentik.policies.models import PolicyBinding
    attributes = {"mission-leben.de/purpose": "android-portal", "mission-leben.de/mode": "personal",
                  "mission-leben.de/status": "active", "mission-leben.de/username": username,
                  "mission-leben.de/user-uuid": spec["account_uuid"], "mission-leben.de/handset-profile": "shared-account",
                  "mission-leben.de/device-ownership": "company"}
    dag = DeviceAccessGroup.objects.create(pbm_uuid=UUID(spec["dag_uuid"]), name="Mission Leben Android - Personal - " + username,
                                          attributes=attributes, policy_engine_mode="all")
    # Multi-table inheritance: setting only child pk lets the parent's default
    # generate another UUID. Pin the actual PolicyBinding parent field.
    DeviceUserBinding.objects.create(policy_binding_uuid=UUID(spec["direct_binding_uuid"]), target=dag, user_id=spec["account_pk"],
        group=None, policy=None, enabled=True, negate=False, order=10, is_primary=True, expiring=False, expires=None)
    PolicyBinding.objects.create(pk=UUID(spec["positive_binding_uuid"]), target=dag, policy_id=UUID(spec["positive_policy_uuid"]),
        user=None, group=None, enabled=True, negate=False, order=20, failure_result=False, timeout=2, expiring=False, expires=None)


def main():
    from authentik import VERSION
    from django.db import connection, transaction
    from authentik.core.models import User
    from authentik.endpoints.models import DeviceAccessGroup
    from authentik.policies.models import PolicyBinding
    if VERSION != "2026.8.3":
        raise RuntimeError("Unreviewed Authentik version")
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", required=True)
    parser.add_argument("--prepare")
    parser.add_argument("--apply")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--rollback")
    parser.add_argument("--expect-after")
    args = parser.parse_args()
    spec = json.loads(Path(args.spec).read_text())
    validate_spec(spec)
    if args.prepare:
        with transaction.atomic():
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
            state = snapshot(spec)
            assert_prerequisites(state)
        fd = os.open(args.prepare, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(state, stream, sort_keys=True, indent=2)
        print("HANDSET_PREPARED=" + digest(state))
    elif args.apply:
        with transaction.atomic():
            User.objects.select_for_update().get(uuid=spec["account_uuid"])
            state = snapshot(spec)
            if digest(state) != args.apply:
                raise RuntimeError("Scoped state changed since backup")
            assert_prerequisites(state)
            provision(spec, state["user"]["username"])
            after = snapshot(spec)
            assert_provisioned(after)
        print("HANDSET_PROVISIONED=" + json.dumps({"sha256": digest(after), "state": after}))
    elif args.verify:
        with transaction.atomic():
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
            state = snapshot(spec)
        print("HANDSET_VERIFIED=" + json.dumps({"sha256": digest(state), "state": state}))
    elif args.rollback:
        before = json.loads(Path(args.rollback).read_text())
        with transaction.atomic():
            if not args.expect_after or digest(snapshot(spec)) != args.expect_after:
                raise RuntimeError("Refusing rollback after concurrent state change")
            dag = DeviceAccessGroup.objects.get(pk=spec["dag_uuid"])
            if dag.device_set.exists():
                raise RuntimeError("Provisioned DAG has device evidence; disable exception first, never delete devices")
            PolicyBinding.objects.filter(pk__in=[spec["direct_binding_uuid"], spec["positive_binding_uuid"]]).delete()
            dag.delete()
            if digest(snapshot(spec)) != digest(before):
                raise RuntimeError("Rollback did not restore exact scoped baseline")
        print("HANDSET_ROLLED_BACK=" + digest(before))
    else:
        raise RuntimeError("Choose prepare, apply, verify or guarded rollback")


if __name__ == "__main__":
    main()
