import io

MIUI = 'app/src/main/java/com/notifrelay/ui/miuix/MiuixUI.kt'
BLOCK = '.tmp_rewrite/block.txt'

lines = io.open(MIUI, encoding='utf-8').read().split('\n')
start = None
end = None
for i, line in enumerate(lines):
    if line.strip() == 'private fun MiuixAppContent(manager: BleRelayManager, widthSizeClass: WindowWidthSizeClass) {':
        start = i - 1  # '@Composable' 行
        break
for i, line in enumerate(lines):
    if '设备页' in line and line.strip().startswith('// ='):
        end = i
        break
assert start is not None and end is not None, (start, end)
assert lines[start].strip() == '@Composable', lines[start]

block = io.open(BLOCK, encoding='utf-8').read().rstrip('\n').split('\n')
lines[start:end] = block
io.open(MIUI, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('replaced lines', start + 1, 'to', end, 'with', len(block), 'lines')
