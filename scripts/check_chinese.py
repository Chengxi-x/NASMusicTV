import re
import sys

# 命中的字符串里常混有 CJK 之外的符号（如 RemoteControlHtml.kt 的 ✕ U+2715），
# Windows 控制台默认 CP936 编码不了 ⇒ print 直接抛 UnicodeEncodeError。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

files = [
    r'app/src/main/java/com/nasmusic/tv/net/BackupTransferServer.kt',
    r'app/src/main/java/com/nasmusic/tv/net/ModelTransferServer.kt',
    r'app/src/main/java/com/nasmusic/tv/net/RemoteControlHtml.kt',
]

for fpath in files:
    try:
        with open(fpath, encoding='utf-8') as f:
            content = f.read()
    except OSError as e:
        print(f'Error reading {fpath}: {e}')
        continue
    # Find Chinese strings in quotes
    chinese = re.findall(r'"([^"]*[\u4e00-\u9fff][^"]*?)"', content)
    if chinese:
        fname = fpath.split('\\')[-1]
        print(f'\n=== {fname} === ({len(chinese)} strings)')
        for s in chinese[:15]:
            print(f'  {s[:60]}')
        if len(chinese) > 15:
            print(f'  ... and {len(chinese)-15} more')
