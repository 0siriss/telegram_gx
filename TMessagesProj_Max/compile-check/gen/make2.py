import os, sys, re
sys.path.insert(0, os.path.dirname(__file__))
from stubgen import methods
from make import keep, write, ANDROID

def M(path, names): return "\n".join(keep(methods(path, names)))

UI = ANDROID + ["androidx.recyclerview.widget.RecyclerView"]

write("org.telegram.ui.ActionBar","ActionBar",
"""public class ActionBar extends FrameLayout {
    public ActionBar(Context context) { super(context); }
    public static class ActionBarMenuOnItemClick {
        public void onItemClick(int id) {}
        public boolean canOpenMenu() { return true; }
    }
""" + M("org/telegram/ui/ActionBar/ActionBar.java", ["getCurrentActionBarHeight","getOccupyStatusBar","setOccupyStatusBar","setTitle","setSubtitle","setBackButtonImage","setAllowOverlayTitle","setActionBarMenuOnItemClick","createMenu","setTitleColor","setSubtitleColor","setAddToContainer","setItemsColor","setItemsBackgroundColor","setCastShadows"]) + "\n}", UI)

write("org.telegram.ui.ActionBar","ActionBarMenu",
"public class ActionBarMenu extends LinearLayout {\n    public ActionBarMenu(Context context) { super(context); }\n" + M("org/telegram/ui/ActionBar/ActionBarMenu.java", ["addItem"]) + "\n}", UI)

write("org.telegram.ui.ActionBar","ActionBarMenuSubItem",
"public class ActionBarMenuSubItem extends FrameLayout {\n    public ActionBarMenuSubItem(Context context) { super(context); }\n    public void setTextAndIcon(CharSequence text, int icon) { }\n}", UI)

write("org.telegram.ui.ActionBar","ActionBarMenuItem",
"""public class ActionBarMenuItem extends FrameLayout {
    public ActionBarMenuItem(Context context) { super(context); }
    public static class ActionBarMenuItemSearchListener {
        public void onPreToggleSearch() {}
        public void onSearchExpand() {}
        public boolean canCollapseSearch() { return true; }
        public void onSearchCollapse() {}
        public void onTextChanged(EditText editText) {}
        public void onSearchPressed(EditText editText) {}
    }
""" + M("org/telegram/ui/ActionBar/ActionBarMenuItem.java", ["addSubItem","setIsSearchField","setSearchFieldHint","setActionBarMenuItemSearchListener","openSearch"]) + "\n}", UI)

write("org.telegram.ui.ActionBar","AlertDialog",
"""public class AlertDialog extends Dialog {
    public AlertDialog(Context context, int progressStyle) { super(context); }
    public interface OnButtonClickListener { void onClick(AlertDialog dialog, int which); }
    public static class Builder {
        public Builder(Context context) {}
        public AlertDialog create() { return null; }
        public AlertDialog show() { return null; }
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setMessage(CharSequence message) { return this; }
        public Builder setView(View view) { return this; }
        public Builder setView(View view, int height) { return this; }
        public Builder setPositiveButton(CharSequence text, final OnButtonClickListener listener) { return this; }
        public Builder setNegativeButton(CharSequence text, final OnButtonClickListener listener) { return this; }
        public Builder setNeutralButton(CharSequence text, final OnButtonClickListener listener) { return this; }
        public Builder setItems(CharSequence[] items, final android.content.DialogInterface.OnClickListener onClickListener) { return this; }
    }
}""", UI)

write("org.telegram.ui.ActionBar","BaseFragment",
"""public abstract class BaseFragment {
    public View fragmentView;
    protected ActionBar actionBar;
    protected boolean isPaused = true;
    protected View createView(Context context) { return null; }
""" + M("org/telegram/ui/ActionBar/BaseFragment.java", ["onFragmentCreate","onFragmentDestroy","onResume","onPause","isPaused","presentFragment","finishFragment","getParentActivity","showDialog","getActionBar","getContext","startActivityForResult","onActivityResultFragment","onRequestPermissionsResultFragment","isLightStatusBar","onBackPressed"]) + "\n}", UI)

