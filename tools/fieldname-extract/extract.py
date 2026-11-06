#!/usr/bin/env python3
"""MoS4J 字段名静态抽取（T0.4，一次性）。

从 Go 参考仓库抽取 `json:"..."` tag，生成 docs/contract/fieldnames.json。
冻结后不随 Go 演进重抽；如确需重抽（例如对拍回补时），输出须走 PR 评审比对 diff。

用法：python3 tools/fieldname-extract/extract.py <go-repo-path>
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import datetime

SCOPE = ["session/", "llm/model.go", "agent/hook/", "agent/approval/"]


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    go = os.path.abspath(sys.argv[1])
    files = subprocess.run(
        ["grep", "-rl", 'json:"', "--include=*.go"] + SCOPE,
        capture_output=True, text=True, cwd=go,
    ).stdout.split()

    seen = {}
    for f in sorted(set(files)):
        src = open(os.path.join(go, f), encoding="utf-8").read()
        for m in re.finditer(r'json:"([^"]+)"', src):
            tag = m.group(1)
            name = tag.split(",")[0]
            if not name:
                continue  # json:"-" 或匿名 tag 不入清单
            seen[(name, f)] = {
                "file": f, "tag": tag, "name": name, "options": tag.split(",")[1:],
            }

    out = sorted(seen.values(), key=lambda e: (e["file"], e["tag"]))
    names = sorted(set(e["name"] for e in out))
    doc = {
        "_comment": "MoS4J 事件与载荷字段名冻结清单（T0.4 一次性抽取自 Go 参考仓库，之后不随 Go 演进重抽）。守护测试 FieldNameContractTest 的基线。",
        "_source": "参考仓库 ../mos（只读）；范围：" + ", ".join(SCOPE),
        "_extracted": datetime.datetime.now().strftime("%Y-%m-%d"),
        "_count": len(out),
        "_names_sha256": hashlib.sha256("\n".join(names).encode()).hexdigest(),
        "fields": out,
    }
    dest = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "contract", "fieldnames.json")
    with open(dest, "w", encoding="utf-8") as fp:
        json.dump(doc, fp, ensure_ascii=False, indent=2)
    print(f"字段条目: {doc['_count']}  唯一字段名: {len(names)}  sha256: {doc['_names_sha256']}")


if __name__ == "__main__":
    main()
