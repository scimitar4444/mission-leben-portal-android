import ast
from pathlib import Path
from textwrap import indent
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[2]


def operator_source(path):
    tree = ast.parse(path.read_text())
    return next(ast.literal_eval(node.value) for node in tree.body
                if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "OPERATOR_BODY" for t in node.targets))


def group(name, level="Einrichtung", **overrides):
    return SimpleNamespace(name=name, attributes={"iam_group_type": "organization_unit", "iam_managed": True,
                                                "iam_plan_status": "AKTIV", "iam_org_level": level, **overrides})


@pytest.mark.parametrize("names,facilities,active,allowed", [
    (["BR_IT_MANAGEMENT"], [], True, True),
    (["BR_EINRICHTUNGSLEITUNG"], [group("ORG_ML_H015"), group("ORG_ML_H016")], True, True),
    (["BR_PFLEGEDIENSTLEITUNG"], [group("ORG_ML_H015")], True, True),
    (["BR_STELLVERTRETENDE_EINRICHTUNGSLEITUNG"], [group("ORG_ML_H015")], True, True),
    (["BR_EINRICHTUNGSLEITUNG"], [], True, False),
    (["BR_ABTEILUNGSLEITUNG"], [group("ORG_ML_H001", "Geschäftseinheit/Standort")], True, False),
    (["BR_GESCHAEFTSFUEHRUNG"], [group("ORG_ML_H015")], True, False),
    (["BR_PFLEGEDIENSTLEITUNG"], [group("ORG_ML_H015_01", "Teilbereich")], True, False),
    (["BR_PFLEGEDIENSTLEITUNG"], [group("ORG_ML_H015", iam_managed=False)], True, False),
    ([], [group("ORG_ML_H015")], True, False),
    (["BR_IT_MANAGEMENT"], [], False, False),
])
def test_exclusive_tiles_require_existing_real_operator_scope(names, facilities, active, allowed):
    source = operator_source(ROOT / "authentik/device_portal_navigation.py")
    request = SimpleNamespace(user=SimpleNamespace(is_active=active, all_groups=lambda: [group(n) for n in names] + facilities))
    namespace = {}
    exec("def manage(request):\n" + indent(source + "return operator\n", "    "), namespace)
    exec("def guide(request):\n" + indent(source + "return not operator\n", "    "), namespace)
    assert namespace["manage"](request) is allowed
    assert namespace["guide"](request) is (active and not allowed)


def test_bootstrap_preserves_exact_reviewed_navigation_rule():
    assert operator_source(ROOT / "authentik/device_portal_navigation.py") == operator_source(ROOT / "authentik/bootstrap_enrollment_portal.py")
    source = (ROOT / "authentik/bootstrap_enrollment_portal.py").read_text()
    assert '"meta_hide": True' in source
    assert '"mission-leben-device-manage"' in source
    assert '"mission-leben-device-guide"' in source