# --- vendored RecyclerView: только то, что реально используется, с настоящими дженериками ---
write("androidx.recyclerview.widget","RecyclerView",
"""public class RecyclerView extends ViewGroup {
    public RecyclerView(Context context) { super(context); }
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    public void setLayoutManager(LayoutManager layout) {}
    public LayoutManager getLayoutManager() { return null; }
    public void setAdapter(Adapter adapter) {}
    public Adapter getAdapter() { return null; }
    public void addOnScrollListener(OnScrollListener listener) {}
    public void scrollToPosition(int position) {}
    public void smoothScrollToPosition(int position) {}
    public void setClipToPadding(boolean clipToPadding) {}
    public void addOnItemTouchListener(OnItemTouchListener listener) {}
    public View findChildViewUnder(float x, float y) { return null; }
    public int getChildAdapterPosition(View child) { return 0; }
    public interface OnItemTouchListener {
        boolean onInterceptTouchEvent(RecyclerView rv, android.view.MotionEvent e);
        void onTouchEvent(RecyclerView rv, android.view.MotionEvent e);
        void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept);
    }
    public static abstract class LayoutManager {}
    public abstract static class OnScrollListener {
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {}
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {}
    }
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public LayoutParams(int width, int height) { super(width, height); }
    }
    public abstract static class ViewHolder {
        public final View itemView;
        public ViewHolder(View itemView) { this.itemView = itemView; }
        public final int getItemViewType() { return 0; }
        public final int getAdapterPosition() { return 0; }
    }
    public abstract static class Adapter<VH extends ViewHolder> {
        public abstract VH onCreateViewHolder(ViewGroup parent, int viewType);
        public abstract void onBindViewHolder(VH holder, int position);
        public abstract int getItemCount();
        public int getItemViewType(int position) { return 0; }
        public long getItemId(int position) { return 0; }
        public final void notifyDataSetChanged() {}
        public final void notifyItemChanged(int position) {}
        public final void notifyItemInserted(int position) {}
        public final void notifyItemRemoved(int position) {}
        public final void notifyItemRangeInserted(int positionStart, int itemCount) {}
    }
}""", ANDROID)

write("androidx.recyclerview.widget","LinearLayoutManager",
"""public class LinearLayoutManager extends RecyclerView.LayoutManager {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;
    public LinearLayoutManager(Context context) {}
    public LinearLayoutManager(Context context, int orientation, boolean reverseLayout) {}
    public void setStackFromEnd(boolean stackFromEnd) {}
    public boolean getStackFromEnd() { return false; }
    public void setReverseLayout(boolean reverseLayout) {}
    public int findFirstVisibleItemPosition() { return 0; }
    public int findLastVisibleItemPosition() { return 0; }
    public void scrollToPositionWithOffset(int position, int offset) {}
}""", ANDROID)

write("org.telegram.ui.Components","RecyclerListView",
"""public class RecyclerListView extends RecyclerView {
    public RecyclerListView(Context context) { super(context); }
    public interface OnItemClickListener { void onItemClick(View view, int position); }
    public interface OnItemClickListenerExtended { void onItemClick(View view, int position, float x, float y); }
    public interface OnItemLongClickListener { boolean onItemClick(View view, int position); }
    public interface OnItemLongClickListenerExtended { boolean onItemClick(View view, int position, float x, float y); }
    public static class Holder extends RecyclerView.ViewHolder {
        public Holder(View itemView) { super(itemView); }
    }
    public abstract static class SelectionAdapter extends RecyclerView.Adapter {
        public abstract boolean isEnabled(RecyclerView.ViewHolder holder);
    }
    public void setOnItemClickListener(OnItemClickListener listener) {}
    public void setOnItemClickListener(OnItemClickListenerExtended listener) {}
    public void setOnItemLongClickListener(OnItemLongClickListener listener) {}
    public void setOnItemLongClickListener(OnItemLongClickListener listener, long duration) {}
    public void setOnItemLongClickListener(OnItemLongClickListenerExtended listener) {}
    public void setOnItemLongClickListener(OnItemLongClickListenerExtended listener, long duration) {}
    public void cancelClickRunnables(boolean uncheck) {}
    public void invalidateViews() {}
}""", UI)

write("org.telegram.ui.Components","BackupImageView",
"public class BackupImageView extends View {\n    public BackupImageView(Context context) { super(context); }\n" + M("org/telegram/ui/Components/BackupImageView.java", ["setRoundRadius","setImageBitmap","setImageDrawable","setImageResource"]) + "\n}", UI)

write("org.telegram.ui.Components","AvatarDrawable",
"""public class AvatarDrawable extends Drawable {
    public AvatarDrawable() {}
    public void draw(Canvas c) {}
    public void setAlpha(int a) {}
    public void setColorFilter(ColorFilter f) {}
    public int getOpacity() { return 0; }
""" + M("org/telegram/ui/Components/AvatarDrawable.java", ["setInfo","setTextSize","setAvatarType"]) + "\n}", UI)

