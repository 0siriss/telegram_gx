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
""" + M("org/telegram/ui/ActionBar/ActionBar.java", ["getCurrentActionBarHeight","getOccupyStatusBar","setOccupyStatusBar","setTitle","setSubtitle","setBackButtonImage","setAllowOverlayTitle","setActionBarMenuOnItemClick","createMenu","setTitleColor","setSubtitleColor"]) + "\n}", UI)

write("org.telegram.ui.ActionBar","ActionBarMenu",
"public class ActionBarMenu extends LinearLayout {\n    public ActionBarMenu(Context context) { super(context); }\n" + M("org/telegram/ui/ActionBar/ActionBarMenu.java", ["addItem"]) + "\n}", UI)

write("org.telegram.ui.ActionBar","ActionBarMenuSubItem",
"public class ActionBarMenuSubItem extends FrameLayout {\n    public ActionBarMenuSubItem(Context context) { super(context); }\n}", UI)

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
""" + M("org/telegram/ui/ActionBar/ActionBarMenuItem.java", ["addSubItem","setIsSearchField","setSearchFieldHint","setActionBarMenuItemSearchListener"]) + "\n}", UI)

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
""" + M("org/telegram/ui/ActionBar/BaseFragment.java", ["onFragmentCreate","onFragmentDestroy","onResume","onPause","isPaused","presentFragment","finishFragment","getParentActivity","showDialog","getActionBar","getContext","startActivityForResult","onActivityResultFragment"]) + "\n}", UI)

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
print("ok")
