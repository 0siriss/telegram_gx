/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QRCodeBottomSheet;
import org.telegram.ui.Components.SectionsScrollView;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

public class ProxySettingsActivity extends BaseFragment {

    private final static int TYPE_SOCKS5 = 0;
    private final static int TYPE_MTPROTO = 1;

    private static int parseEchExtensionId(String text) {
        if (text == null) {
            return DEFAULT_ECH_EXTENSION_ID;
        }
        text = text.trim();
        if (!text.matches("(?i)[0-9a-f]{1,4}")) {
            return DEFAULT_ECH_EXTENSION_ID;
        }
        try {
            return Integer.parseInt(text, 16) & 0xffff;
        } catch (NumberFormatException e) {
            return DEFAULT_ECH_EXTENSION_ID;
        }
    }

    private final static int FIELD_IP = 0;
    private final static int FIELD_PORT = 1;
    private final static int FIELD_USER = 2;
    private final static int FIELD_PASSWORD = 3;
    private final static int FIELD_SECRET = 4;

    private EditTextBoldCursor[] inputFields;
    private ScrollView scrollView;
    private LinearLayout linearLayout2;
    private LinearLayout inputFieldsContainer;
    private HeaderCell headerCell;
    private ShadowSectionCell[] sectionCell = new ShadowSectionCell[3];
    private TextInfoPrivacyCell[] bottomCells = new TextInfoPrivacyCell[2];
    private TextSettingsCell shareCell;
    private TextSettingsCell pasteCell;
    private ActionBarMenuItem doneItem;
    private RadioCell[] typeCell = new RadioCell[2];
    private int currentType = -1;

    private int pasteType = -1;
    private String pasteString;
    private String[] pasteFields;

    private float shareDoneProgress = 1f;
    private float[] shareDoneProgressAnimValues = new float[2];
    private boolean shareDoneEnabled = true;
    private ValueAnimator shareDoneAnimator;

    private ClipboardManager clipboardManager;

    // TLS Fragmentation UI
    private LinearLayout fragmentContainer;
    private android.widget.Switch fragmentSwitch;
    private EditTextBoldCursor fragmentMinField;
    private EditTextBoldCursor fragmentMaxField;

    // TLS Fingerprint profile UI
    private TextSettingsCell fingerprintProfileCell;
    private int currentFingerprintProfile = 0;
    private int currentRotationInterval = 0;
    private FrameLayout rotationIntervalContainer;
    private EditTextBoldCursor rotationIntervalField;
    private static final String[] FINGERPRINT_PROFILE_NAMES = {
        "Chrome 120+ (default)", "Firefox 121+", "Safari 17", "Edge 120+", "Random"
    };

    // TLS ECH extension ID UI
    private TextSettingsCell echIdCell;
    private int currentEchId = DEFAULT_ECH_EXTENSION_ID;
    private static final int DEFAULT_ECH_EXTENSION_ID = 0xfe0d;
    private static final int[] ECH_ID_PRESET_VALUES = {0xfe0d, 0xfe02};
    private static final String[] ECH_ID_PRESET_NAMES = {"fe0d (default)", "fe02 (legacy)", "Custom…"};


    private boolean addingNewProxy;

    private SharedConfig.ProxyInfo currentProxyInfo;

    private boolean ignoreOnTextChange;

    private static final int done_button = 1;

