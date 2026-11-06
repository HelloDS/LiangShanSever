#!/usr/bin/env python3
"""从实际 Surefire 报告生成脱敏验收摘要，不复制日志/凭据。"""
import datetime
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
suites = {}
for report in root.glob('*/target/surefire-reports/TEST-*.xml'):
    for case in ET.parse(report).getroot().findall('testcase'):
        suite = suites.setdefault(case.attrib['classname'], {})
        suite[case.attrib['name']] = {
            'seconds': float(case.get('time', '0')),
            'failure': case.find('failure') is not None,
            'error': case.find('error') is not None,
            'skipped': case.find('skipped') is not None,
        }
if not suites:
    raise SystemExit('没有实际测试报告，不能生成通过记录')
rows = []
for name, cases in sorted(suites.items()):
    rows.append(dict(suite=name, tests=len(cases),
                     failures=sum(c['failure'] for c in cases.values()),
                     errors=sum(c['error'] for c in cases.values()),
                     skipped=sum(c['skipped'] for c in cases.values()),
                     seconds=round(sum(c['seconds'] for c in cases.values()), 3),
                     cases=sorted(cases)))
passed = not any(s['failures'] or s['errors'] or s['skipped'] for s in rows)
result = dict(executedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              suites=rows, total=sum(s['tests'] for s in rows), result='PASS' if passed else 'FAIL')
(root / 'docs/evidence/integration-tests.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(f"已收集 {result['total']} 项实际测试：{result['result']}")
raise SystemExit(0 if passed else 1)
