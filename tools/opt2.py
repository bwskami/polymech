import subprocess, sys
sys.stdout.reconfigure(encoding="utf-8")

def bp(p):
    return subprocess.run(["cygpath", "-w", p], capture_output=True, text=True).stdout.strip()
def rf(p):
    with open(bp(p), "r", encoding="utf-8") as f: return f.read()
def wf(p, c):
    with open(bp(p), "w", encoding="utf-8") as f: f.write(c)

PLANET = "/c/modIDEA/polymech/polymech-template-1.21.1/src/main/java/com/mss/polymech/client/gui/widget/planet"

def find_method(code, sig):
    s = code.find(sig)
    if s < 0: return -1, -1
    bc = 0; found = False
    for i in range(s, len(code)):
        if code[i] == "{": bc += 1; found = True
        elif code[i] == "}":
            bc -= 1
            if found and bc == 0: return s, i + 1
    return s, len(code)

path = PLANET + "/OrbitalDrawer.java"
code = rf(path)

s, e = find_method(code, "    void drawBeltBand(")
assert s >= 0, "drawBeltBand not found"
