import re, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from stubgen import methods, int_fields, SRC, REPO
OUT = os.path.join(os.path.dirname(__file__), "..", "build", "stubs")

KNOWN = set("""int long float double boolean void byte short char String CharSequence Object Runnable Context View
ViewGroup Drawable Bitmap Canvas Paint Typeface Point EditText TextView FrameLayout LinearLayout Activity Dialog
ShapeDrawable CombinedDrawable Integer Long Boolean Float LayoutParams ActionBarMenu ActionBarMenuItem ActionBarMenuSubItem
ActionBarMenuOnItemClick ActionBarMenuItemSearchListener BaseFragment ActionBar Builder OnButtonClickListener AlertDialog
OnItemClickListener OnItemLongClickListener OnItemClickListenerExtended OnItemLongClickListenerExtended FastDateFormat
LocaleController Theme RecyclerView Adapter ViewHolder Throwable Exception Intent MediaController""".split())

def ok(line):
    sig = line.split("{")[0]
    sig = re.sub(r'"[^"]*"', '', sig)
    for t in re.findall(r'\b([A-Z]\w*)\b', sig):
        if t not in KNOWN: return False
    # аннотации и дженерики, которые мы не поддерживаем
    if "@" in sig or "<" in sig: return False
    return True

def keep(lines): return [l for l in lines if ok(l)]

def write(pkg, name, body, imports=()):
    d = os.path.join(OUT, *pkg.split("."))
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, name + ".java"), "w") as f:
        f.write("package %s;\n\n" % pkg)
        for i in imports: f.write("import %s;\n" % i)
        f.write("\n" + body + "\n")

ANDROID = ["android.content.Context","android.view.View","android.view.ViewGroup","android.graphics.drawable.Drawable",
           "android.graphics.Bitmap","android.graphics.Canvas","android.graphics.Paint","android.graphics.Typeface",
           "android.graphics.Point","android.widget.EditText","android.widget.TextView","android.widget.FrameLayout",
           "android.widget.LinearLayout","android.app.Activity","android.app.Dialog","android.graphics.drawable.ShapeDrawable",
           "android.graphics.ColorFilter","android.content.Intent"]

# --- messenger ---
au = keep(methods("org/telegram/messenger/AndroidUtilities.java", ["dp","bold","runOnUIThread","cancelRunOnUIThread","addToClipboard","showKeyboard","hideKeyboard"]))
write("org.telegram.messenger","AndroidUtilities","public class AndroidUtilities {\n    public static int statusBarHeight;\n    public static int navigationBarHeight;\n    public static float density;\n    public static Point displaySize = new Point();\n"+"\n".join(au)+"\n}", ANDROID)
fl = keep(methods("org/telegram/messenger/FileLog.java", ["d","e"]))
write("org.telegram.messenger","FileLog","public class FileLog {\n"+"\n".join(fl)+"\n}", ANDROID)
write("org.telegram.messenger","ApplicationLoader","public class ApplicationLoader {\n    public static volatile Context applicationContext;\n}", ANDROID)
lc = keep(methods("org/telegram/messenger/LocaleController.java", ["getInstance","formatDateChat","stringForMessageListDate","getFormatterDay","formatterDay"]))
write("org.telegram.messenger","LocaleController","public class LocaleController {\n"+"\n".join(lc)+"\n}", ANDROID+["org.telegram.messenger.time.FastDateFormat"])
fd = keep(methods("org/telegram/messenger/time/FastDateFormat.java", ["format"]))
write("org.telegram.messenger.time","FastDateFormat","public class FastDateFormat {\n"+"\n".join(fd)+"\n}", ANDROID)
write("org.telegram.messenger","MessagesController","public class MessagesController {\n    public static android.content.SharedPreferences getGlobalMainSettings() { return null; }\n}")
write("org.telegram.messenger","MaxSection", open(SRC+"org/telegram/messenger/MaxSection.java").read().split("package org.telegram.messenger;",1)[1].replace("import org.telegram.ui.ActionBar.BaseFragment;",""), ["org.telegram.ui.ActionBar.BaseFragment"])

# R.drawable из реального использования в модуле
used = set()
for root,_,files in os.walk(os.path.join(REPO, "TMessagesProj_Max/src/main/kotlin")):
    for f in files:
        used |= set(re.findall(r'R\.drawable\.(\w+)', open(os.path.join(root,f)).read()))
res = set(os.path.splitext(f)[0] for d in os.listdir(os.path.join(REPO, "TMessagesProj/src/main/res")) if d.startswith("drawable") for f in os.listdir(os.path.join(REPO, "TMessagesProj/src/main/res", d)))
fields = "\n".join("        public static final int %s = %d;" % (n,i+1) for i,n in enumerate(sorted(used & res)))
missing = sorted(used - res)
if missing: print("НЕТ ТАКИХ РЕСУРСОВ:", missing)
write("org.telegram.messenger","R","public final class R {\n    public static final class drawable {\n"+fields+"\n    }\n}")

# --- Theme ---
th = keep(methods("org/telegram/ui/ActionBar/Theme.java", ["getColor","createRoundRectDrawable","createSelectorDrawable","createSimpleSelectorRoundRectDrawable","createCircleDrawableWithIcon","getCachedWallpaper","isWallpaperMotion"]))
keys = int_fields("org/telegram/ui/ActionBar/Theme.java", r'key_\w+')
# Вложенные вручную: сверено с Theme.java (ResourcesProvider — интерфейс, AdaptiveRipple.filledRectByKey(int, float...)).
theme_nested = "    public interface ResourcesProvider {}\n    public static class AdaptiveRipple {\n        public static Drawable filledRectByKey(int backgroundColorKey, float... radii) { return null; }\n    }\n"
write("org.telegram.ui.ActionBar","Theme","public class Theme {\n"+theme_nested+"\n".join(keys)+"\n"+"\n".join(th)+"\n}", ANDROID+["org.telegram.ui.Components.CombinedDrawable"])
write("org.telegram.ui.Components","CombinedDrawable","public class CombinedDrawable extends Drawable {\n    public void draw(Canvas c) {}\n    public void setAlpha(int a) {}\n    public void setColorFilter(ColorFilter f) {}\n    public int getOpacity() { return 0; }\n}", ANDROID)

# --- LayoutHelper ---
lh = keep(methods("org/telegram/ui/Components/LayoutHelper.java", ["createFrame","createLinear"]))
write("org.telegram.ui.Components","LayoutHelper","public class LayoutHelper {\n    public static final int MATCH_PARENT = -1;\n    public static final int WRAP_CONTENT = -2;\n"+"\n".join(lh)+"\n}", ANDROID)
print("ok", len(au), len(fl), len(lc), len(th), len(keys), len(lh))
