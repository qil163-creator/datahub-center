from pathlib import Path

p = Path("sql/postgresql/datahub-center.sql")
text = p.read_text(encoding="utf-8")
old_len = len(text)
subs = [
    (
        "-- Included migrations through: 0031",
        "-- Included migrations through: 0032",
    ),
    (
        '"collect_issue_detail" int2 NOT NULL DEFAULT 0,',
        '"collect_issue_detail" int2 NOT NULL DEFAULT 1,',
    ),
    (
        'COMMENT ON COLUMN "datahub-center"."dh_governance_quality_rule"."collect_issue_detail" IS \'采集问题明细：0-否 1-是（留存可回源定位记录）\';',
        'COMMENT ON COLUMN "datahub-center"."dh_governance_quality_rule"."collect_issue_detail" IS \'采集问题明细：0-否 1-是（留存可回源定位记录，默认开启）\';',
    ),
]
for src, dst in subs:
    count = text.count(src)
    if count != 1:
        raise SystemExit(f"expected 1 occurrence of {src[:80]!r}, found {count}")
    text = text.replace(src, dst, 1)
p.write_text(text, encoding="utf-8", newline="\n")
print(f"ok {old_len} -> {len(text)} newlines={text.count(chr(10))}")
