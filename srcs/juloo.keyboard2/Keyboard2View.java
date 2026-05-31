package juloo.keyboard2;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Keyboard2View extends View
  implements View.OnTouchListener, Pointers.IPointerEventHandler
{
  private KeyboardData _keyboard;

  /** The key holding the shift key is used to set shift state from
      autocapitalisation. */
  private KeyboardData.Key _shift_key;

  /** Used to add fake pointers. */
  private KeyboardData.Key _compose_key;

  private Pointers _pointers;

  private Pointers.Modifiers _mods;

  private static int _currentWhat = 0;

  private Config _config;

  private float _keyWidth;
  private float _mainLabelSize;
  private float _subLabelSize;
  private float _marginRight;
  private float _marginLeft;
  private float _marginBottom;
  private int _insets_left = 0;
  private int _insets_right = 0;
  private int _insets_bottom = 0;

  private Theme _theme;
  private Theme.Computed _tc;

  /** Fullscreen landscape split mode (experimental). When active the keyboard
      fills the window height and is split into a left and right half whose
      keys are packed as an interleaved triangle tessellation, with a center
      type-test display between them. */
  private boolean _split = false;
  /** Fraction of the width reserved for the center gap in split mode. */
  private static final float SPLIT_CENTER_RATIO = 0.60f;
  /** The center rectangle, recomputed in [onMeasure] when split. */
  private final RectF _center_rect = new RectF();
  /** The triangle (and occasional rectangle) keys of both halves, rebuilt in
      [onMeasure] when split. */
  private final ArrayList<TriKey> _split_keys = new ArrayList<TriKey>();
  private final Path _tri_path = new Path();
  /** Characters typed while in split mode, echoed in the center area. */
  private final StringBuilder _test_text = new StringBuilder();
  private final Paint _test_paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _test_hint_paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _test_bg_paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _tri_border_paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  /** A key occupying a triangle in split mode. [key] may be null for an unused
      slot. Vertices are absolute view coordinates. */
  static final class TriKey
  {
    final KeyboardData.Key key;
    final float[] xs;
    final float[] ys;
    final float cx; // label anchor (centroid)
    final float cy;
    TriKey(KeyboardData.Key k, float x0, float y0, float x1, float y1, float x2, float y2)
    {
      key = k;
      xs = new float[]{ x0, x1, x2 };
      ys = new float[]{ y0, y1, y2 };
      cx = (x0 + x1 + x2) / 3f;
      cy = (y0 + y1 + y2) / 3f;
    }

    /** Barycentric sign test for point-in-triangle. */
    boolean contains(float px, float py)
    {
      float d1 = sign(px, py, xs[0], ys[0], xs[1], ys[1]);
      float d2 = sign(px, py, xs[1], ys[1], xs[2], ys[2]);
      float d3 = sign(px, py, xs[2], ys[2], xs[0], ys[0]);
      boolean neg = (d1 < 0) || (d2 < 0) || (d3 < 0);
      boolean pos = (d1 > 0) || (d2 > 0) || (d3 > 0);
      return !(neg && pos);
    }

    private static float sign(float px, float py, float ax, float ay, float bx, float by)
    {
      return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }
  }

  private static RectF _tmpRect = new RectF();

  enum Vertical
  {
    TOP,
    CENTER,
    BOTTOM
  }

  public Keyboard2View(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _theme = new Theme(getContext(), attrs);
    _config = Config.globalConfig();
    _pointers = new Pointers(this, _config);
    refresh_navigation_bar(context);
    setOnTouchListener(this);
    int layout_id = (attrs == null) ? 0 :
      attrs.getAttributeResourceValue(null, "layout", 0);
    if (layout_id == 0)
      reset();
    else
      setKeyboard(KeyboardData.load(getResources(), layout_id));
  }

  private Window getParentWindow(Context context)
  {
    if (context instanceof InputMethodService)
      return ((InputMethodService)context).getWindow().getWindow();
    if (context instanceof ContextWrapper)
      return getParentWindow(((ContextWrapper)context).getBaseContext());
    return null;
  }

  public void refresh_navigation_bar(Context context)
  {
    if (VERSION.SDK_INT < 21)
      return;
    // The intermediate Window is a [Dialog].
    Window w = getParentWindow(context);
    w.setNavigationBarColor(_theme.colorNavBar);
    if (VERSION.SDK_INT < 26)
      return;
    int uiFlags = getSystemUiVisibility();
    if (_theme.isLightNavBar)
      uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    else
      uiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    setSystemUiVisibility(uiFlags);
  }

  public void setKeyboard(KeyboardData kw)
  {
    _keyboard = kw;
    _shift_key = _keyboard.findKeyWithValue(KeyValue.SHIFT);
    _compose_key = _keyboard.findKeyWithValue(KeyValue.COMPOSE);
    KeyModifier.set_modmap(_keyboard.modmap);
    reset();
  }

  public void reset()
  {
    _mods = Pointers.Modifiers.EMPTY;
    _pointers.clear();
    requestLayout();
    invalidate();
  }

  void set_fake_ptr_latched(KeyboardData.Key key, KeyValue kv, boolean latched,
      boolean lock)
  {
    if (_keyboard == null || key == null)
      return;
    _pointers.set_fake_pointer_state(key, kv, latched, lock);
  }

  /** Called by auto-capitalisation. */
  public void set_shift_state(boolean latched, boolean lock)
  {
    set_fake_ptr_latched(_shift_key, KeyValue.SHIFT, latched, lock);
  }

  /** Called from [KeyEventHandler]. */
  public void set_compose_pending(boolean pending)
  {
    set_fake_ptr_latched(_compose_key, KeyValue.COMPOSE, pending, false);
  }

  /** Called from [Keybard2.onUpdateSelection].  */
  public void set_selection_state(boolean selection_state)
  {
    if (_config.editor_config.selection_mode_enabled)
      set_fake_ptr_latched(KeyboardData.Key.EMPTY,
          KeyValue.SELECTION_MODE, selection_state, true);
  }

  public KeyValue modifyKey(KeyValue k, Pointers.Modifiers mods)
  {
    return KeyModifier.modify(k, mods);
  }

  public void onPointerDown(KeyValue k, boolean isSwipe)
  {
    updateFlags();
    _config.handler.key_down(k, isSwipe);
    invalidate();
    vibrate();
  }

  public void onPointerUp(KeyValue k, Pointers.Modifiers mods)
  {
    // [key_up] must be called before [updateFlags]. The latter might disable
    // flags.
    _config.handler.key_up(k, mods);
    updateFlags();
    if (_split)
      test_capture(k);
    invalidate();
  }

  public void onPointerHold(KeyValue k, Pointers.Modifiers mods)
  {
    _config.handler.key_up(k, mods);
    updateFlags();
  }

  public void onPointerFlagsChanged(boolean shouldVibrate)
  {
    updateFlags();
    invalidate();
    if (shouldVibrate)
      vibrate();
  }

  private void updateFlags()
  {
    _mods = _pointers.getModifiers();
    _config.handler.mods_changed(_mods);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    int p;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_POINTER_UP:
        _pointers.onTouchUp(event.getPointerId(event.getActionIndex()));
        break;
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_POINTER_DOWN:
        p = event.getActionIndex();
        float tx = event.getX(p);
        float ty = event.getY(p);
        KeyboardData.Key key = getKeyAtPosition(tx, ty);
        if (key != null)
          _pointers.onTouchDown(tx, ty, event.getPointerId(p), key);
        break;
      case MotionEvent.ACTION_MOVE:
        for (p = 0; p < event.getPointerCount(); p++)
          _pointers.onTouchMove(event.getX(p), event.getY(p), event.getPointerId(p));
        break;
      case MotionEvent.ACTION_CANCEL:
        _pointers.onTouchCancel();
        break;
      default:
        return (false);
    }
    return (true);
  }

  private KeyboardData.Row getRowAtPosition(float ty)
  {
    float y = _config.marginTop;
    if (ty < y)
      return null;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += (row.shift + row.height) * _tc.row_height;
      if (ty < y)
        return row;
    }
    return null;
  }

  private KeyboardData.Key getKeyAtPosition(float tx, float ty)
  {
    if (_split)
    {
      for (TriKey tk : _split_keys)
        if (tk.key != null && tk.contains(tx, ty))
          return tk.key;
      return null;
    }
    KeyboardData.Row row = getRowAtPosition(ty);
    float x = _marginLeft;
    if (row == null || tx < x)
      return null;
    for (KeyboardData.Key key : row.keys)
    {
      float xLeft = x + key.shift * _keyWidth;
      float xRight = xLeft + key.width * _keyWidth;
      if (tx < xLeft)
        return null;
      if (tx < xRight)
        return key;
      x = xRight;
    }
    return null;
  }

  private void vibrate()
  {
    VibratorCompat.vibrate(this, _config);
  }

  @Override
  public void onMeasure(int wSpec, int hSpec)
  {
    DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
    int width = dm.widthPixels;
    _marginLeft = Math.max(_config.horizontal_margin, _insets_left);
    _marginRight = Math.max(_config.horizontal_margin, _insets_right);
    _marginBottom = _config.margin_bottom + _insets_bottom;
    _split = _config.split_test_mode && _config.orientation_landscape;
    _keyWidth = (width - _marginLeft - _marginRight) / _keyboard.keysWidth;
    float fill_rows = 0f;
    if (_split)
    {
      // Stretch the rows to fill the whole window height.
      int avail = MeasureSpec.getSize(hSpec);
      if (avail <= 0)
        avail = _config.screenHeightPixels;
      fill_rows = avail - _config.marginTop - _marginBottom;
    }
    _tc = new Theme.Computed(_theme, _config, _keyWidth, _keyboard, fill_rows);
    // Compute the size of labels based on the width or the height of keys. The
    // margin around keys is taken into account. Keys normal aspect ratio is
    // assumed to be 3/2 for a 10 columns layout. It's generally more, the
    // width computation is useful when the keyboard is unusually high.
    float labelBaseSize = Math.min(
        _tc.row_height - _tc.vertical_margin,
        (width / 10 - _tc.horizontal_margin) * 3/2
        ) * _config.characterSize;
    _mainLabelSize = labelBaseSize * _config.labelTextSize;
    _subLabelSize = labelBaseSize * _config.sublabelTextSize;
    int height =
      (int)(_tc.row_height * _keyboard.keysHeight
          + _config.marginTop + _marginBottom);
    if (_split)
    {
      float gap = width * SPLIT_CENTER_RATIO;
      _center_rect.left = (width - gap) / 2f;
      _center_rect.right = _center_rect.left + gap;
      _center_rect.top = _tc.margin_top;
      _center_rect.bottom = height - _marginBottom;
      buildSplitTriangles(width, height);
    }
    setMeasuredDimension(width, height);
  }

  Rect _cached_exclusion_rect = new Rect();
  List<Rect> _cached_exclusion_rects = Arrays.asList(_cached_exclusion_rect);
  @Override
  public void onLayout(boolean changed, int left, int top, int right, int bottom)
  {
    if (!changed)
      return;
    if (VERSION.SDK_INT >= 29)
    {
      // Disable the back-gesture on the keyboard area
      _cached_exclusion_rect.set(
          left + (int)_marginLeft,
          top + (int)_config.marginTop,
          right - (int)_marginRight,
          bottom - (int)_marginBottom);
      setSystemGestureExclusionRects(_cached_exclusion_rects);
    }
  }

  @Override
  public WindowInsets onApplyWindowInsets(WindowInsets wi)
  {
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is set in [Keyboard2#updateSoftInputWindowLayoutParams] for SDK_INT >= 35.
    if (VERSION.SDK_INT < 35)
      return wi;
    int insets_types =
      WindowInsets.Type.systemBars()
      | WindowInsets.Type.displayCutout();
    Insets insets = wi.getInsets(insets_types);
    _insets_left = insets.left;
    _insets_right = insets.right;
    _insets_bottom = insets.bottom;
    return WindowInsets.CONSUMED;
  }

  /** Horizontal and vertical position of the 9 indexes. */
  static final Paint.Align[] LABEL_POSITION_H = new Paint.Align[]{
    Paint.Align.CENTER, Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT,
    Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.RIGHT,
    Paint.Align.CENTER, Paint.Align.CENTER
  };

  static final Vertical[] LABEL_POSITION_V = new Vertical[]{
    Vertical.CENTER, Vertical.TOP, Vertical.TOP, Vertical.BOTTOM,
    Vertical.BOTTOM, Vertical.CENTER, Vertical.CENTER, Vertical.TOP,
    Vertical.BOTTOM
  };

  @Override
  protected void onDraw(Canvas canvas)
  {
    if (_split)
    {
      drawCenterTestArea(canvas);
      drawSplitTriangles(canvas);
      return;
    }
    float y = _tc.margin_top;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += row.shift * _tc.row_height;
      float x = _marginLeft + _tc.margin_left;
      float keyH = row.height * _tc.row_height - _tc.vertical_margin;
      for (KeyboardData.Key k : row.keys)
      {
        x += k.shift * _keyWidth;
        float keyW = _keyWidth * k.width - _tc.horizontal_margin;
        boolean isKeyDown = _pointers.isKeyDown(k);
        Theme.Computed.Key tc_key = isKeyDown ? _tc.key_activated : _tc.key;
        drawKeyFrame(canvas, x, y, keyW, keyH, tc_key);
        if (k.keys[0] != null)
          drawLabel(canvas, k.keys[0], keyW / 2f + x, y, keyH, isKeyDown, tc_key);
        for (int i = 1; i < 9; i++)
        {
          if (k.keys[i] != null)
            drawSubLabel(canvas, k.keys[i], x, y, keyW, keyH, i, isKeyDown, tc_key);
        }
        drawIndication(canvas, k, x, y, keyW, keyH, _tc);
        x += _keyWidth * k.width;
      }
      y += row.height * _tc.row_height;
    }
  }

  /** Build the interleaved triangle tessellation for both halves. Called from
      [onMeasure] in split mode. */
  private void buildSplitTriangles(int viewW, int viewH)
  {
    _split_keys.clear();
    float top = _tc.margin_top;
    float bottom = viewH - _marginBottom;
    // Split the layout's keys into a left and right half by column.
    ArrayList<KeyboardData.Key> left = new ArrayList<KeyboardData.Key>();
    ArrayList<KeyboardData.Key> right = new ArrayList<KeyboardData.Key>();
    float splitCol = _keyboard.keysWidth / 2f;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      float col = 0f;
      for (KeyboardData.Key k : row.keys)
      {
        col += k.shift;
        if (col + k.width / 2f < splitCol)
          left.add(k);
        else
          right.add(k);
        col += k.width;
      }
    }
    // Duplicate a space key onto both halves.
    KeyboardData.Key space =
      KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.getKeyByName("space"));
    left.add(space);
    right.add(space);
    _tri_border_paint.setStyle(Paint.Style.STROKE);
    _tri_border_paint.setStrokeWidth(
        Math.max(2f, getResources().getDisplayMetrics().density * 1.5f));
    _tri_border_paint.setColor(_theme.colorKeyActivated);
    tessellateHalf(_marginLeft, top, _center_rect.left, bottom, left);
    tessellateHalf(_center_rect.right, top, viewW - _marginRight, bottom, right);
  }

  /** Fill the rectangle [x0,y0,x1,y1] with diagonal-split cells, alternating
      the diagonal per cell so the triangles interleave, and assign [keys] to
      the triangle slots in order. Outer edges stay straight. */
  private void tessellateHalf(float x0, float y0, float x1, float y1,
      ArrayList<KeyboardData.Key> keys)
  {
    int K = keys.size();
    if (K <= 0 || x1 <= x0 || y1 <= y0)
      return;
    float W = x1 - x0;
    float H = y1 - y0;
    // Aim for ~K/2 near-square cells, using the tall aspect ratio so cells
    // stack vertically (few columns, many rows) instead of becoming slivers.
    int cells = (int)Math.ceil(K / 2.0);
    int nCols = Math.max(1, Math.round((float)Math.sqrt(cells * W / H)));
    int nRows = (int)Math.ceil((double)cells / nCols);
    while (2 * nCols * nRows < K)
      nRows++;
    float cw = W / nCols;
    float ch = H / nRows;
    int idx = 0;
    for (int r = 0; r < nRows; r++)
    {
      for (int c = 0; c < nCols; c++)
      {
        float lx = x0 + c * cw, ty = y0 + r * ch;
        float rx = lx + cw, by = ty + ch;
        KeyboardData.Key kA = idx < K ? keys.get(idx) : null; idx++;
        KeyboardData.Key kB = idx < K ? keys.get(idx) : null; idx++;
        if (((r + c) & 1) == 0)
        {
          // Diagonal top-left to bottom-right.
          _split_keys.add(new TriKey(kA, lx, ty, rx, ty, rx, by));
          _split_keys.add(new TriKey(kB, lx, ty, rx, by, lx, by));
        }
        else
        {
          // Diagonal top-right to bottom-left.
          _split_keys.add(new TriKey(kA, lx, ty, rx, ty, lx, by));
          _split_keys.add(new TriKey(kB, rx, ty, rx, by, lx, by));
        }
      }
    }
  }

  /** Draw the triangle keys of both halves. */
  private void drawSplitTriangles(Canvas canvas)
  {
    for (TriKey tk : _split_keys)
    {
      boolean down = tk.key != null && _pointers.isKeyDown(tk.key);
      Theme.Computed.Key tc_key = down ? _tc.key_activated : _tc.key;
      _tri_path.reset();
      _tri_path.moveTo(tk.xs[0], tk.ys[0]);
      _tri_path.lineTo(tk.xs[1], tk.ys[1]);
      _tri_path.lineTo(tk.xs[2], tk.ys[2]);
      _tri_path.close();
      canvas.drawPath(_tri_path, tc_key.bg_paint);
      canvas.drawPath(_tri_path, _tri_border_paint);
      if (tk.key != null && tk.key.keys[0] != null)
      {
        KeyValue kv = modifyKey(tk.key.keys[0], _mods);
        if (kv != null)
        {
          Paint p = tc_key.label_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT),
              labelColor(kv, down, false), _mainLabelSize);
          p.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(kv.getString(), tk.cx,
              tk.cy - (p.ascent() + p.descent()) / 2f, p);
        }
      }
    }
  }

  /** Draw the center type-test panel: a faint rounded panel showing the
      characters typed so far (or a hint when empty). */
  private void drawCenterTestArea(Canvas canvas)
  {
    if (_center_rect.width() <= 0f)
      return;
    float pad = _center_rect.width() * 0.06f;
    _test_bg_paint.setColor(_theme.colorKeyActivated);
    _test_bg_paint.setAlpha(60);
    float r = Math.min(40f, _center_rect.width() / 12f);
    canvas.drawRoundRect(_center_rect, r, r, _test_bg_paint);
    float cx = _center_rect.centerX();
    if (_test_text.length() == 0)
    {
      _test_hint_paint.setColor(_theme.subLabelColor);
      _test_hint_paint.setTextAlign(Paint.Align.CENTER);
      _test_hint_paint.setTextSize(Math.min(_center_rect.height() / 12f, _mainLabelSize));
      canvas.drawText("type-test", cx,
          _center_rect.centerY() - (_test_hint_paint.ascent() + _test_hint_paint.descent()) / 2f,
          _test_hint_paint);
      return;
    }
    _test_paint.setColor(_theme.labelColor);
    _test_paint.setTextAlign(Paint.Align.LEFT);
    _test_paint.setTextSize(Math.min(_center_rect.height() / 9f, _mainLabelSize * 1.2f));
    // Show the tail of the buffer that fits on one line, with a caret.
    float maxW = _center_rect.width() - pad * 2f;
    String full = _test_text.toString() + "|";
    int start = 0;
    while (start < full.length()
        && _test_paint.measureText(full, start, full.length()) > maxW)
      start++;
    String shown = full.substring(start);
    float ty = _center_rect.centerY() - (_test_paint.ascent() + _test_paint.descent()) / 2f;
    canvas.drawText(shown, _center_rect.left + pad, ty, _test_paint);
  }

  /** Append a typed key to the center type-test buffer (split mode only). */
  private void test_capture(KeyValue k)
  {
    if (k == null)
      return;
    switch (k.getKind())
    {
      case Char:
        _test_text.append(k.getChar());
        break;
      case String:
        _test_text.append(k.getString());
        break;
      case Editing:
        if (k.getEditing() == KeyValue.Editing.BACKSPACE)
        {
          if (_test_text.length() > 0)
            _test_text.deleteCharAt(_test_text.length() - 1);
        }
        else if (k.getEditing() == KeyValue.Editing.SPACE_BAR)
          _test_text.append(' ');
        break;
      default:
        break;
    }
  }

  @Override
  public void onDetachedFromWindow()
  {
    super.onDetachedFromWindow();
  }

  /** Draw borders and background of the key. */
  void drawKeyFrame(Canvas canvas, float x, float y, float keyW, float keyH,
      Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    float w = tc.border_width;
    float padding = w / 2.f;
    _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding);
    canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint);
    if (w > 0.f)
    {
      float overlap = r - r * 0.85f + w; // sin(45°)
      drawBorder(canvas, x, y, x + overlap, y + keyH, tc.border_left_paint, tc);
      drawBorder(canvas, x + keyW - overlap, y, x + keyW, y + keyH, tc.border_right_paint, tc);
      drawBorder(canvas, x, y, x + keyW, y + overlap, tc.border_top_paint, tc);
      drawBorder(canvas, x, y + keyH - overlap, x + keyW, y + keyH, tc.border_bottom_paint, tc);
    }
  }

  /** Clip to draw a border at a time. This allows to call [drawRoundRect]
      several time with the same parameters but a different Paint. */
  void drawBorder(Canvas canvas, float clipl, float clipt, float clipr,
      float clipb, Paint paint, Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    canvas.save();
    canvas.clipRect(clipl, clipt, clipr, clipb);
    canvas.drawRoundRect(_tmpRect, r, r, paint);
    canvas.restore();
  }

  private int labelColor(KeyValue k, boolean isKeyDown, boolean sublabel)
  {
    if (isKeyDown)
    {
      int flags = _pointers.getKeyFlags(k);
      if (flags != -1)
      {
        if ((flags & Pointers.FLAG_P_LOCKED) != 0)
          return _theme.lockedColor;
        return _theme.activatedColor;
      }
      return _theme.pressedColor;
    }
    if (k.hasFlagsAny(KeyValue.FLAG_SECONDARY | KeyValue.FLAG_GREYED))
    {
      if (k.hasFlagsAny(KeyValue.FLAG_GREYED))
        return _theme.greyedLabelColor;
      return _theme.secondaryLabelColor;
    }
    return sublabel ? _theme.subLabelColor : _theme.labelColor;
  }

  private void drawLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyH, boolean isKeyDown, Theme.Computed.Key tc)
  {
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, true);
    Paint p = tc.label_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(kv, isKeyDown, false), textSize);
    canvas.drawText(kv.getString(), x, (keyH - p.ascent() - p.descent()) / 2f + y, p);
  }

  private void drawSubLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyW, float keyH, int sub_index, boolean isKeyDown,
      Theme.Computed.Key tc)
  {
    Paint.Align a = LABEL_POSITION_H[sub_index];
    Vertical v = LABEL_POSITION_V[sub_index];
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, false);
    Paint p = tc.sublabel_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(kv, isKeyDown, true), textSize, a);
    float subPadding = _config.keyPadding;
    if (v == Vertical.CENTER)
      y += (keyH - p.ascent() - p.descent()) / 2f;
    else
      y += (v == Vertical.TOP) ? subPadding - p.ascent() : keyH - subPadding - p.descent();
    if (a == Paint.Align.CENTER)
      x += keyW / 2f;
    else
      x += (a == Paint.Align.LEFT) ? subPadding : keyW - subPadding;
    String label = kv.getString();
    int label_len = label.length();
    // Limit the label of string keys to 3 characters
    if (label_len > 3 && kv.getKind() == KeyValue.Kind.String)
      label_len = 3;
    canvas.drawText(label, 0, label_len, x, y, p);
  }

  private void drawIndication(Canvas canvas, KeyboardData.Key k, float x,
      float y, float keyW, float keyH, Theme.Computed tc)
  {
    if (k.indication == null || k.indication.equals(""))
      return;
    Paint p = tc.indication_paint;
    p.setTextSize(_subLabelSize);
    canvas.drawText(k.indication, 0, k.indication.length(),
        x + keyW / 2f, (keyH - p.ascent() - p.descent()) * 4/5 + y, p);
  }

  private float scaleTextSize(KeyValue k, boolean main_label)
  {
    float smaller_font = k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) ? 0.75f : 1.f;
    float label_size = main_label ? _mainLabelSize : _subLabelSize;
    return label_size * smaller_font;
  }
}