write("org.telegram.ui.Components","SizeNotifierFrameLayout",
"""public class SizeNotifierFrameLayout extends FrameLayout {
    public SizeNotifierFrameLayout(Context context) { super(context); }
    protected boolean isActionBarVisible() { return true; }
""" + M("org/telegram/ui/Components/SizeNotifierFrameLayout.java", ["setBackgroundImage","setOccupyStatusBar"]) + "\n}", UI)


# --- оформление сообщений: пузыри, эмодзи, часики ---
write("org.telegram.ui.ActionBar","MessageDrawable",
"""public class MessageDrawable extends Drawable {
    public static final int TYPE_TEXT = 0;
    public static final int TYPE_MEDIA = 1;
    public static final int TYPE_PREVIEW = 2;
    public MessageDrawable(int type, boolean out, boolean selected) {}
    public void setAlpha(int a) {}
    public void setColorFilter(ColorFilter f) {}
    public int getOpacity() { return 0; }
    public org.telegram.ui.Components.MotionBackgroundDrawable getMotionBackgroundDrawable() { return null; }
""" + M("org/telegram/ui/ActionBar/MessageDrawable.java", ["setTop","draw"]) + "\n}", UI)

write("org.telegram.ui.Components","MotionBackgroundDrawable",
"""public class MotionBackgroundDrawable extends Drawable {
    public float getPosAnimationProgress() { return 1f; }
    public void switchToNextPosition() {}
    public void draw(Canvas c) {}
    public void setAlpha(int a) {}
    public void setColorFilter(ColorFilter f) {}
    public int getOpacity() { return 0; }
}""", UI)



write("org.telegram.ui.Components","MsgClockDrawable",
"""public class MsgClockDrawable extends Drawable {
    public MsgClockDrawable() {}
    public void draw(Canvas c) {}
    public void setAlpha(int a) {}
    public void setColorFilter(ColorFilter f) {}
    public int getOpacity() { return 0; }
""" + M("org/telegram/ui/Components/MsgClockDrawable.java", ["setColor"]) + "\n}", UI)

# parseEmojis возвращает ArrayList<EmojiSpanRange>; модулю нужен только размер.
write("org.telegram.messenger","Emoji",
"""public class Emoji {
    public static class EmojiSpanRange {}
    public static java.util.ArrayList<EmojiSpanRange> parseEmojis(CharSequence cs) { return null; }
    public static CharSequence replaceEmoji(CharSequence cs, Paint.FontMetricsInt fontMetrics, boolean createNew) { return null; }
    public static boolean fullyConsistsOfEmojis(CharSequence cs) { return false; }
}""", UI)

write("org.telegram.messenger","SharedConfig","public class SharedConfig {\n    public static int fontSize = 16;\n}", UI)
print("ok")

write("org.telegram.messenger","MediaController",
"""public class MediaController {
""" + M("org/telegram/messenger/MediaController.java", ["getInstance"]) + "\n}", UI)


# Сверено с DialogsActivity.java: MAIN_TABS_HEIGHT 56 + MAIN_TABS_MARGIN 8 * 2.
write("org.telegram.ui","DialogsActivity","""public class DialogsActivity {
    public static final int MAIN_TABS_HEIGHT = 56;
    public static final int MAIN_TABS_MARGIN = 8;
    public static final int MAIN_TABS_HEIGHT_WITH_MARGINS = MAIN_TABS_HEIGHT + MAIN_TABS_MARGIN * 2;
}""", UI)

# Вручную, сверено с FragmentFloatingButton.java: конструкторы с @NonNull и setImageResource с @DrawableRes фильтр не пропускает.
write("org.telegram.ui.Components","FragmentFloatingButton",
"""public class FragmentFloatingButton extends FrameLayout {
    public FragmentFloatingButton(Context context, Theme.ResourcesProvider resourcesProvider) { super(context); }
    public FragmentFloatingButton(Context context, Theme.ResourcesProvider resourcesProvider, boolean isSubButton) { super(context); }
    public void setImageResource(int drawable) { }
    public void setButtonVisible(boolean visible, boolean animated) { }
    public static FrameLayout.LayoutParams createDefaultLayoutParams() { return null; }
}""", UI + ["org.telegram.ui.ActionBar.Theme"])

