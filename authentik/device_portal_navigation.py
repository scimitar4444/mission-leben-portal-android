"""Narrow, version-guarded device tile migration; never executes the full bootstrap.

Run inside Authentik with --prepare /private/before.json, then --apply with the
reported canonical SHA. Proxy access remains unchanged so employee /self works.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

OPERATOR_BODY = '''
if not request.user.is_active:
    return False
groups = list(request.user.all_groups())
names = {group.name for group in groups}
operator = "BR_IT_MANAGEMENT" in names
if not operator and names.intersection({"BR_EINRICHTUNGSLEITUNG", "BR_PFLEGEDIENSTLEITUNG", "BR_STELLVERTRETENDE_EINRICHTUNGSLEITUNG"}):
    import re
    for group in groups:
        attrs = group.attributes or {}
        if (re.fullmatch(r"ORG_ML_H[0-9]{3}(?:_[0-9]{2})?", group.name)
            and attrs.get("iam_group_type") == "organization_unit"
            and attrs.get("iam_managed") is True
            and attrs.get("iam_plan_status") in {"UMGESETZT_UEBERGANG", "AKTIV"}
            and (attrs.get("iam_org_level") in {"Einrichtung", "Einrichtung/Verbund"}
                 or (group.name == "ORG_ML_H001" and attrs.get("iam_org_level") == "Geschäftseinheit/Standort"))):
            operator = True
            break
'''
SPECS = (
    ("mission-leben-device-manage", "Gerät einrichten", "https://geraete.mission-leben.de/",
     "Mission Leben Geräte-Navigation - Einrichter", OPERATOR_BODY + "return operator\n"),
    ("mission-leben-device-guide", "App installieren", "https://geraete.mission-leben.de/download",
     "Mission Leben Geräte-Navigation - Mitarbeitende", OPERATOR_BODY + "return not operator\n"),
)


def snapshot():
    from authentik.core.models import Application
    from authentik.policies.expression.models import ExpressionPolicy
    from authentik.policies.models import PolicyBinding
    slugs = ["mission-leben-device-init", *[spec[0] for spec in SPECS]]
    names = [spec[3] for spec in SPECS]
    fields = ["pk", "slug", "name", "provider_id", "meta_launch_url", "meta_description",
              "meta_publisher", "meta_hide", "open_in_new_tab", "policy_engine_mode"]
    apps = list(Application.objects.filter(slug__in=slugs).order_by("slug").values(*fields))
    policies = list(ExpressionPolicy.objects.filter(name__in=names).order_by("name")
                    .values("pk", "name", "expression"))
    bindings = list(PolicyBinding.objects.filter(target_id__in=[app["pk"] for app in apps])
                    .order_by("pk").values("pk", "target_id", "policy_id", "group_id", "user_id",
                                           "enabled", "negate", "failure_result", "order"))
    return json.loads(json.dumps({"applications": apps, "policies": policies, "bindings": bindings}, default=str))


def digest(data):
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def apply_navigation():
    from authentik.core.models import Application
    from authentik.policies.expression.models import ExpressionPolicy
    from authentik.policies.models import PolicyBinding, PolicyEngineMode
    proxy_app = Application.objects.get(slug="mission-leben-device-init")
    if proxy_app.provider_id != 29:
        raise RuntimeError("Unexpected device proxy provider; manual review required")
    proxy_app.meta_hide = True
    proxy_app.save(update_fields=["meta_hide"])
    for slug, name, url, policy_name, expression in SPECS:
        app, _ = Application.objects.get_or_create(slug=slug, defaults={"name": name})
        if app.provider_id is not None:
            raise RuntimeError("Navigation tile must not own a provider")
        policy, _ = ExpressionPolicy.objects.get_or_create(name=policy_name, defaults={"expression": expression})
        if PolicyBinding.objects.filter(target=app).exclude(policy=policy, group=None, user=None).exists():
            raise RuntimeError("Unexpected navigation binding")
        policy.expression = expression
        policy.save(update_fields=["expression"])
        app.name = name
        app.meta_launch_url = url
        app.meta_description = "Geräte verwalten und einrichten" if slug.endswith("manage") else "Android-App installieren und persönlich anmelden"
        app.meta_publisher = "Mission Leben"
        app.meta_hide = False
        app.open_in_new_tab = True
        app.policy_engine_mode = PolicyEngineMode.MODE_ALL
        app.save()
        binding, _ = PolicyBinding.objects.get_or_create(target=app, policy=policy, defaults={"order": 0})
        binding.enabled = True
        binding.negate = False
        binding.failure_result = False
        binding.order = 0
        binding.save()


def rollback_navigation(before):
    """Restore only recorded navigation objects after an exact after-state CAS."""
    from authentik.core.models import Application
    from authentik.policies.expression.models import ExpressionPolicy
    from authentik.policies.models import PolicyBinding
    old_apps = {row["slug"]: row for row in before["applications"]}
    old_policies = {row["name"]: row for row in before["policies"]}
    for slug, _, _, policy_name, _ in SPECS:
        app = Application.objects.filter(slug=slug).first()
        if app:
            PolicyBinding.objects.filter(target=app).delete()
            if slug not in old_apps:
                app.delete()
        if policy_name not in old_policies:
            policy = ExpressionPolicy.objects.filter(name=policy_name).first()
            if policy and PolicyBinding.objects.filter(policy=policy).exists():
                raise RuntimeError("Created navigation policy acquired another consumer")
            if policy:
                policy.delete()
    for row in before["applications"]:
        values = {key: value for key, value in row.items() if key != "pk"}
        Application.objects.filter(pk=row["pk"]).update(**values)
    for row in before["policies"]:
        ExpressionPolicy.objects.filter(pk=row["pk"]).update(name=row["name"], expression=row["expression"])
    own_ids = {row["pk"] for row in before["applications"] if row["slug"] != "mission-leben-device-init"}
    for row in before["bindings"]:
        if row["target_id"] in own_ids:
            PolicyBinding.objects.create(**row)
    if digest(snapshot()) != digest(before):
        raise RuntimeError("Rollback did not reproduce the exact prior state")


def acceptance(username):
    """Actual installed PolicyEngine; scenario users are memory-only copies."""
    import copy
    from authentik.core.models import Application, Group, User
    from authentik.policies.engine import PolicyEngine
    from django.test import RequestFactory
    user = User.objects.get(username=username)
    manage = Application.objects.get(slug=SPECS[0][0])
    guide = Application.objects.get(slug=SPECS[1][0])
    house = Group.objects.get(name="ORG_ML_H015")
    cases = [("real_current_it", user, True)]
    for label, names, active, expected in (
        ("employee", [], True, False),
        ("el_with_house", ["BR_EINRICHTUNGSLEITUNG"], True, True),
        ("pdl_with_house", ["BR_PFLEGEDIENSTLEITUNG"], True, True),
        ("deputy_el_with_house", ["BR_STELLVERTRETENDE_EINRICHTUNGSLEITUNG"], True, True),
        ("el_without_house", ["BR_EINRICHTUNGSLEITUNG"], True, False),
        ("central_department_lead", ["BR_ABTEILUNGSLEITUNG"], True, False),
        ("inactive_it", ["BR_IT_MANAGEMENT"], False, False),
    ):
        principal = copy.copy(user)
        principal.is_active = active
        principal.__dict__.pop("_actor", None)
        groups = list(Group.objects.filter(name__in=names))
        if label.endswith("with_house"):
            groups.append(house)
        principal.all_groups = lambda groups=groups: groups
        cases.append((label, principal, expected))
    result = []
    for label, principal, expected in cases:
        request = RequestFactory().get("/api/v3/core/applications/")
        request.user = principal
        outcomes = []
        for app in (manage, guide):
            engine = PolicyEngine(app, principal, request)
            engine.request.debug = True  # Native diagnostic mode: no events/cache writes.
            engine.use_cache = False
            engine.empty_result = False
            outcome = engine.build().result
            if outcome.messages:
                raise RuntimeError(f"Navigation policy returned diagnostic errors: {outcome.messages}")
            outcomes.append(outcome.passing)
        wanted = [expected, principal.is_active and not expected]
        if outcomes != wanted:
            raise RuntimeError(f"Navigation acceptance failed: {label} {outcomes} != {wanted}")
        result.append({"case": label, "manage": outcomes[0], "guide": outcomes[1], "pass": True})
    proxy = Application.objects.get(slug="mission-leben-device-init")
    if not proxy.meta_hide or proxy.provider_id != 29 or proxy.policies.exists():
        raise RuntimeError("Proxy access or visibility changed unexpectedly")
    return {"cases": result, "real_current_it": True, "scenario_mutations_persisted": False,
            "proxy_provider": proxy.provider_id, "proxy_hidden": proxy.meta_hide,
            "proxy_bindings": proxy.policies.count(), "navigation_new_tab": manage.open_in_new_tab and guide.open_in_new_tab}


def main():
    from authentik import VERSION
    from django.db import connection, transaction
    if VERSION != "2026.8.3":
        raise RuntimeError("Unsupported Authentik version")
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare")
    parser.add_argument("--apply")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--rollback", help="Private before.json")
    parser.add_argument("--expect-after", help="Exact canonical current-state SHA")
    parser.add_argument("--acceptance", action="store_true")
    parser.add_argument("--acceptance-user")
    args = parser.parse_args()
    if args.prepare:
        with transaction.atomic():
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
            before = snapshot()
        fd = os.open(args.prepare, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(before, stream, sort_keys=True, indent=2)
        print("NAVIGATION_PREPARED=" + digest(before))
    elif args.apply:
        with transaction.atomic():
            from authentik.core.models import Application
            list(Application.objects.select_for_update().filter(slug__in=["mission-leben-device-init", *[spec[0] for spec in SPECS]]))
            if digest(snapshot()) != args.apply:
                raise RuntimeError("Navigation state changed since backup")
            apply_navigation()
            after = snapshot()
        print("NAVIGATION_APPLIED=" + json.dumps({"sha256": digest(after), "snapshot": after}))
    elif args.rollback:
        if not args.expect_after:
            raise RuntimeError("Rollback requires exact after-state hash")
        before = json.loads(Path(args.rollback).read_text())
        with transaction.atomic():
            if digest(snapshot()) != args.expect_after:
                raise RuntimeError("Navigation changed since rollout; refusing rollback")
            rollback_navigation(before)
        print("NAVIGATION_ROLLED_BACK=" + digest(before))
    elif args.acceptance:
        if not args.acceptance_user:
            raise RuntimeError("Acceptance requires an explicitly selected existing IT user")
        with transaction.atomic():
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
            result = acceptance(args.acceptance_user)
        print("NAVIGATION_ACCEPTANCE=" + json.dumps(result))
    elif args.verify:
        with transaction.atomic():
            with connection.cursor() as cursor:
                cursor.execute("SET TRANSACTION READ ONLY")
            data = snapshot()
        print("NAVIGATION_VERIFIED=" + json.dumps({"sha256": digest(data), "snapshot": data}))
    else:
        raise RuntimeError("Choose prepare, guarded apply or read-only verify")


if __name__ == "__main__":
    main()
