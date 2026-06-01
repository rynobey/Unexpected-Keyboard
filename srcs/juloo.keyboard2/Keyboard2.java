package juloo.keyboard2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.util.LogPrinter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.dict.DictionariesActivity;
import juloo.keyboard2.prefs.LayoutsPreference;
import juloo.keyboard2.suggestions.CandidatesView;
import juloo.keyboard2.suggestions.Suggestions;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  /** The view containing the keyboard and candidates view. */
  private ViewGroup _keyboard_container_view;
  private Keyboard2View _keyboard_layout_view;
  private CandidatesView _candidates_view;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  /** Installed and current locales. */
  private Dictionaries _dictionaries;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  private Handler _handler;

  private Config _config;

  private FoldStateTracker _foldStateTracker;

  /** Layout currently visible before it has been modified. */
  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  /** Layout currently visible. */
  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    return LayoutModifier.modify_layout(current_layout_unmodified());
  }

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    _keyboard_layout_view.setKeyboard(l);
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    _handler = new Handler(getMainLooper());
    _foldStateTracker = new FoldStateTracker(this);
    _dictionaries = Dictionaries.instance(this);
    Config.initGlobalConfig(prefs, getResources(),
        _foldStateTracker.isUnfolded(), _dictionaries);
    _config = Config.globalConfig();
    _keyeventhandler = new KeyEventHandler(this.new Receiver(), _config);
    _config.handler = _keyeventhandler;
    prefs.registerOnSharedPreferenceChangeListener(this);
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    refreshSubtypeImm();
    create_keyboard_view();
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    _foldStateTracker.close();
  }

  private void create_keyboard_view()
  {
    _keyboard_container_view = (ViewGroup)inflate_view(R.layout.keyboard);
    _keyboard_layout_view = (Keyboard2View)_keyboard_container_view.findViewById(R.id.keyboard_view);
    _candidates_view = (CandidatesView)_keyboard_container_view.findViewById(R.id.candidates_view);
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  private void refreshSubtypeImm()
  {
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _config.device_locales = DeviceLocales.load(this);
    if (_config.device_locales.default_ != null)
    {
      String layout_name = _config.device_locales.default_.default_layout;
      if (layout_name != null)
        default_layout = LayoutsPreference.layout_of_string(getResources(), layout_name);
    }
    _config.extra_keys_subtype = _config.device_locales.extra_keys();
    if (default_layout == null)
      default_layout = loadLayout(R.xml.latn_qwerty_us);
    _localeTextLayout = default_layout;
  }

  private void refresh_current_dictionary()
  {
    _config.current_dictionary = null;
    _config.emoji_dictionary = null;
    if (_config.device_locales.default_ == null)
      return;
    String current = _config.device_locales.default_.dictionary;
    if (current == null)
      return;
    Cdict[] dicts = _dictionaries.load(current);
    if (dicts == null)
      return;
    _config.current_dictionary = Dictionaries.find_by_name(dicts, "main");
    _config.emoji_dictionary = Dictionaries.find_by_name(dicts, "emoji");
  }

  /** Experimental fullscreen landscape split mode is active.
      Now reads the per-session decision (computed in onStartInputView). */
  private boolean split_active()
  {
    return _config != null && _config.session_split && _config.orientation_landscape;
  }

  /** Decide whether THIS input session should use split mode. Driven by
      the split_mode preference, hardware-keyboard presence, and the
      focused app's manifest meta-data when in "auto". */
  private boolean shouldUseSplitForSession(EditorInfo info)
  {
    if (_config == null) return false;
    // Hardware keyboard present? Skip split mode — the on-screen keyboard
    // shouldn't claim the full screen when typing on hardware. System
    // usually auto-hides the IME in this case; if it doesn't, fall back
    // to a normal compact landscape keyboard.
    Configuration cfg = getResources().getConfiguration();
    if (cfg.keyboard == Configuration.KEYBOARD_QWERTY
        && cfg.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES)
      return false;
    String mode = _config.split_mode;
    if ("off".equals(mode))    return false;
    if ("always".equals(mode)) return true;
    // "auto" — check whether the focused app declares hole-layout support
    // via manifest meta-data. No central allow-list: apps opt in themselves.
    if (info == null || info.packageName == null) return false;
    return appDeclaresHoleSupport(info.packageName);
  }

  /** True if the named app's manifest has
      <meta-data android:name="…SUPPORTS_HOLE_LAYOUT" android:value="true"/>
      on its <application> tag. Fails closed on any lookup error. */
  private boolean appDeclaresHoleSupport(String packageName)
  {
    try {
      ApplicationInfo ai = getPackageManager()
          .getApplicationInfo(packageName, PackageManager.GET_META_DATA);
      return ai.metaData != null
          && ai.metaData.getBoolean(Config.META_SUPPORTS_HOLE_LAYOUT, false);
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  /** The package we'll send hole-layout broadcasts to for the current
      input session — null when no compatible app is focused. */
  private String _holeReceiverPkg = null;

  /** Unicast broadcast the current hole rectangle (or "clear") to the
      compatible app. Coordinates are converted view-local → screen px. */
  private void broadcastHoleLayout(boolean active, RectF rectInView, View view)
  {
    if (_holeReceiverPkg == null) return;
    Intent i = new Intent(Config.ACTION_HOLE_LAYOUT);
    i.setPackage(_holeReceiverPkg);   // unicast — only this app gets it
    i.putExtra("protocol_version", 1);
    i.putExtra("active", active);
    if (active && rectInView != null && view != null) {
      int[] origin = new int[2];
      view.getLocationOnScreen(origin);
      i.putExtra("rect_left",   (int)(rectInView.left   + origin[0]));
      i.putExtra("rect_top",    (int)(rectInView.top    + origin[1]));
      i.putExtra("rect_right",  (int)(rectInView.right  + origin[0]));
      i.putExtra("rect_bottom", (int)(rectInView.bottom + origin[1]));
    }
    sendBroadcast(i);
  }

  /** Called by Keyboard2View after each onMeasure in split mode. */
  public void onSplitRectChanged(RectF centerRectInView, View view)
  {
    if (_holeReceiverPkg != null)
      broadcastHoleLayout(true, centerRectInView, view);
  }

  private void refresh_candidates_view()
  {
    boolean should_show =
      _config.suggestions_enabled
      && _config.editor_config.should_show_candidates_view
      && !split_active();
    if (should_show)
      _candidates_view.refresh_config(_config);
    _candidates_view.setVisibility(should_show ? View.VISIBLE : View.GONE);
  }

  /** Might re-create the keyboard view. [_keyboard_layout_view.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded(), _dictionaries);
    refresh_current_dictionary();
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      create_keyboard_view();
      _emojiPane = null;
      _clipboard_pane = null;
      setInputView(_keyboard_container_view);
    }
    // Set keyboard background opacity. In split mode the container must be
    // transparent so the app behind shows through the central hole.
    // setAlpha is reversible (the colorKeyboard drawable stays attached),
    // so the next non-split session restores the normal opacity.
    Drawable bg = _keyboard_container_view.getBackground().mutate();
    bg.setAlpha(split_active() ? 0 : _config.keyboardOpacity);
    _keyboard_container_view.setBackground(bg);
    _keyboard_layout_view.reset();
    refresh_candidates_view();
  }

  private KeyboardData refresh_special_layout()
  {
    if (_config.editor_config.numeric_layout)
    {
      switch (_config.selected_number_layout)
      {
        case PIN: return loadPinentry(R.xml.pin);
        case NUMBER: return loadNumpad(R.xml.numeric);
      }
    }
    return null;
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    _config.editor_config.refresh(info, getResources());
    // Lock the per-session split decision BEFORE refresh_config() —
    // split_active() now reads _config.session_split.
    _config.session_split = shouldUseSplitForSession(info);
    _holeReceiverPkg = (_config.session_split && info != null) ? info.packageName : null;
    refresh_config();
    _currentSpecialLayout = refresh_special_layout();
    _keyboard_layout_view.setKeyboard(current_layout());
    _keyeventhandler.started(_config);
    setInputView(_keyboard_container_view);
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setInputView(View v)
  {
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);
    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
    v.requestApplyInsets();
  }

  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  /** Originals of window bg + chain bgs, saved on first split-mode entry
      and restored on exit. Without scoping these changes to split mode,
      the IME's surrounding system-panel area goes transparent for ALL
      keyboard sessions — including non-split — which exposes black gaps
      between the IME and the app behind. */
  private Drawable _saved_window_bg = null;
  private boolean _saved_window_bg_valid = false;
  private final java.util.IdentityHashMap<View, Drawable> _saved_chain_bgs =
      new java.util.IdentityHashMap<>();

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();

    if (split_active()) {
      applyTransparencyForSplit(window);
    } else {
      restoreTransparencyAfterSplit(window);
    }

    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      WindowManager.LayoutParams wattrs = window.getAttributes();
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }
    updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
    final View inputArea = window.findViewById(android.R.id.inputArea);

    updateLayoutHeightOf(
            (View) inputArea.getParent(),
            (isFullscreenMode() || split_active())
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT);
    updateLayoutGravityOf((View) inputArea.getParent(), Gravity.BOTTOM);
  }

  /** In split mode: save the original window + chain backgrounds (first
      time only) and replace them with transparency so the central hole
      can show the app behind.

      Crucially, also request PixelFormat.TRANSLUCENT for the window's
      surface buffer. Without this, the IME's window surface is allocated
      as RGBx_8888 (no alpha channel) — verified via dumpsys SurfaceFlinger
      — so the "transparent" centre is just opaque black pixels regardless
      of what View backgrounds we clear. Switching to TRANSLUCENT makes
      Android allocate RGBA_8888, allowing the central hole to actually
      composite through to the SurfaceView (e.g. termux-x11's X canvas)
      underneath. */
  private void applyTransparencyForSplit(Window window)
  {
    if (!_saved_window_bg_valid) {
      _saved_window_bg = window.getDecorView().getBackground();
      _saved_window_bg_valid = true;
    }
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setFormat(PixelFormat.TRANSLUCENT);

    if (_keyboard_container_view == null) return;
    final View decor = window.getDecorView();
    ViewParent p = _keyboard_container_view.getParent();
    while (p instanceof View)
    {
      View pv = (View) p;
      if (!_saved_chain_bgs.containsKey(pv)) {
        _saved_chain_bgs.put(pv, pv.getBackground());
      }
      pv.setBackground(null);
      if (pv == decor) break;
      p = pv.getParent();
    }
  }

  /** In non-split mode: restore the originals we saved, so normal keyboard
      sessions look the way the system theme intended. */
  private void restoreTransparencyAfterSplit(Window window)
  {
    if (_saved_window_bg_valid) {
      window.setBackgroundDrawable(_saved_window_bg);
      _saved_window_bg = null;
      _saved_window_bg_valid = false;
    }
    // Restore the default opaque buffer format so non-split sessions
    // don't pay the alpha-blending cost.
    window.setFormat(PixelFormat.OPAQUE);
    if (!_saved_chain_bgs.isEmpty()) {
      for (java.util.Map.Entry<View, Drawable> e : _saved_chain_bgs.entrySet()) {
        e.getKey().setBackground(e.getValue());
      }
      _saved_chain_bgs.clear();
    }
  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    refresh_current_dictionary();
    refresh_candidates_view();
    _keyboard_layout_view.setKeyboard(current_layout());
    _keyeventhandler.ime_subtype_changed();
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart, newSelEnd);
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboard_layout_view.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    if (_holeReceiverPkg != null) {
      broadcastHoleLayout(false, null, null);
      _holeReceiverPkg = null;
    }
    _keyboard_layout_view.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    refresh_config();
    _keyboard_layout_view.setKeyboard(current_layout());
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  @Override
  public boolean onEvaluateInputViewShown()
  {
    // Since Android 16, this method returns [false] for unknown reasons.
    if (super.onEvaluateInputViewShown())
      return true;
    if (getResources().getConfiguration().hardKeyboardHidden
        == Configuration.HARDKEYBOARDHIDDEN_NO)
    {
      Logs.debug("Physical keyboard is present");
      return false;
    }
    return true;
  }

  /** Tell the system how much of our IME window actually occludes the app
      behind, and where touches should be routed. In split mode the
      keyboard physically covers the full screen but the centre is
      transparent — we want the app to:
        1. stay full-screen (don't resize for our IME)
        2. receive touches in the centre (pass through the hole)
      Both are achieved by reporting zero content/visible insets and a
      touchable region that excludes the centre rect. */
  @Override
  public void onComputeInsets(InputMethodService.Insets outInsets)
  {
    super.onComputeInsets(outInsets);
    if (!split_active() || _keyboard_layout_view == null) return;

    // Tell the system our IME doesn't occlude any pixels of the app.
    // This stops the system from resizing the activity behind, which
    // otherwise tries to fit into "zero" remaining height.
    outInsets.contentTopInsets = _keyboard_layout_view.getHeight();
    outInsets.visibleTopInsets = _keyboard_layout_view.getHeight();

    // Build a touchable region covering the left and right halves only.
    // Touches in the centre hole pass through to the app behind.
    RectF c = _keyboard_layout_view.getCenterRect();
    if (c != null && c.width() > 0 && c.height() > 0) {
      int[] origin = new int[2];
      _keyboard_layout_view.getLocationOnScreen(origin);
      int viewW = _keyboard_layout_view.getWidth();
      int viewH = _keyboard_layout_view.getHeight();
      android.graphics.Region region = new android.graphics.Region(
          origin[0], origin[1], origin[0] + viewW, origin[1] + viewH);
      android.graphics.Rect hole = new android.graphics.Rect(
          origin[0] + (int) c.left,  origin[1] + (int) c.top,
          origin[0] + (int) c.right, origin[1] + (int) c.bottom);
      region.op(hole, android.graphics.Region.Op.DIFFERENCE);
      outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
      outInsets.touchableRegion.set(region);
    }
  }

  /** Called from [onClick] attributes. */
  public void launch_dictionaries_activity(View v)
  {
    start_activity(DictionariesActivity.class);
  }

  void start_activity(Class cls)
  {
    Intent intent = new Intent(this, cls);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          start_activity(SettingsActivity.class);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          _keyboard_layout_view.setKeyboard(current_layout());
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumpad(R.xml.numeric));
          break;

        case SWITCH_EMOJI:
          if (_emojiPane == null)
            _emojiPane = (ViewGroup)inflate_view(R.layout.emoji_pane);
          setInputView(_emojiPane);
          break;

        case SWITCH_CLIPBOARD:
          if (_clipboard_pane == null)
            _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
          setInputView(_clipboard_pane);
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          setInputView(_keyboard_container_view);
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_PREV:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToPreviousInputMethod();
          break;

        case CHANGE_METHOD_NEXT:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToNextInputMethod(getConnectionToken(), false);
          else
            switchToNextInputMethod(false);
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(_config.editor_config.actionId);
          break;

        case SWITCH_FORWARD:
          incrTextLayout(1);
          break;

        case SWITCH_BACKWARD:
          incrTextLayout(-1);
          break;

        case SWITCH_GREEKMATH:
          setSpecialLayout(loadNumpad(R.xml.greekmath));
          break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboard_layout_view.set_shift_state(state, lock);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboard_layout_view.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboard_layout_view.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      return Keyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }

    public void set_suggestions(Suggestions suggestions)
    {
      _candidates_view.set_candidates(suggestions);
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
  }
}