# Вручную, сверено с ItemOptions.java (makeOptions с @NonNull фильтр не пропускает).
write("org.telegram.ui.Components","ItemOptions",
"""public class ItemOptions {
    public static ItemOptions makeOptions(org.telegram.ui.ActionBar.BaseFragment fragment, View scrimView) { return null; }
    public ItemOptions add(int iconResId, CharSequence text, Runnable onClickListener) { return this; }
    public ItemOptions add(int iconResId, CharSequence text, boolean isRed, Runnable onClickListener) { return this; }
    public ItemOptions addIf(boolean condition, int iconResId, CharSequence text, Runnable onClickListener) { return this; }
    public ItemOptions addIf(boolean condition, int iconResId, CharSequence text, boolean isRed, Runnable onClickListener) { return this; }
    public ItemOptions addView(View view) { return this; }
    public ItemOptions addGap() { return this; }
    public ItemOptions setGravity(int gravity) { return this; }
    public ItemOptions show() { return this; }
    public void dismiss() { }
}""", UI)

# Стекло Telegram 13 — вручную, сверено с blur3/* (абстрактные классы и интерфейсы).
B3 = "org.telegram.ui.Components.blur3"
write(B3 + ".source","BlurredBackgroundSource","public interface BlurredBackgroundSource {}", UI)
write(B3 + ".source","BlurredBackgroundSourceColor","public class BlurredBackgroundSourceColor implements BlurredBackgroundSource {\n    public void setColor(int color) { }\n}", UI)
write(B3 + ".source","BlurredBackgroundSourceBitmap",
"""public class BlurredBackgroundSourceBitmap implements BlurredBackgroundSource {
    public void setBitmap(Bitmap bitmap) { }
    public Bitmap getBitmap() { return null; }
    public void setParentSize(int width, int height, int actionBarHeight) { }
}""", UI)
write(B3 + ".source","BlurredBackgroundSourceWrapped",
"""public class BlurredBackgroundSourceWrapped implements BlurredBackgroundSource {
    public BlurredBackgroundSource getSource() { return null; }
    public void setSource(BlurredBackgroundSource source) { }
}""", UI)
write("org.telegram.ui.Components.chat","ViewPositionWatcher",
"""public class ViewPositionWatcher {
    public ViewPositionWatcher(View anchorView) { }
    public void shutdown() { }
}""", UI)
write("org.telegram.ui.Components.chat","WallpaperBitmapProvider",
"""public class WallpaperBitmapProvider {
    public org.telegram.ui.Components.blur3.source.BlurredBackgroundSource updateSourceFromBackgroundViewDrawable(Drawable drawable) { return null; }
}""", UI)
write(B3 + ".drawable.color","BlurredBackgroundColorProvider","public interface BlurredBackgroundColorProvider {}", UI)
write(B3 + ".drawable.color","BlurredBackgroundProvider","public interface BlurredBackgroundProvider extends BlurredBackgroundColorProvider {}", UI)
write(B3 + ".drawable","BlurredBackgroundDrawable",
"""public abstract class BlurredBackgroundDrawable extends Drawable {
    public BlurredBackgroundDrawable setRadius(float radius) { return this; }
    public BlurredBackgroundDrawable setPadding(int padding) { return this; }
    public BlurredBackgroundDrawable setColorProvider(org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider p) { return this; }
}""", UI)
write(B3 + ".drawable.color.impl","BlurredBackgroundProviderImpl",
"""public class BlurredBackgroundProviderImpl {
    public static org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider topPanel(org.telegram.ui.ActionBar.Theme.ResourcesProvider r) { return null; }
    public static org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider topPanelChatActivity(org.telegram.ui.ActionBar.Theme.ResourcesProvider r) { return null; }
    public static org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider bottomPanelChatActivity(org.telegram.ui.ActionBar.Theme.ResourcesProvider r) { return null; }
}""", UI)
write(B3,"BlurredBackgroundDrawableViewFactory",
"""public class BlurredBackgroundDrawableViewFactory {
    public BlurredBackgroundDrawableViewFactory(org.telegram.ui.Components.blur3.source.BlurredBackgroundSource source) { }
    public void setSourceRootView(org.telegram.ui.Components.chat.ViewPositionWatcher watcher, ViewGroup parent) { }
    public org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable create(View view, org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider provider) { return null; }
}""", UI)

# ActionBar.setupGlass и ActionBarMenuItem.openSearch — сверено с исходником.
p_ab = os.path.join(os.path.dirname(__file__), "..", "build", "stubs", "org", "telegram", "ui", "ActionBar", "ActionBar.java")
src = open(p_ab).read().rstrip().rstrip("}")
src += "    public void setupGlass(org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory factory, org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider colorProvider) { }\n}\n"
open(p_ab, "w").write(src)

