#!/usr/bin/env python3
"""通过 BPM 管理接口发布资产导出审批。运行前设置 DATAHUB_ADMIN_API 和管理员的 DATAHUB_ACCESS_TOKEN。"""

import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen


KEY = "asset_data_export"
NAME = "资产数据导出审批"
FORM_NAME = "资产数据导出申请"
FIELDS = {"exportId": "申请编号", "datasourceId": "数据源编号", "tableName": "源表",
          "rowLimit": "最大导出行数", "reason": "申请用途"}


def api(path, *, method="GET", params=None, body=None):
    url = os.environ["DATAHUB_ADMIN_API"].rstrip("/") + path
    if params:
        url += "?" + urlencode(params)
    payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    request = Request(url, data=payload, method=method, headers={
        "Authorization": "Bearer " + os.environ["DATAHUB_ACCESS_TOKEN"],
        "Content-Type": "application/json",
    })
    with urlopen(request, timeout=30) as response:
        result = json.load(response)
    if result["code"] != 0:
        raise RuntimeError(f"{path} ({result['code']}): {result.get('msg')}")
    return result["data"]


def ensure_form():
    forms = api("/bpm/form/simple-list")
    for form in forms:
        if form["name"] == FORM_NAME:
            saved = api("/bpm/form/get", params={"id": form["id"]})
            if {json.loads(field)["field"] for field in saved["fields"]} != set(FIELDS):
                raise ValueError("已有同名表单的字段与资产导出契约不符，请在流程表单中核对")
            return form["id"]
    fields = [json.dumps({"type": "input", "field": key, "title": title, "props": {"disabled": True}},
                         ensure_ascii=False) for key, title in FIELDS.items()]
    return api("/bpm/form/create", method="POST", body={
        "name": FORM_NAME, "status": 0, "remark": "由资产查询提交，审批时只读。",
        "conf": json.dumps({"form": {"layout": "vertical"}, "submitBtn": False, "resetBtn": False}),
        "fields": fields,
    })


def ensure_category():
    for category in api("/bpm/category/simple-list"):
        if category["code"] == "asset":
            return category["code"]
    api("/bpm/category/create", method="POST", body={
        "name": "数据资产", "code": "asset", "status": 0, "sort": 20,
    })
    return "asset"


def process_model(form_id, category, manager_id):
    readonly = [{"field": key, "permission": "1"} for key in FIELDS]
    reviewer = {
        "id": "ExportReview", "type": 11, "name": "部门负责人审批",
        "showText": "申请人所属部门的独立负责人", "candidateStrategy": 37, "candidateParam": "1",
        "assignStartUserHandlerType": 4,
        "approveType": 1, "approveMethod": 1, "reasonRequire": True, "fieldsPermission": readonly,
        "rejectHandler": {"type": 1}, "assignEmptyHandler": {"type": 2},
        "buttonsSetting": [{"id": n, "displayName": title, "enable": n in (1, 2)}
                           for n, title in enumerate(["通过", "拒绝", "转办", "委派", "加签", "退回", "抄送"], 1)],
        "childNode": {"id": "EndEvent", "type": 1, "name": "结束"},
    }
    return {
        "key": KEY, "name": NAME, "type": 20, "formType": 10, "formId": form_id, "category": category,
        "description": "从资产查询申请，部门负责人审阅用途、表和行数，通过后申请人可在 24 小时内下载一次。",
        "managerUserIds": [manager_id], "startUserIds": [], "startDeptIds": [], "visible": False,
        "allowCancelRunningProcess": True, "allowWithdrawTask": False,
        "simpleModel": {"id": "StartUserNode", "type": 10, "name": "发起人", "fieldsPermission": readonly,
                        "childNode": reviewer},
    }


def active_definition():
    definitions = api("/bpm/process-definition/page", params={"key": KEY, "pageNo": 1, "pageSize": 100})
    for definition in definitions["list"]:
        if definition["key"] == KEY and definition["suspensionState"] == 1:
            return definition["id"]
    return None


def publish():
    current = active_definition()
    if current:
        return current
    form_id = ensure_form()
    category = ensure_category()
    manager_id = api("/system/auth/get-permission-info")["user"]["id"]
    model = process_model(form_id, category, manager_id)
    existing = next((row for row in api("/bpm/model/list") if row["key"] == KEY), None)
    if existing:
        saved = api("/bpm/model/export", params={"id": existing["id"]})
        review = saved.get("simpleModel", {}).get("childNode", {})
        expected = model["simpleModel"]["childNode"]
        settings = ("candidateStrategy", "candidateParam", "assignStartUserHandlerType", "assignEmptyHandler",
                    "approveType", "rejectHandler")
        if saved.get("formId") != form_id or any(review.get(k) != expected[k] for k in settings):
            raise ValueError("已有同标识模型的审批设置不同，请在流程模型中核对后发布")
        model_id = existing["id"]
    else:
        model_id = api("/bpm/model/create", method="POST", body=model)
    api("/bpm/model/deploy", method="POST", params={"id": model_id})
    deployed = active_definition()
    if not deployed:
        raise RuntimeError("BPM 发布后未找到已启用的资产导出审批定义")
    return deployed


if __name__ == "__main__":
    print(publish())
