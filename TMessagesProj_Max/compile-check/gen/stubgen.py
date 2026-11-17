"""Генератор заглушек: берёт настоящие объявления из исходников Telegram и заменяет тела."""
import re, sys, os
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SRC = os.path.join(REPO, "TMessagesProj/src/main/java/")

def default_return(sig):
    m = re.match(r'\s*public\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?([\w.<>\[\], ?]+?)\s+\w+\s*\(', sig)
    if not m: return ""
    t = m.group(1).strip()
    if t == "void": return ""
    if t in ("int","long","short","byte","char"): return "return 0;"
    if t in ("float","double"): return "return 0;"
    if t == "boolean": return "return false;"
    return "return null;"

def methods(path, patterns):
    text = open(SRC + path, encoding='utf-8').read().split("\n")
    out = []
    for line in text:
        s = line.strip()
        if not s.startswith("public "): continue
        if "(" not in s or not s.endswith("{"): continue
        m = re.search(r'(\w+)\s*\(', s)
        if not m: continue
        name = m.group(1)
        if any(re.fullmatch(p, name) for p in patterns):
            sig = s[:-1].strip()
            # конструктор
            if re.match(r'public\s+\w+\s*\(', sig) and not re.match(r'public\s+(static|final|abstract|void|int|boolean|long|float)\b', sig):
                out.append("    " + sig + " { super(null); }" if False else "    " + sig + " { }")
            else:
                out.append("    " + sig + " { " + default_return(sig) + " }")
    return out

def int_fields(path, prefix_regex):
    out = []
    for line in open(SRC + path, encoding='utf-8'):
        m = re.match(r'\s*public static final int (' + prefix_regex + r')\s*=', line)
        if m: out.append("    public static final int %s = %d;" % (m.group(1), len(out) + 1))
    return out
