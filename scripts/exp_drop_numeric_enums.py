"""实验用：删除 glossary 里的数字编码枚举块。

模拟「表多了、值域无法人工枚举」的场景，看 Agent 能否用 INSPECT_COLUMN 查回来。
只删 status 类（值是 1/2/3/4 这种猜不出来的编码），保留英文值枚举（delivered 这种能猜）。
"""
import re
import sys

PATH = 'src/main/resources/schema/glossary.yml'

with open(PATH, encoding='utf-8') as f:
    lines = f.read().split('\n')

out = []
i = 0
removed = []
while i < len(lines):
    line = lines[i]
    if line.strip() == 'enum:':
        # 收集紧随其后的 value 行
        j = i + 1
        vals = []
        while j < len(lines) and lines[j].strip().startswith('- { value:'):
            m = re.search(r'value:\s*"([^"]*)"', lines[j])
            if m:
                vals.append(m.group(1))
            j += 1
        # 只删「全部是数字」的枚举块
        if vals and all(v.isdigit() for v in vals):
            col = '?'
            for k in range(len(out) - 1, -1, -1):
                mm = re.match(r'^      (\w+):\s*$', out[k])
                if mm:
                    col = mm.group(1)
                    break
            removed.append('%s = %s' % (col, ','.join(vals)))
            i = j
            continue
    out.append(line)
    i += 1

with open(PATH, 'w', encoding='utf-8') as f:
    f.write('\n'.join(out))

print('删除的数字枚举块: %d' % len(removed))
for r in removed:
    print('  -', r)
print('剩余 enum 块: %d' % sum(1 for l in out if l.strip() == 'enum:'))