write("org.telegram.ui.Components","EditTextBoldCursor","public class EditTextBoldCursor extends EditText {\n    public EditTextBoldCursor(Context context) { super(context); }\n}", UI)
write("org.telegram.ui.Components","FragmentSearchField",
"""public class FragmentSearchField extends FrameLayout {
    public final EditTextBoldCursor editText = null;
    public FragmentSearchField(Context context, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) { super(context); }
    public void setHintText(CharSequence text, boolean animated) { }
    public void setSectionBackground() { }
    public void setupBlurredBackground(org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable drawable) { }
    public void setCloseButtonOnClickListener(Runnable onCloseSearch) { }
    public void setCloseButtonVisible(boolean visible) { }
}""", UI)
write("org.telegram.ui.Components","FilterTabsView",
"""public class FilterTabsView extends FrameLayout {
    public FilterTabsView(Context context, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) { super(context); }
    public interface FilterTabsViewDelegate {
        void onPageSelected(Tab tab, boolean forward);
        void onPageScrolled(float progress);
        void onSamePageSelected();
        int getTabCounter(int tabId);
        boolean didSelectTab(TabView tabView, boolean selected);
        boolean isTabMenuVisible();
        void onDeletePressed(int id);
        void onPageReorder(int fromId, int toId);
        boolean canPerformActions();
    }
    public class Tab { public int id; }
    public class TabView extends View { public TabView(Context context) { super(context); } }
    public void setDelegate(FilterTabsViewDelegate filterTabsViewDelegate) { }
    public void addTab(int id, int stableId, CharSequence text, boolean noanimate, boolean isDefault, boolean isLocked) { }
    public void finishAddingTabs(boolean animated) { }
    public void selectFirstTab() { }
    public void scrollToTab(Tab tab, int position) { }
    public int getTabsCount() { return 0; }
    public Tab getTab(int i) { return null; }
    public boolean selectTabWithStableId(int stableId) { return false; }
    public int getCurrentTabId() { return 0; }
    public void setBlurredBackground(org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable drawable) { }
    public void notifyTabCounterChanged(int id) { }
    public void removeTabs() { }
}""", UI)

# Ячейки Telegram для профиля и панель эмодзи — вручную, сверено с Cells/*.java и EmojiView.java.
write("org.telegram.ui.Cells","TextDetailCell",
"""public class TextDetailCell extends FrameLayout {
    public TextDetailCell(Context context) { super(context); }
    public TextDetailCell(Context context, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, boolean textMultiline, boolean valueMultiline) { super(context); }
    public void setTextAndValue(CharSequence text, CharSequence value, boolean divider) { }
}""", UI)
write("org.telegram.ui.Cells","HeaderCell",
"""public class HeaderCell extends FrameLayout {
    public HeaderCell(Context context) { super(context); }
    public void setText(CharSequence text) { }
}""", UI)
write("org.telegram.ui.Cells","TextCell",
"""public class TextCell extends FrameLayout {
    public TextCell(Context context) { super(context); }
    public void setTextAndIcon(CharSequence text, int resId, boolean divider) { }
    public void setColors(int icon, int text) { }
}""", UI)
write("org.telegram.ui.Cells","ShadowSectionCell",
"""public class ShadowSectionCell extends View {
    public ShadowSectionCell(Context context) { super(context); }
}""", UI)
write("org.telegram.tgnet","TLRPC","public class TLRPC {\n    public static class ChatFull {}\n}")
write("org.telegram.ui.Components","EmojiView",
"""public class EmojiView extends FrameLayout {
    public EmojiView(org.telegram.ui.ActionBar.BaseFragment fragment, boolean needAnimatedEmoji, boolean needStickers, boolean needGif, final Context context, boolean needSearch, final org.telegram.tgnet.TLRPC.ChatFull chatFull, ViewGroup parentView, boolean shouldDrawBackground, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, boolean frozenAtStart) { super(context); }
    public interface EmojiViewDelegate {
        default boolean onBackspace() { return false; }
        default void onEmojiSelected(String emoji) { }
    }
    public void setDelegate(EmojiViewDelegate emojiViewDelegate) { }
}""", UI)

write("org.telegram.ui.Cells","GraySectionCell",
"""public class GraySectionCell extends FrameLayout {
    public GraySectionCell(Context context) { super(context); }
    public void setText(CharSequence text) { }
}""", UI)
write("org.telegram.ui.Cells","TextInfoPrivacyCell",
"""public class TextInfoPrivacyCell extends FrameLayout {
    public TextInfoPrivacyCell(Context context) { super(context); }
    public void setText(CharSequence text) { }
}""", UI)