    private void showEchCustomIdDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setSingleLine(true);
        input.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        input.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        input.setHintText("hex, e.g. fe0d");
        input.setText(String.format(Locale.US, "%04x", currentEchId));
        input.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(6), AndroidUtilities.dp(21), 0);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.CENTER_VERTICAL, 21, 6, 21, 0));

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        builder.setTitle("Custom ECH Extension ID");
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            currentEchId = parseEchExtensionId(input.getText().toString());
            echIdCell.setTextAndValue("ECH extension ID", String.format(Locale.US, "%04x", currentEchId), false);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    public static class TypeCell extends FrameLayout {

        private TextView textView;
        private ImageView checkImage;
        private boolean needDivider;

        public TypeCell(Context context) {
            super(context);

            setWillNotDraw(false);

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 23 + 48 : 21, 0, LocaleController.isRTL ? 21 : 23, 0));

            checkImage = new ImageView(context);
            checkImage.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addedIcon), PorterDuff.Mode.MULTIPLY));
            checkImage.setImageResource(R.drawable.sticker_added);
            addView(checkImage, LayoutHelper.createFrame(19, 14, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
        }

        public void setValue(String name, boolean checked, boolean divider) {
            textView.setText(name);
            checkImage.setVisibility(checked ? VISIBLE : INVISIBLE);
            needDivider = divider;
        }

        public void setTypeChecked(boolean value) {
            checkImage.setVisibility(value ? VISIBLE : INVISIBLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    public ProxySettingsActivity() {
        super();
        currentProxyInfo = new SharedConfig.ProxyInfo("", 1080, "", "", "");
        addingNewProxy = true;
    }

    public ProxySettingsActivity(SharedConfig.ProxyInfo proxyInfo) {
        super();
        currentProxyInfo = proxyInfo;
    }

    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = this::updatePasteCell;

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        updatePasteCell();
    }

    @Override
    public void onPause() {
        super.onPause();
        clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.ProxyDetails));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    if (getParentActivity() == null) {
                        return;
                    }
                    currentProxyInfo.address = inputFields[FIELD_IP].getText().toString();
                    currentProxyInfo.port = Utilities.parseInt(inputFields[FIELD_PORT].getText().toString());
                    if (currentType == 0) {
                        currentProxyInfo.secret = "";
                        currentProxyInfo.username = inputFields[FIELD_USER].getText().toString();
                        currentProxyInfo.password = inputFields[FIELD_PASSWORD].getText().toString();
                    } else {
                        currentProxyInfo.secret = inputFields[FIELD_SECRET].getText().toString();
                        currentProxyInfo.username = "";
                        currentProxyInfo.password = "";
                    }

                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                    SharedPreferences.Editor editor = preferences.edit();
                    boolean enabled;
                    if (addingNewProxy) {
                        SharedConfig.addProxy(currentProxyInfo);
                        SharedConfig.currentProxy = currentProxyInfo;
                        editor.putBoolean("proxy_enabled", true);
                        enabled = true;
                    } else {
                        enabled = preferences.getBoolean("proxy_enabled", false);
                        SharedConfig.saveProxyList();
                    }
                    if (addingNewProxy || SharedConfig.currentProxy == currentProxyInfo) {
                        editor.putString("proxy_ip", currentProxyInfo.address);
                        editor.putString("proxy_pass", currentProxyInfo.password);
                        editor.putString("proxy_user", currentProxyInfo.username);
                        editor.putInt("proxy_port", currentProxyInfo.port);
                        editor.putString("proxy_secret", currentProxyInfo.secret);
                        ConnectionsManager.setProxySettings(enabled, currentProxyInfo.address, currentProxyInfo.port, currentProxyInfo.username, currentProxyInfo.password, currentProxyInfo.secret);
                    }
                    // Save and apply TLS fingerprint profile
                    if (rotationIntervalField != null) {
                        currentRotationInterval = Utilities.parseInt(rotationIntervalField.getText().toString());
                        if (currentRotationInterval < 30 && currentRotationInterval != 0) currentRotationInterval = 30;
                        if (currentRotationInterval > 240) currentRotationInterval = 240;
                    }
                    editor.putInt("tls_fingerprint_profile", currentFingerprintProfile);
                    editor.putInt("tls_rotation_interval", currentRotationInterval);
                    ConnectionsManager.setTlsFingerprintProfile(currentFingerprintProfile, currentRotationInterval);
                    // Save and apply ECH extension ID
                    editor.putInt("tls_ech_extension_id", currentEchId);
                    ConnectionsManager.setTlsEchExtensionId(currentEchId);
                    // Save and apply TLS fragment settings
                    if (fragmentContainer != null) {
                        boolean fragEnabled = fragmentSwitch.isChecked();
                        int fragMin = Utilities.parseInt(fragmentMinField.getText().toString());
                        int fragMax = Utilities.parseInt(fragmentMaxField.getText().toString());
                        if (fragMin < 1) fragMin = 1;
                        if (fragMax < fragMin) fragMax = fragMin;
                        editor.putBoolean("tls_fragment_enabled", fragEnabled);
                        editor.putInt("tls_fragment_min", fragMin);
                        editor.putInt("tls_fragment_max", fragMax);
                        ConnectionsManager.setTlsFragmentConfig(fragEnabled, fragMin, fragMax);
                    }
                    editor.commit();

                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

                    finishFragment();
                }
            }
        });

        doneItem = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(LocaleController.getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

//        linearLayout2 = new SectionsScrollView.SectionsLinearLayout(context);
//        scrollView = new SectionsScrollView(context, linearLayout2, resourceProvider);
        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        linearLayout2 = new LinearLayout(context);
        linearLayout2.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout2, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final View.OnClickListener typeCellClickListener = view -> setProxyType((Integer) view.getTag(), true);

        for (int a = 0; a < 2; a++) {
            typeCell[a] = new RadioCell(context);
            typeCell[a].setBackground(Theme.getSelectorDrawable(true));
            typeCell[a].setTag(a);
            if (a == 0) {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxySocks5), a == currentType, true);
            } else {
                typeCell[a].setText(LocaleController.getString(R.string.UseProxyTelegram), a == currentType, false);
            }
            linearLayout2.addView(typeCell[a], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
            typeCell[a].setOnClickListener(typeCellClickListener);
        }

        sectionCell[0] = new ShadowSectionCell(context);
        linearLayout2.addView(sectionCell[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFieldsContainer = new LinearLayout(context);
        inputFieldsContainer.setOrientation(LinearLayout.VERTICAL);
        inputFieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // bring to front for transitions
            inputFieldsContainer.setElevation(AndroidUtilities.dp(1f));
            inputFieldsContainer.setOutlineProvider(null);
        }
        linearLayout2.addView(inputFieldsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        inputFields = new EditTextBoldCursor[5];
        for (int a = 0; a < 5; a++) {
            FrameLayout container = new FrameLayout(context);
            inputFieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

            inputFields[a] = new EditTextBoldCursor(context);
            inputFields[a].setTag(a);
            inputFields[a].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            inputFields[a].setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            inputFields[a].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setBackground(null);
            inputFields[a].setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            inputFields[a].setCursorSize(AndroidUtilities.dp(20));
            inputFields[a].setCursorWidth(1.5f);
            inputFields[a].setSingleLine(true);
            inputFields[a].setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            inputFields[a].setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            inputFields[a].setTransformHintToHeader(true);
            inputFields[a].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));

            if (a == FIELD_IP) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PORT) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_NUMBER);
                inputFields[a].addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {

                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (ignoreOnTextChange) {
                            return;
                        }
                        EditText phoneField = inputFields[FIELD_PORT];
                        int start = phoneField.getSelectionStart();
                        String chars = "0123456789";
                        String str = phoneField.getText().toString();
                        StringBuilder builder = new StringBuilder(str.length());
                        for (int a = 0; a < str.length(); a++) {
                            String ch = str.substring(a, a + 1);
                            if (chars.contains(ch)) {
                                builder.append(ch);
                            }
                        }
                        ignoreOnTextChange = true;
                        boolean changed;
                        int port = Utilities.parseInt(builder.toString());
                        if (port < 0 || port > 65535 || !str.equals(builder.toString())) {
                            if (port < 0) {
                                phoneField.setText("0");
                            } else if (port > 65535) {
                                phoneField.setText("65535");
                            } else {
                                phoneField.setText(builder.toString());
                            }
                        } else {
                            if (start >= 0) {
                                phoneField.setSelection(Math.min(start, phoneField.length()));
                            }
                        }
                        ignoreOnTextChange = false;
                        checkShareDone(true);
                    }
                });
            } else if (a == FIELD_PASSWORD) {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                inputFields[a].setTypeface(Typeface.DEFAULT);
                inputFields[a].setTransformationMethod(PasswordTransformationMethod.getInstance());
            } else {
                inputFields[a].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            inputFields[a].setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            switch (a) {
                case FIELD_IP:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyAddress));
                    inputFields[a].setText(currentProxyInfo.address);
                    break;
                case FIELD_PASSWORD:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPassword));
                    inputFields[a].setText(currentProxyInfo.password);
                    break;
                case FIELD_PORT:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyPort));
                    inputFields[a].setText("" + currentProxyInfo.port);
                    break;
                case FIELD_USER:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxyUsername));
                    inputFields[a].setText(currentProxyInfo.username);
                    break;
                case FIELD_SECRET:
                    inputFields[a].setHintText(LocaleController.getString(R.string.UseProxySecret));
                    inputFields[a].setText(currentProxyInfo.secret);
                    break;
            }
            inputFields[a].setSelection(inputFields[a].length());

            inputFields[a].setPadding(0, 0, 0, 0);
            container.addView(inputFields[a], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 17, a == FIELD_IP ? 12 : 0, 17, 0));

            inputFields[a].setOnEditorActionListener((textView, i, keyEvent) -> {
                if (i == EditorInfo.IME_ACTION_NEXT) {
                    int num = (Integer) textView.getTag();
                    if (num + 1 < inputFields.length) {
                        num++;
                        inputFields[num].requestFocus();
                    }
                    return true;
                } else if (i == EditorInfo.IME_ACTION_DONE) {
                    finishFragment();
                    return true;
                }
                return false;
            });
        }

        for (int i = 0; i < 2; i++) {
            bottomCells[i] = new TextInfoPrivacyCell(context);
            bottomCells[i].setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
            if (i == 0) {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyInfo));
            } else {
                bottomCells[i].setText(LocaleController.getString(R.string.UseProxyTelegramInfo) + "\n\n" + LocaleController.getString(R.string.UseProxyTelegramInfo2));
                bottomCells[i].setVisibility(View.GONE);
            }
            linearLayout2.addView(bottomCells[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        // TLS Fingerprint + Fragmentation section (MTProto only)
        SharedPreferences fragPrefs = MessagesController.getGlobalMainSettings();
        boolean savedFragEnabled = fragPrefs.getBoolean("tls_fragment_enabled", false);
        int savedFragMin = fragPrefs.getInt("tls_fragment_min", 1);
        int savedFragMax = fragPrefs.getInt("tls_fragment_max", 3);
        currentFingerprintProfile = fragPrefs.getInt("tls_fingerprint_profile", 0);
        currentRotationInterval = fragPrefs.getInt("tls_rotation_interval", 0);
        currentEchId = fragPrefs.getInt("tls_ech_extension_id", DEFAULT_ECH_EXTENSION_ID);
        ConnectionsManager.setTlsFragmentConfig(savedFragEnabled, savedFragMin, savedFragMax);
        ConnectionsManager.setTlsFingerprintProfile(currentFingerprintProfile, currentRotationInterval);
        ConnectionsManager.setTlsEchExtensionId(currentEchId);

        fragmentContainer = new LinearLayout(context);
        fragmentContainer.setOrientation(LinearLayout.VERTICAL);
        fragmentContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        linearLayout2.addView(fragmentContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fragmentContainer.setVisibility(View.GONE);

        // TLS Fingerprint profile section
        HeaderCell fpHeader = new HeaderCell(context);
        fpHeader.setText("TLS Fingerprint");
        fragmentContainer.addView(fpHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fingerprintProfileCell = new TextSettingsCell(context);
        fingerprintProfileCell.setBackground(Theme.getSelectorDrawable(true));
        fingerprintProfileCell.setTextAndValue("Browser profile", FINGERPRINT_PROFILE_NAMES[currentFingerprintProfile], false);
        fingerprintProfileCell.setOnClickListener(v -> {
            if (getParentActivity() == null) return;
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getParentActivity());
            builder.setTitle("TLS Fingerprint Profile");
            builder.setSingleChoiceItems(FINGERPRINT_PROFILE_NAMES, currentFingerprintProfile, (dialog, which) -> {
                currentFingerprintProfile = which;
                fingerprintProfileCell.setTextAndValue("Browser profile", FINGERPRINT_PROFILE_NAMES[which], false);
                // show/hide rotation interval for Random profile (index 4)
                if (rotationIntervalContainer != null) {
                    rotationIntervalContainer.setVisibility(which == 4 ? View.VISIBLE : View.GONE);
                }
                dialog.dismiss();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(builder.create());
        });
        fragmentContainer.addView(fingerprintProfileCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ECH extension ID picker (same checkmark-list pattern as Browser profile, right next to it)
        echIdCell = new TextSettingsCell(context);
        echIdCell.setBackground(Theme.getSelectorDrawable(true));
        echIdCell.setTextAndValue("ECH extension ID", String.format(Locale.US, "%04x", currentEchId), false);
        echIdCell.setOnClickListener(v -> {
            if (getParentActivity() == null) return;
            int selected = ECH_ID_PRESET_NAMES.length - 1;
            for (int i = 0; i < ECH_ID_PRESET_VALUES.length; i++) {
                if (ECH_ID_PRESET_VALUES[i] == currentEchId) {
                    selected = i;
                    break;
                }
            }
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getParentActivity());
            builder.setTitle("ECH Extension ID");
            builder.setSingleChoiceItems(ECH_ID_PRESET_NAMES, selected, (dialog, which) -> {
                dialog.dismiss();
                if (which == ECH_ID_PRESET_NAMES.length - 1) {
                    showEchCustomIdDialog();
                } else {
                    currentEchId = ECH_ID_PRESET_VALUES[which];
                    echIdCell.setTextAndValue("ECH extension ID", String.format(Locale.US, "%04x", currentEchId), false);
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(builder.create());
        });
        fragmentContainer.addView(echIdCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Rotation interval field (visible only when Random profile is selected)
        rotationIntervalContainer = new FrameLayout(context);
        rotationIntervalContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        rotationIntervalField = new EditTextBoldCursor(context);
        rotationIntervalField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        rotationIntervalField.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        rotationIntervalField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        rotationIntervalField.setBackground(null);
        rotationIntervalField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        rotationIntervalField.setInputType(InputType.TYPE_CLASS_NUMBER);
        rotationIntervalField.setSingleLine(true);
        rotationIntervalField.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        rotationIntervalField.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        rotationIntervalField.setTransformHintToHeader(true);
        rotationIntervalField.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        rotationIntervalField.setHintText("Rotation interval, sec (30–240, 0=per connection)");
        rotationIntervalField.setText(currentRotationInterval > 0 ? String.valueOf(currentRotationInterval) : "");
        rotationIntervalField.setPadding(AndroidUtilities.dp(LocaleController.isRTL ? 0 : 21), 0, AndroidUtilities.dp(LocaleController.isRTL ? 21 : 0), 0);
        rotationIntervalContainer.addView(rotationIntervalField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.CENTER_VERTICAL));
        fragmentContainer.addView(rotationIntervalContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        rotationIntervalContainer.setVisibility(currentFingerprintProfile == 4 ? View.VISIBLE : View.GONE);

        // Section header
        HeaderCell fragHeader = new HeaderCell(context);
        fragHeader.setText("TLS Fragmentation");
        fragmentContainer.addView(fragHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Toggle row
        FrameLayout switchRow = new FrameLayout(context);
        switchRow.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        TextView switchLabel = new TextView(context);
        switchLabel.setText("Fragment ClientHello");
        switchLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        switchLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        switchRow.addView(switchLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 0 : 21, 0, LocaleController.isRTL ? 21 : 0, 0));
        fragmentSwitch = new android.widget.Switch(context);
        fragmentSwitch.setChecked(savedFragEnabled);
        switchRow.addView(fragmentSwitch, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 21, 0, 21, 0));
        fragmentContainer.addView(switchRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        // Min field
        FrameLayout minContainer = new FrameLayout(context);
        minContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentMinField = new EditTextBoldCursor(context);
        fragmentMinField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        fragmentMinField.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        fragmentMinField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        fragmentMinField.setBackground(null);
        fragmentMinField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        fragmentMinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        fragmentMinField.setSingleLine(true);
        fragmentMinField.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        fragmentMinField.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        fragmentMinField.setTransformHintToHeader(true);
        fragmentMinField.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        fragmentMinField.setHintText("Min bytes (default: 1)");
        fragmentMinField.setText(String.valueOf(savedFragMin));
        fragmentMinField.setPadding(AndroidUtilities.dp(LocaleController.isRTL ? 0 : 21), 0, AndroidUtilities.dp(LocaleController.isRTL ? 21 : 0), 0);
        minContainer.addView(fragmentMinField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.CENTER_VERTICAL));
        fragmentContainer.addView(minContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        // Max field
        FrameLayout maxContainer = new FrameLayout(context);
        maxContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentMaxField = new EditTextBoldCursor(context);
        fragmentMaxField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        fragmentMaxField.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        fragmentMaxField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        fragmentMaxField.setBackground(null);
        fragmentMaxField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        fragmentMaxField.setInputType(InputType.TYPE_CLASS_NUMBER);
        fragmentMaxField.setSingleLine(true);
        fragmentMaxField.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        fragmentMaxField.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        fragmentMaxField.setTransformHintToHeader(true);
        fragmentMaxField.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        fragmentMaxField.setHintText("Max bytes (default: 3)");
        fragmentMaxField.setText(String.valueOf(savedFragMax));
        fragmentMaxField.setPadding(AndroidUtilities.dp(LocaleController.isRTL ? 0 : 21), 0, AndroidUtilities.dp(LocaleController.isRTL ? 21 : 0), 0);
        maxContainer.addView(fragmentMaxField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.CENTER_VERTICAL));
        fragmentContainer.addView(maxContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        // Divider
        ShadowSectionCell fragDivider = new ShadowSectionCell(context);
        fragDivider.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        linearLayout2.addView(fragDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        pasteCell = new TextSettingsCell(fragmentView.getContext());
        pasteCell.setBackground(Theme.getSelectorDrawable(true));
        pasteCell.setText(LocaleController.getString(R.string.PasteFromClipboard), false);
        pasteCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        pasteCell.setOnClickListener(v -> {
            if (pasteType != -1) {
                for (int i = 0; i < pasteFields.length; i++) {
                    if (pasteType == TYPE_SOCKS5 && i == FIELD_SECRET) {
                        continue;
                    }
                    if (pasteType == TYPE_MTPROTO && (i == FIELD_USER || i == FIELD_PASSWORD)) {
                        continue;
                    }
                    if (pasteFields[i] != null) {
                        try {
                            inputFields[i].setText(URLDecoder.decode(pasteFields[i], "UTF-8"));
                        } catch (UnsupportedEncodingException e) {
                            inputFields[i].setText(pasteFields[i]);
                        }
                    } else {
                        inputFields[i].setText(null);
                    }
                }
                inputFields[0].setSelection(inputFields[0].length());
                setProxyType(pasteType, true, () -> {
                    AndroidUtilities.hideKeyboard(inputFieldsContainer.findFocus());
                    for (int i = 0; i < pasteFields.length; i++) {
                        if (pasteType == TYPE_SOCKS5 && i != FIELD_SECRET) {
                            continue;
                        }
                        if (pasteType == TYPE_MTPROTO && i != FIELD_USER && i != FIELD_PASSWORD) {
                            continue;
                        }
                        inputFields[i].setText(null);
                    }
                });
            }
        });
        linearLayout2.addView(pasteCell, 0, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        pasteCell.setVisibility(View.GONE);
        sectionCell[2] = new ShadowSectionCell(fragmentView.getContext());
        sectionCell[2].setBackground(Theme.getThemedDrawableByKey(fragmentView.getContext(), R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        linearLayout2.addView(sectionCell[2], 1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        sectionCell[2].setVisibility(View.GONE);

        shareCell = new TextSettingsCell(context);
        shareCell.setBackgroundDrawable(Theme.getSelectorDrawable(true));
        shareCell.setText(LocaleController.getString(R.string.ShareFile), false);
        shareCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        linearLayout2.addView(shareCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        shareCell.setOnClickListener(v -> {
            StringBuilder params = new StringBuilder();
            String address = inputFields[FIELD_IP].getText().toString();
            String password = inputFields[FIELD_PASSWORD].getText().toString();
            String user = inputFields[FIELD_USER].getText().toString();
            String port = inputFields[FIELD_PORT].getText().toString();
            String secret = inputFields[FIELD_SECRET].getText().toString();
            String url;
            try {
                if (!TextUtils.isEmpty(address)) {
                    params.append("server=").append(URLEncoder.encode(address, "UTF-8"));
                }
                if (!TextUtils.isEmpty(port)) {
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("port=").append(URLEncoder.encode(port, "UTF-8"));
                }
                if (currentType == 1) {
                    url = "https://t.me/proxy?";
                    if (params.length() != 0) {
                        params.append("&");
                    }
                    params.append("secret=").append(URLEncoder.encode(secret, "UTF-8"));
                } else {
                    url = "https://t.me/socks?";
                    if (!TextUtils.isEmpty(user)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("user=").append(URLEncoder.encode(user, "UTF-8"));
                    }
                    if (!TextUtils.isEmpty(password)) {
                        if (params.length() != 0) {
                            params.append("&");
                        }
                        params.append("pass=").append(URLEncoder.encode(password, "UTF-8"));
                    }
                }
            } catch (Exception ignore) {
                return;
            }
            if (params.length() == 0) {
                return;
            }
            String link = url + params.toString();
            QRCodeBottomSheet alert = new QRCodeBottomSheet(context, LocaleController.getString(R.string.ShareQrCode), link, LocaleController.getString(R.string.QRCodeLinkHelpProxy), true);
            Bitmap icon = SvgHelper.getBitmap(AndroidUtilities.readRes(R.raw.qr_dog), AndroidUtilities.dp(60), AndroidUtilities.dp(60), false);
            alert.setCenterImage(icon);
            showDialog(alert);
        });

        sectionCell[1] = new ShadowSectionCell(context);
        sectionCell[1].setBackgroundDrawable(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        linearLayout2.addView(sectionCell[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        shareDoneEnabled = true;
        shareDoneProgress = 1f;
        checkShareDone(false);

        currentType = -1;
        setProxyType(TextUtils.isEmpty(currentProxyInfo.secret) ? 0 : 1, false);

        pasteType = -1;
        pasteString = null;
        updatePasteCell();

        return fragmentView;
    }

    private void updatePasteCell() {
        final ClipData clip = clipboardManager.getPrimaryClip();

        String clipText;
        if (clip != null && clip.getItemCount() > 0) {
            try {
                clipText = clip.getItemAt(0).coerceToText(fragmentView.getContext()).toString();
            } catch (Exception e) {
                clipText = null;
            }
        } else {
            clipText = null;
        }

        if (TextUtils.equals(clipText, pasteString)) {
            return;
        }

        pasteType = -1;
        pasteString = clipText;
        pasteFields = new String[inputFields.length];
        if (clipText != null) {
            String[] params = null;

            final String[] socksStrings = {"t.me/socks?", "tg://socks?"};
            for (int i = 0; i < socksStrings.length; i++) {
                final int index = clipText.indexOf(socksStrings[i]);
                if (index >= 0) {
                    pasteType = TYPE_SOCKS5;
                    params = clipText.substring(index + socksStrings[i].length()).split("&");
                    break;
                }
            }

            if (params == null) {
                final String[] proxyStrings = {"t.me/proxy?", "tg://proxy?"};
                for (int i = 0; i < proxyStrings.length; i++) {
                    final int index = clipText.indexOf(proxyStrings[i]);
                    if (index >= 0) {
                        pasteType = TYPE_MTPROTO;
                        params = clipText.substring(index + proxyStrings[i].length()).split("&");
                        break;
                    }
                }
            }

            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    final String[] pair = params[i].split("=");
                    if (pair.length != 2) continue;
                    switch (pair[0].toLowerCase()) {
                        case "server":
                            pasteFields[FIELD_IP] = pair[1];
                            break;
                        case "port":
                            pasteFields[FIELD_PORT] = pair[1];
                            break;
                        case "user":
                            if (pasteType == TYPE_SOCKS5) {
                                pasteFields[FIELD_USER] = pair[1];
                            }
                            break;
                        case "pass":
                            if (pasteType == TYPE_SOCKS5) {
                                pasteFields[FIELD_PASSWORD] = pair[1];
                            }
                            break;
                        case "secret":
                            if (pasteType == TYPE_MTPROTO) {
                                pasteFields[FIELD_SECRET] = pair[1];
                            }
                            break;
                    }
                }
            }
        }

        if (pasteType != -1) {
            if (pasteCell.getVisibility() != View.VISIBLE) {
                pasteCell.setVisibility(View.VISIBLE);
                sectionCell[2].setVisibility(View.VISIBLE);
            }
        } else {
            if (pasteCell.getVisibility() != View.GONE) {
                pasteCell.setVisibility(View.GONE);
                sectionCell[2].setVisibility(View.GONE);
            }
        }
    }

    private void setShareDoneEnabled(boolean enabled, boolean animated) {
        if (shareDoneEnabled != enabled) {
            if (shareDoneAnimator != null) {
                shareDoneAnimator.cancel();
            } else if (animated) {
                shareDoneAnimator = ValueAnimator.ofFloat(0f, 1f);
                shareDoneAnimator.setDuration(200);
                shareDoneAnimator.addUpdateListener(a -> {
                    shareDoneProgress = AndroidUtilities.lerp(shareDoneProgressAnimValues, a.getAnimatedFraction());
                    shareCell.setTextColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), shareDoneProgress));
                    doneItem.setAlpha(shareDoneProgress / 2f + 0.5f);
                });
            }
            if (animated) {
                shareDoneProgressAnimValues[0] = shareDoneProgress;
                shareDoneProgressAnimValues[1] = enabled ? 1f : 0f;
                shareDoneAnimator.start();
            } else {
                shareDoneProgress = enabled ? 1f : 0f;
                shareCell.setTextColor(enabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                doneItem.setAlpha(enabled ? 1f : .5f);
            }
            shareCell.setEnabled(enabled);
            doneItem.setEnabled(enabled);
            shareDoneEnabled = enabled;
        }
    }

    private void checkShareDone(boolean animated) {
        if (shareCell == null || doneItem == null || inputFields[FIELD_IP] == null || inputFields[FIELD_PORT] == null) {
            return;
        }
        setShareDoneEnabled(inputFields[FIELD_IP].length() != 0 && Utilities.parseInt(inputFields[FIELD_PORT].getText().toString()) != 0, animated);
    }

    private void setProxyType(int type, boolean animated) {
        setProxyType(type, animated, null);
    }

    private void setProxyType(int type, boolean animated, Runnable onTransitionEnd) {
        if (currentType != type) {
            currentType = type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TransitionManager.endTransitions(linearLayout2);
            }
            if (animated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final TransitionSet transitionSet = new TransitionSet()
                        .addTransition(new Fade(Fade.OUT))
                        .addTransition(new ChangeBounds())
                        .addTransition(new Fade(Fade.IN))
                        .setInterpolator(CubicBezierInterpolator.DEFAULT)
                        .setDuration(250);

                if (onTransitionEnd != null) {
                    transitionSet.addListener(new Transition.TransitionListener() {
                        @Override
                        public void onTransitionStart(Transition transition) {
                        }

                        @Override
                        public void onTransitionEnd(Transition transition) {
                            onTransitionEnd.run();
                        }

                        @Override
                        public void onTransitionCancel(Transition transition) {
                        }

                        @Override
                        public void onTransitionPause(Transition transition) {
                        }

                        @Override
                        public void onTransitionResume(Transition transition) {
                        }
                    });
                }

                TransitionManager.beginDelayedTransition(linearLayout2, transitionSet);
            }
            if (currentType == 0) {
                bottomCells[0].setVisibility(View.VISIBLE);
                bottomCells[1].setVisibility(View.GONE);
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.VISIBLE);
                if (fragmentContainer != null) fragmentContainer.setVisibility(View.GONE);
            } else if (currentType == 1) {
                bottomCells[0].setVisibility(View.GONE);
                bottomCells[1].setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_SECRET].getParent()).setVisibility(View.VISIBLE);
                ((View) inputFields[FIELD_PASSWORD].getParent()).setVisibility(View.GONE);
                ((View) inputFields[FIELD_USER].getParent()).setVisibility(View.GONE);
                if (fragmentContainer != null) fragmentContainer.setVisibility(View.VISIBLE);
            }
            typeCell[0].setChecked(currentType == 0, animated);
            typeCell[1].setChecked(currentType == 1, animated);
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && !backward && addingNewProxy) {
            inputFields[FIELD_IP].requestFocus();
            AndroidUtilities.showKeyboard(inputFields[FIELD_IP]);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (shareCell != null && (shareDoneAnimator == null || !shareDoneAnimator.isRunning())) {
                shareCell.setTextColor(shareDoneEnabled ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4) : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            }
            if (inputFields != null) {
                for (int i = 0; i < inputFields.length; i++) {
                    inputFields[i].setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                            Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                            Theme.getColor(Theme.key_text_RedRegular));
                }
            }
        };
        ArrayList<ThemeDescription> arrayList = new ArrayList<>();
        arrayList.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(scrollView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder));
        arrayList.add(new ThemeDescription(inputFieldsContainer, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(linearLayout2, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(shareCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteBlueText4));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, null, delegate, Theme.key_windowBackgroundWhiteGrayText2));

        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(pasteCell, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        arrayList.add(new ThemeDescription(pasteCell, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText4));

        for (int a = 0; a < typeCell.length; a++) {
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
            arrayList.add(new ThemeDescription(typeCell[a], 0, new Class[]{RadioCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOX, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackground));
            arrayList.add(new ThemeDescription(typeCell[a], ThemeDescription.FLAG_CHECKBOXCHECK, new Class[]{RadioCell.class}, new String[]{"radioButton"}, null, null, null, Theme.key_radioBackgroundChecked));
        }

        if (inputFields != null) {
            for (int a = 0; a < inputFields.length; a++) {
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_HINTTEXTCOLOR | ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
                arrayList.add(new ThemeDescription(inputFields[a], ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputField));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_windowBackgroundWhiteInputFieldActivated));
                arrayList.add(new ThemeDescription(null, 0, null, null, null, delegate, Theme.key_text_RedRegular));
            }
        } else {
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            arrayList.add(new ThemeDescription(null, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        }
        arrayList.add(new ThemeDescription(headerCell, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(headerCell, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        for (int a = 0; a < sectionCell.length; a++) {
            if (sectionCell[a] != null) {
                arrayList.add(new ThemeDescription(sectionCell[a], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            }
        }
        for (int i = 0; i < bottomCells.length; i++) {
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
            arrayList.add(new ThemeDescription(bottomCells[i], 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
            arrayList.add(new ThemeDescription(bottomCells[i], ThemeDescription.FLAG_LINKCOLOR, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteLinkText));
        }

        return arrayList;
    }
}
