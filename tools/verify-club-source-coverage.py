#!/usr/bin/env python3
"""核对 Creator 亲友圈模块的字面请求，不把路由存在当作验收通过。"""
import hashlib
import json
import re
from pathlib import Path

server = Path(__file__).resolve().parents[1]
creator = server.parent / "Client/creator/assets/source-data"
index = json.loads((creator / "index.json").read_text())
router_files = [
    server / "game-transport/src/main/java/com/ruoyi/game/transport" / name
    for name in ("ClubWireRouter.java", "PartnerWireRouter.java", "RoomWireRouter.java", "GameDispatcher.java")
]
implemented = set()
for path in router_files:
    text = path.read_text()
    implemented.update(re.findall(r'"([A-Za-z]+/[A-Za-z]+)"', text))
    if path.name == "PartnerWireRouter.java":
        assert '"partner".equals(request.api())' in text
        commands = re.search(r'COMMANDS\s*=\s*Set.of\((.*?)\);', text, re.S).group(1)
        names = set(re.findall(r'"([A-Za-z]+)"', commands))
        assert names == set(re.findall(r'case "([A-Za-z]+)"', text))
        implemented.update("partner/" + name for name in names)
modules = []
missing = set()
for name in ("club", "clubpartner", "clubbuild", "diamond"):
    suffix = f"/client/modules/{name}/assets/module_{name}.min.js"
    matches = [(url, entry) for url, entry in index["byUrl"].items() if url.endswith(suffix)]
    assert len(matches) == 1, name
    url, entry = matches[0]
    path = creator / (entry["path"] + entry["extension"])
    raw = path.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == entry["sha256"], name
    routes = sorted(set("/".join(pair) for pair in re.findall(r'api\s*:\s*"([A-Za-z]+)"\s*,\s*c\s*:\s*"([A-Za-z]+)"', raw.decode())))
    items = []
    for route in routes:
        if route == "build/getAutoBuildConfig":
            status = "关闭资源状态查询已实现"
        elif route.startswith("build/") or route == "club/setClubMemComment":
            status = "随建设页面暂缓"
        elif route in implemented:
            status = "已有路由实现，行为验收另见证据"
        else:
            status = "缺少路由实现"
            missing.add(route)
        items.append({"route": route, "classification": status})
    modules.append({"module": name, "creatorFile": str(path.relative_to(server.parent)), "sha256": entry["sha256"], "routes": items})

report = {
    "status": "PASS" if not missing else "FAIL",
    "scope": "Creator 打包源文件字面路由核对，动态路由与实际浏览器行为单独验收",
    "buildingScope": "用户暂缓体力、石材和开采，建设页面及其中备注入口保持隐藏",
    "roomGameScope": "当前实际牌局接入 6003，其他玩法单独列为待接入",
    "modules": modules,
    "missingRoutes": sorted(missing),
}
out = server / "docs/evidence/club-full-20261107/creator-source-coverage.json"
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"status": report["status"], "modules": len(modules), "literalRoutes": sum(len(item["routes"]) for item in modules), "missingRoutes": report["missingRoutes"]}, ensure_ascii=False))
assert not missing, "亲友圈模块还有未分类路由"
