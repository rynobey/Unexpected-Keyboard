package juloo.keyboard2;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
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
  /** The center rectangle, recomputed in [onMeasure] when split.
      Width is driven by _config.split_center_ratio (user-tunable). */
  private final RectF _center_rect = new RectF();
  /** The current centre (hole) rectangle in view-local coords, or null
      when not in split mode. Used by Keyboard2.onComputeInsets to declare
      the touchable region. */
  public RectF getCenterRect() { return _split ? _center_rect : null; }
  /** Top/bottom of the key area in split mode (leaves a blank strip above and
      below, the bottom one giving room for the system hide/globe controls). */
  private float _split_top = 0f;
  private float _split_bottom = 0f;
  /** The selfie-camera cutout on a side edge (view coords), if any. The row at
      it is grown taller and the obscured key's label is shifted clear. */
  private final RectF _cutout_rect = new RectF();
  private boolean _has_cutout = false;
  private boolean _cutout_left = false;
  /** The triangle (and occasional rectangle) keys of both halves, rebuilt in
      [onMeasure] when split. */
  private final ArrayList<TriKey> _split_keys = new ArrayList<TriKey>();
  private final Path _tri_path = new Path();
  /** One label size shared by all split keys, so letters are consistent. */
  private float _split_label_size = 0f;
  /** Gap (px) each key is inset from its cell, and rounded-corner effect. */
  private float _split_gap_px = 3f;
  private CornerPathEffect _round_effect = null;
  /** How far (fraction of cell width) the dividing diagonal between a cell's
      two keys is offset at top and bottom, so each key is a near-rectangular
      trapezoid (narrow side = this fraction of the cell width). */
  private static final float SPLIT_TRAP_F = 0.35f;
  /** Characters typed while in split mode, echoed in the center area. */
  private final StringBuilder _test_text = new StringBuilder();
  private final Paint _test_paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _test_hint_paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _test_bg_paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint _tri_border_paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  /** A key occupying a convex polygon (a triangle, or a quad once a screen
      corner is chamfered) in split mode. [key] may be null for an unused slot.
      Vertices are absolute view coordinates in order. [onEdge] marks vertices
      that lie on a physical screen edge (top, bottom, or the outer side) —
      swipe sub-labels are not placed there. */
  static final class TriKey
  {
    final KeyboardData.Key key;
    final float labelSize;
    final float top, bottom, outerX;
    float[] xs;
    float[] ys;
    boolean[] onEdge;
    float cx; // label anchor (centroid)
    float cy;
    TriKey(KeyboardData.Key k, float labelSize, float top, float bottom,
        float outerX, float... coords)
    {
      key = k;
      this.labelSize = labelSize;
      this.top = top;
      this.bottom = bottom;
      this.outerX = outerX;
      int n = coords.length / 2;
      xs = new float[n];
      ys = new float[n];
      for (int i = 0; i < n; i++)
      {
        xs[i] = coords[2 * i];
        ys[i] = coords[2 * i + 1];
      }
      recompute();
    }

    private void recompute()
    {
      int n = xs.length;
      float sx = 0f, sy = 0f;
      onEdge = new boolean[n];
      for (int i = 0; i < n; i++)
      {
        sx += xs[i];
        sy += ys[i];
        // Only the outer (left/right) side is a hard screen edge now; the
        // top/bottom have blank strips, so swipes there are reachable.
        onEdge[i] = near(xs[i], outerX);
      }
      cx = sx / n;
      cy = sy / n;
    }

    private static boolean near(float a, float b) { return Math.abs(a - b) < 1.5f; }

    /** Replace the vertex at (px,py) with a chamfer (slanted edge) of length
        [cr] cut across the corner, turning that corner into two vertices. */
    boolean chamfer(float px, float py, float cr)
    {
      int n = xs.length, i = -1;
      for (int j = 0; j < n; j++)
        if (near(xs[j], px) && near(ys[j], py)) { i = j; break; }
      if (i < 0)
        return false;
      int prev = (i - 1 + n) % n, next = (i + 1) % n;
      // Only bevel when the two edges at this corner run along the screen
      // edges (one vertical, one horizontal through the corner).
      boolean prevV = near(xs[prev], px), prevH = near(ys[prev], py);
      boolean nextV = near(xs[next], px), nextH = near(ys[next], py);
      if (!((prevV && nextH) || (prevH && nextV)))
        return false;
      float ax = chamferPt(xs[i], xs[prev], cr), ay = chamferPt(ys[i], ys[prev], cr);
      float bx = chamferPt(xs[i], xs[next], cr), by = chamferPt(ys[i], ys[next], cr);
      float[] nx = new float[n + 1], ny = new float[n + 1];
      int w = 0;
      for (int j = 0; j < n; j++)
      {
        if (j == i)
        {
          nx[w] = ax; ny[w++] = ay;
          nx[w] = bx; ny[w++] = by;
        }
        else
        {
          nx[w] = xs[j]; ny[w++] = ys[j];
        }
      }
      xs = nx; ys = ny;
      recompute();
      return true;
    }

    /** Point [cr] away from [from] toward [to], clamped to the midpoint. */
    private static float chamferPt(float from, float to, float cr)
    {
      float d = to - from;
      float len = Math.abs(d);
      float t = (len <= 0f) ? 0f : Math.min(cr, len * 0.5f) / len;
      return from + d * t;
    }

    /** Even-odd ray-cast point-in-polygon (handles triangle or quad). */
    boolean contains(float px, float py)
    {
      boolean in = false;
      int n = xs.length;
      for (int i = 0, j = n - 1; i < n; j = i++)
      {
        if (((ys[i] > py) != (ys[j] > py))
            && (px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]))
          in = !in;
      }
      return in;
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
    _split = _config.session_split && _config.orientation_landscape;
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
    _mainLabelSize = labelBaseSize * _config.labelTextSize * _config.main_label_scale;
    _subLabelSize = labelBaseSize * _config.sublabelTextSize * _config.sublabel_scale;
    int height =
      (int)(_tc.row_height * _keyboard.keysHeight
          + _config.marginTop + _marginBottom);
    if (_split)
    {
      float density = getContext().getResources().getDisplayMetrics().density;
      float origTop = density * 22f;            // small blank strip above
      float origBottom = height - density * 36f; // blank strip below for controls
      // Apply the height-ratio preference: shrink the key area
      // symmetrically (gaps grow above and below as ratio decreases).
      float origH = origBottom - origTop;
      float newH = origH * _config.split_height_ratio;
      float pad = (origH - newH) / 2f;
      // Vertical-offset preference: shift the key area up (positive) or
      // down (negative), clamped to the free space the height ratio left.
      float shift = origH * _config.split_vertical_offset;
      shift = Math.max(-pad, Math.min(pad, shift));
      _split_top = origTop + pad - shift;
      _split_bottom = origBottom - pad - shift;
      float gap = width * _config.split_center_ratio;
      _center_rect.left = (width - gap) / 2f;
      _center_rect.right = _center_rect.left + gap;
      _center_rect.top = _split_top;
      _center_rect.bottom = _split_bottom;
      buildSplitTriangles(width, height);
      // Notify the service so it can broadcast the hole rect to a
      // compatible app. Walk the ContextWrapper chain — when this view is
      // inflated via LayoutInflater (especially with AppCompat themes),
      // getContext() returns a ContextThemeWrapper around the IME service,
      // not the IME itself, so a bare `instanceof` cast fails silently and
      // no active=true broadcast ever fires.
      android.content.Context ctx = getContext();
      while (ctx instanceof android.content.ContextWrapper && !(ctx instanceof Keyboard2))
        ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
      if (ctx instanceof Keyboard2)
        ((Keyboard2) ctx).onSplitRectChanged(_center_rect, this);
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
      // No drawCenterTestArea — the hole must be fully transparent so the
      // compatible app behind shows through. (The test-area overlay was a
      // development-time placeholder before real receivers existed.)
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
      [onMeasure] in split mode. Each half extends to the physical screen edge
      (into the display cutout) on its outer side. */
  private void buildSplitTriangles(int viewW, int viewH)
  {
    _split_keys.clear();
    // Leave a blank strip above and below the keys.
    float top = _split_top;
    float bottom = _split_bottom;

    float density = getResources().getDisplayMetrics().density;
    _split_gap_px = density * 1.5f;
    _round_effect = new CornerPathEffect(density * 2f);
    compute_cutout(viewW);
    _tri_border_paint.setStyle(Paint.Style.STROKE);
    _tri_border_paint.setStrokeWidth(Math.max(2f, density * 1.5f));
    _tri_border_paint.setColor(_theme.colorKeyActivated);

    if ("columns".equals(_config.split_variant))
    {
      // Columnar variant: each original QWERTY row becomes a vertical
      // column for wider, thumb-friendlier keys. Bottom modifier strip
      // (ctrl/alt/space on left, space/enter on right) stays a horizontal
      // row at the bottom of each half.
      float bottomRowH = (bottom - top) * 0.18f; // ~1/5 of letter area
      float lettersBottom = bottom - bottomRowH;
      ArrayList<ArrayList<KeyboardData.Key>> leftCols = split_layout_left_columns();
      ArrayList<ArrayList<KeyboardData.Key>> rightCols = split_layout_right_columns();
      int maxColLen = 0;
      for (ArrayList<KeyboardData.Key> col : leftCols)  maxColLen = Math.max(maxColLen, col.size());
      for (ArrayList<KeyboardData.Key> col : rightCols) maxColLen = Math.max(maxColLen, col.size());
      _split_label_size = (lettersBottom - top) / Math.max(1, maxColLen) * 0.20f;
      tessellateColumns(0f, top, _center_rect.left, lettersBottom, 0f, leftCols);
      tessellateColumns(_center_rect.right, top, viewW, lettersBottom, viewW, rightCols);
      // Bottom modifier row, one per half, laid out as add_rect_row.
      add_rect_row(split_bottom_left(),  0f,  lettersBottom, _center_rect.left, bottom,
                   lettersBottom, bottom, top, bottom, 0f, true, false, true);
      add_rect_row(split_bottom_right(), _center_rect.right, lettersBottom, viewW, bottom,
                   lettersBottom, bottom, top, bottom, viewW, false, false, true);
      return;
    }

    // Original "rows" variant.
    ArrayList<ArrayList<KeyboardData.Key>> leftRows = split_layout_left();
    ArrayList<ArrayList<KeyboardData.Key>> rightRows = split_layout_right();
    int rowCount = Math.max(leftRows.size(), rightRows.size());
    _split_label_size = (bottom - top) / Math.max(1, rowCount) * 0.20f;
    // Outer side fills to the screen edge (0 on the left, viewW on the right).
    tessellateRows(0f, top, _center_rect.left, bottom, 0f, leftRows);
    tessellateRows(_center_rect.right, top, viewW, bottom, viewW, rightRows);
    // No screen-corner bevel needed: the blank top/bottom strips keep the keys
    // away from the rounded screen corners.
  }

  /** Radius (px) of the device's rounded corners, with a sensible fallback. */
  private float corner_radius_px()
  {
    float fallback = getResources().getDisplayMetrics().density * 20f;
    if (VERSION.SDK_INT >= 31)
    {
      WindowInsets wi = getRootWindowInsets();
      if (wi != null)
      {
        android.view.RoundedCorner rc =
          wi.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT);
        if (rc != null && rc.getRadius() > 0)
          return rc.getRadius();
      }
    }
    return fallback;
  }

  private void chamfer_corner(float px, float py, float cr)
  {
    for (TriKey tk : _split_keys)
      if (tk.chamfer(px, py, cr))
        return;
  }

  /** Find a selfie-camera cutout sitting on a left/right edge, in view coords. */
  private void compute_cutout(int viewW)
  {
    _has_cutout = false;
    _cutout_rect.setEmpty();
    // Disabled by preference: keys keep their uniform size and labels stay
    // centered, even under the lens.
    if (!_config.split_cutout_adapt)
      return;
    if (VERSION.SDK_INT < 28)
      return;
    WindowInsets wi = getRootWindowInsets();
    if (wi == null)
      return;
    android.view.DisplayCutout dc = wi.getDisplayCutout();
    if (dc == null)
      return;
    for (Rect r : dc.getBoundingRects())
    {
      boolean leftEdge = r.left <= 2;
      boolean rightEdge = r.right >= viewW - 2;
      if (leftEdge || rightEdge)
      {
        _cutout_rect.set(r.left, r.top, r.right, r.bottom);
        _cutout_left = leftEdge;
        _has_cutout = true;
        return;
      }
    }
  }

  private KeyboardData.Key make_space()
  {
    return KeyboardData.Key.EMPTY.withKeyValue(0, KeyValue.getKeyByName("space"));
  }

  private static boolean is_space(KeyboardData.Key k)
  {
    return k.keys[0] != null && k.keys[0].getKind() == KeyValue.Kind.Editing
      && k.keys[0].getEditing() == KeyValue.Editing.SPACE_BAR;
  }

  // Swipe-direction slots: 1=NW 2=NE 3=SW 4=SE 5=W 6=E 7=N 8=S.

  /** Build a key for [name], reusing the key from the active layout (so its
      standard swipe sub-keys are preserved) only when [name] is that key's
      center value. Otherwise (e.g. a digit that only exists as a swipe on a
      letter) build a bare key so it shows as itself. */
  private KeyboardData.Key sk(String name)
  {
    KeyValue kv = KeyValue.getKeyByName(name);
    if (_keyboard != null)
    {
      KeyboardData.Key k = _keyboard.findKeyWithValue(kv);
      if (k != null && k.keys[0] != null && k.keys[0].sameKey(kv))
        return strip_moved_swipes(k);
    }
    return KeyboardData.Key.EMPTY.withKeyValue(0, kv);
  }

  /** Symbols moved onto the number row (US shift+number), removed from the
      letter keys they sit on in the base layout. */
  private static final String NUMBER_SYMBOLS = "!@#$%^&*()";

  /** Remove digit swipes (we have a number row) and the shift-number symbols
      (moved onto the number keys), keeping other symbols like ~. */
  private KeyboardData.Key strip_moved_swipes(KeyboardData.Key k)
  {
    for (int i = 1; i < k.keys.length; i++)
    {
      KeyValue kv = k.keys[i];
      if (kv == null || kv.getKind() != KeyValue.Kind.Char)
        continue;
      char c = kv.getChar();
      if (Character.isDigit(c) || NUMBER_SYMBOLS.indexOf(c) >= 0)
        k = k.withKeyValue(i, null);
    }
    return k;
  }

  /** A number key with its shift-symbol on a corner swipe [dir]. */
  private KeyboardData.Key numkey(String digit, char symbol, int dir)
  {
    return sk(digit).withKeyValue(dir, KeyValue.makeCharKey(symbol));
  }

  /** Build a key with an added swipe modifier in direction [dir]. */
  private KeyboardData.Key sk(String name, int dir, String swipe)
  {
    return sk(name).withKeyValue(dir, KeyValue.getKeyByName(swipe));
  }

  private ArrayList<KeyboardData.Key> mkrow(KeyboardData.Key... ks)
  {
    ArrayList<KeyboardData.Key> r = new ArrayList<KeyboardData.Key>();
    for (KeyboardData.Key k : ks)
      r.add(k);
    return r;
  }

  /** An arrows key: tap = left, swipe for the other directions. */
  private KeyboardData.Key arrows_key()
  {
    return KeyboardData.Key.EMPTY
      .withKeyValue(0, KeyValue.getKeyByName("left"))
      .withKeyValue(6, KeyValue.getKeyByName("right")) // E
      .withKeyValue(7, KeyValue.getKeyByName("up"))    // N
      .withKeyValue(8, KeyValue.getKeyByName("down")); // S
  }

  /** Left half of the dedicated QWERTY split layout. esc/tab/fn/enter are
      inward (toward center = E) swipe modifiers. */
  private ArrayList<ArrayList<KeyboardData.Key>> split_layout_left()
  {
    ArrayList<ArrayList<KeyboardData.Key>> rows =
      new ArrayList<ArrayList<KeyboardData.Key>>();
    // Symbols on se (lower-inner corner); esc moves down to q.
    rows.add(mkrow(
          numkey("1", '!', 4), numkey("2", '@', 4), numkey("3", '#', 4),
          numkey("4", '$', 4), numkey("5", '%', 4)));
    rows.add(mkrow(
          // ESC on N (swipe up) — was on NE, but NE on a left-edge key
          // collides with the back gesture. The base layout's SE=esc gets
          // stripped automatically by strip_outward for edge keys.
          sk("q").withKeyValue(7, KeyValue.getKeyByName("esc")),
          sk("w").withKeyValue(1, KeyValue.makeCharKey('~')),
          sk("e"), sk("r"), sk("t")));
    rows.add(mkrow(sk("a"), sk("s"), sk("d"), sk("f"), sk("g")));
    rows.add(mkrow(
          // Tab on N (swipe up) — same reasoning as ESC on Q.
          sk("shift").withKeyValue(7, KeyValue.getKeyByName("tab")),
          sk("z").withKeyValue(3, KeyValue.makeCharKey('\\')),
          sk("x"), sk("c"), sk("v")));
    rows.add(mkrow(
          sk("ctrl", 2, "fn").withKeyValue(4, null), // drop inherited switch_numeric
          sk("alt").withKeyValue(1, KeyValue.getKeyByName("config"))
                   .withKeyValue(2, KeyValue.getKeyByName("change_method")),
          sk("space").withKeyValue(2, KeyValue.getKeyByName("switch_numeric"))));
    return rows;
  }

  /** Right half of the dedicated QWERTY split layout. */
  private ArrayList<ArrayList<KeyboardData.Key>> split_layout_right()
  {
    ArrayList<ArrayList<KeyboardData.Key>> rows =
      new ArrayList<ArrayList<KeyboardData.Key>>();
    // Symbols on sw (lower-inner corner of the right half).
    rows.add(mkrow(
          numkey("6", '^', 3), numkey("7", '&', 3), numkey("8", '*', 3),
          numkey("9", '(', 3), numkey("0", ')', 3)));
    rows.add(mkrow(sk("y"), sk("u"), sk("i"), sk("o"), sk("p")));
    rows.add(mkrow(sk("h"), sk("j"),
          sk("k").withKeyValue(3, KeyValue.makeCharKey('['))
                 .withKeyValue(4, KeyValue.makeCharKey(']')),
          // L is on the right edge — strip_outward removes the base layout's
          // NE=| and SW=\ for us, so no manual modifiers needed.
          sk("l")));
    rows.add(mkrow(sk("b"), sk("n"),
          // M: ' on NE (moved from SW), . on SW so the full stop is still
          // reachable from M (it's gone everywhere else in the split row).
          sk("m").withKeyValue(2, KeyValue.makeCharKey('\''))
                 .withKeyValue(3, KeyValue.makeCharKey('.')),
          sk("backspace").withKeyValue(1, KeyValue.getKeyByName("delete"))));
    // Right space bar: discrete arrow keys (DPAD) on all four swipe edges,
    // instead of the cursor slider (which the left space bar keeps).
    rows.add(mkrow(
          sk("space").withKeyValue(5, KeyValue.getKeyByName("left"))
                     .withKeyValue(6, KeyValue.getKeyByName("right"))
                     .withKeyValue(7, KeyValue.getKeyByName("up"))
                     .withKeyValue(8, KeyValue.getKeyByName("down")),
          sk("enter").withKeyValue(1, KeyValue.getKeyByName("action").withSymbol("act"))));
    return rows;
  }

  // ───────────────────────────── columns variant ─────────────────────────────

  /** Move slot [from] to slot [to]. If [to] is occupied, swap the two
      so the existing value isn't lost. No-op if [from] is empty. */
  private KeyboardData.Key move_or_swap(KeyboardData.Key k, int from, int to)
  {
    KeyValue v = k.keys[from];
    if (v == null) return k;
    KeyValue existing = k.keys[to];
    k = k.withKeyValue(to, v);
    k = k.withKeyValue(from, existing);
    return k;
  }

  /** Like [sk] but keeps the base layout's full swipe set including
      digit/shift-number-symbol swipes — used by the columns variant
      where there's no separate number row to take those. */
  private KeyboardData.Key skd(String name)
  {
    KeyValue kv = KeyValue.getKeyByName(name);
    if (_keyboard != null)
    {
      KeyboardData.Key k = _keyboard.findKeyWithValue(kv);
      if (k != null && k.keys[0] != null && k.keys[0].sameKey(kv))
        return k;
    }
    return KeyboardData.Key.EMPTY.withKeyValue(0, kv);
  }

  /** Left half — columnar variant. 3 columns × 5 keys; Shift moved from
      the top of the outer column to the bottom of the outer column (its
      former top-of-column slot is taken by Z so Z..V slide up). The
      bottom row is replaced with a dedicated Tab + Space pair. */
  private ArrayList<ArrayList<KeyboardData.Key>> split_layout_left_columns()
  {
    ArrayList<ArrayList<KeyboardData.Key>> cols =
        new ArrayList<ArrayList<KeyboardData.Key>>();
    // Outer column (left screen edge): z, x, c, v, shift. ESC lives only
    // on X's N now (was on Z's S — moved).
    cols.add(mkrow(
          skd("z").withKeyValue(7, KeyValue.makeCharKey('\\')),
          skd("x").withKeyValue(2, null) // clear base loc † on NE
                  .withKeyValue(7, KeyValue.getKeyByName("esc")),
          skd("c"),
          skd("v"),
          skd("shift")));
    // Middle column: a, s, d, f, g. Tab removed from A's NW (dedicated
    // Tab key in the bottom row now).
    cols.add(mkrow(
          skd("a").withKeyValue(1, null),
          skd("s"),
          skd("d"),
          skd("f"),
          skd("g")));
    // Inner column: q, w, e, r, t. Digits moved NE→SE on each. E already
    // had loc € on SE — swap so € is preserved (moves to NE). Q's base
    // SE=loc esc cleared first so the swap doesn't carry it.
    cols.add(mkrow(
          move_or_swap(skd("q").withKeyValue(4, null), 2, 4),
          move_or_swap(skd("w"), 2, 4),
          move_or_swap(skd("e"), 2, 4),
          move_or_swap(skd("r"), 2, 4),
          move_or_swap(skd("t"), 2, 4)));
    return cols;
  }

  /** Right half — columnar variant. 3 columns × 5 keys. Backspace moved
      off the top of the outer column down into the new bottom slot,
      with Ctrl below it. Alt is added at the bottom of the middle column.
      The 5-key columns put the camera cutout on a key boundary so no
      key needs to grow to clear it. */
  private ArrayList<ArrayList<KeyboardData.Key>> split_layout_right_columns()
  {
    ArrayList<ArrayList<KeyboardData.Key>> cols =
        new ArrayList<ArrayList<KeyboardData.Key>>();
    // Inner column (closest to centre hole): p, o, i, u, y. Digits moved
    // NE→SW on each. O/I/U/Y already had a symbol on SW (parens, asterisk,
    // ampersand, caret); swap so those are preserved on NE.
    cols.add(mkrow(
          move_or_swap(skd("p"), 2, 3),
          move_or_swap(skd("o"), 2, 3),
          move_or_swap(skd("i"), 2, 3),
          move_or_swap(skd("u"), 2, 3),
          move_or_swap(skd("y"), 2, 3)));
    // Middle column: l, k, j, h, alt
    cols.add(mkrow(
          skd("l"),
          skd("k").withKeyValue(3, KeyValue.makeCharKey('['))
                  .withKeyValue(4, KeyValue.makeCharKey(']')),
          skd("j"),
          skd("h"),
          sk("alt").withKeyValue(1, KeyValue.getKeyByName("config"))
                   .withKeyValue(2, KeyValue.getKeyByName("change_method"))));
    // Outer column (right screen edge): m, n, b, backspace, ctrl
    cols.add(mkrow(
          skd("m").withKeyValue(2, KeyValue.makeCharKey('\''))
                  .withKeyValue(3, KeyValue.makeCharKey('.')),
          skd("n"),
          skd("b"),
          sk("backspace").withKeyValue(1, KeyValue.getKeyByName("delete")),
          sk("ctrl", 2, "fn").withKeyValue(4, null)));
    return cols;
  }

  /** Bottom strip — left half. A dedicated Tab key plus Space (with
      switch_numeric on NE). Replaces the previous Ctrl+Alt+Space. */
  private ArrayList<KeyboardData.Key> split_bottom_left()
  {
    return mkrow(
        sk("tab"),
        sk("space").withKeyValue(2, KeyValue.getKeyByName("switch_numeric")));
  }

  /** Bottom modifier strip — right half. */
  private ArrayList<KeyboardData.Key> split_bottom_right()
  {
    return mkrow(
        sk("space").withKeyValue(5, KeyValue.getKeyByName("left"))
                   .withKeyValue(6, KeyValue.getKeyByName("right"))
                   .withKeyValue(7, KeyValue.getKeyByName("up"))
                   .withKeyValue(8, KeyValue.getKeyByName("down")),
        sk("enter").withKeyValue(1, KeyValue.getKeyByName("action").withSymbol("act")));
  }

  /** Tessellate [columns] as vertical strips filling [x0,y0,x1,y1].
      Each column is one strip of equal width; its keys are rectangles
      spaced evenly down its height. The outer column (touching the
      screen edge) gets the cutout-grow treatment for the key sitting at
      the cutout's y. */
  private void tessellateColumns(float x0, float y0, float x1, float y1,
      float outerX, ArrayList<ArrayList<KeyboardData.Key>> columns)
  {
    int C = columns.size();
    if (C <= 0 || x1 <= x0 || y1 <= y0)
      return;
    float colW = (x1 - x0) / C;
    boolean left_half = outerX <= 0f;
    for (int ci = 0; ci < C; ci++)
    {
      ArrayList<KeyboardData.Key> col = columns.get(ci);
      int K = col.size();
      if (K <= 0)
        continue;
      float cx0 = x0 + ci * colW;
      float cx1 = cx0 + colW;
      boolean outerCol = left_half ? (ci == 0) : (ci == C - 1);
      float keyH = (y1 - y0) / K;
      float[] bnd = new float[K + 1];
      for (int i = 0; i <= K; i++) bnd[i] = y0 + i * keyH;
      float[] obnd = bnd.clone();
      if (outerCol && _has_cutout && _cutout_left == left_half)
      {
        int k = (int)((_cutout_rect.centerY() - y0) / keyH);
        if (k >= 0 && k < K)
        {
          float g = keyH * 0.15f, minH = keyH * 0.55f;
          if (k > 0)
            obnd[k]     = Math.max(bnd[k - 1] + minH, bnd[k] - g);
          if (k + 1 < K)
            obnd[k + 1] = Math.min(bnd[k + 2] - minH, bnd[k + 1] + g);
        }
      }
      for (int ki = 0; ki < K; ki++)
      {
        boolean L = outerCol && left_half;
        boolean R = outerCol && !left_half;
        boolean T = (ki == 0);
        boolean B = (ki == K - 1);
        float ky0 = outerCol ? obnd[ki]     : bnd[ki];
        float ky1 = outerCol ? obnd[ki + 1] : bnd[ki + 1];
        add_split_rect(col.get(ki), cx0, ky0, cx1, ky1, y0, y1, outerX, L, R, T, B);
      }
    }
  }

  /** Lay out [rows] as horizontal bands (top to bottom) filling [x0,y0,x1,y1].
      Each band packs its keys left to right as diagonal-split triangle pairs,
      alternating the diagonal so the keys interleave. [outerX] is the physical
      screen edge of this half. */
  private void tessellateRows(float x0, float y0, float x1, float y1,
      float outerX, ArrayList<ArrayList<KeyboardData.Key>> rows)
  {
    int R = rows.size();
    if (R <= 0 || x1 <= x0 || y1 <= y0)
      return;
    float W = x1 - x0;
    float bandH = (y1 - y0) / R;
    boolean left_half = outerX <= 0f;
    // Uniform band boundaries for the inner keys.
    float[] bnd = new float[R + 1];
    for (int i = 0; i <= R; i++)
      bnd[i] = y0 + i * bandH;
    // Outer-column boundaries: same as uniform, except at the camera cutout
    // where the lens key grows symmetrically into BOTH neighbours (up and
    // down by the same amount) so it's easier to press. Other keys (incl.
    // all inner keys) are unchanged.
    float[] obnd = bnd.clone();
    if (_has_cutout && _cutout_left == left_half)
    {
      int k = (int)((_cutout_rect.centerY() - y0) / bandH);
      if (k >= 0 && k < R)
      {
        float g = bandH * 0.15f, minH = bandH * 0.55f;
        if (k > 0)
          obnd[k]     = Math.max(bnd[k - 1] + minH, bnd[k]     - g);
        if (k + 1 < R)
          obnd[k + 1] = Math.min(bnd[k + 2] - minH, bnd[k + 1] + g);
      }
    }
    for (int ri = 0; ri < R; ri++)
    {
      ArrayList<KeyboardData.Key> keys = rows.get(ri);
      int n = keys.size();
      if (n <= 0)
        continue;
      float by0 = bnd[ri];
      float by1 = bnd[ri + 1];
      float oby0 = obnd[ri];
      float oby1 = obnd[ri + 1];
      boolean top_edge = (ri == 0);
      boolean bot_edge = (ri == R - 1);
      // The number row (top) is rendered as rectangles so corner symbols are
      // always visible; other 5+ key rows get a 1/4-width outer rectangle and
      // interleaved triangles.
      if (n >= 5 && !top_edge)
      {
        float rectW = W * 0.25f;
        if (left_half)
        {
          add_split_rect(keys.get(0), x0, oby0, x0 + rectW, oby1, y0, y1, outerX, true, false, top_edge, bot_edge);
          tess_triangles(keys, 1, n, x0 + rectW, by0, x1, by1, y0, y1, outerX, true, top_edge, bot_edge);
        }
        else
        {
          add_split_rect(keys.get(n - 1), x1 - rectW, oby0, x1, oby1, y0, y1, outerX, false, true, top_edge, bot_edge);
          tess_triangles(keys, 0, n - 1, x0, by0, x1 - rectW, by1, y0, y1, outerX, false, top_edge, bot_edge);
        }
      }
      else
      {
        add_rect_row(keys, x0, by0, x1, by1, oby0, oby1, y0, y1, outerX, left_half, top_edge, bot_edge);
      }
    }
  }

  /** Apply the user's per-key swipe-direction overrides (Settings →
      custom swipe keys). Matched by the key's center value; overrides win
      over the built-in split swipe assignments. The REMOVED placeholder
      clears a slot. Applied before [strip_outward] so edge-key promotion
      still works on the customized swipes. */
  private KeyboardData.Key apply_custom_swipes(KeyboardData.Key k)
  {
    if (k == null || k.keys[0] == null || _config.custom_swipes.isEmpty())
      return k;
    KeyValue[] ov = _config.custom_swipes.get(k.keys[0]);
    if (ov == null)
      return k;
    for (int i = 1; i < 9; i++)
    {
      KeyValue v = ov[i];
      if (v == null)
        continue;
      boolean clear = v.getKind() == KeyValue.Kind.Placeholder
        && v.getPlaceholder() == KeyValue.Placeholder.REMOVED;
      k = k.withKeyValue(i, clear ? null : v);
    }
    return k;
  }

  /** For keys on a physical L/R screen edge, consolidate the corner/
      horizontal swipes onto N (slot 7) and S (slot 8) instead of just
      dropping them. NW/NE swipes promote to N (with NE preferred), SW/SE
      promote to S (SW preferred). Pre-existing N/S take priority over a
      promoted corner. This way edge keys keep ALL their secondaries
      reachable — comma on V, full stop on C/M, esc on Q, etc. — without
      starting any swipe from a screen edge (which would collide with
      Android's back gesture). W/E have no vertical home and are dropped.
      [L/R/T/B] = adjacent to left/right/top/bottom edge. */
  private KeyboardData.Key strip_outward(KeyboardData.Key k, boolean L,
      boolean R, boolean T, boolean B)
  {
    if (!(L || R)) return k;
    KeyValue keep_n = k.keys[7];
    if (keep_n == null) keep_n = k.keys[2];   // NE
    if (keep_n == null) keep_n = k.keys[1];   // NW
    KeyValue keep_s = k.keys[8];
    if (keep_s == null) keep_s = k.keys[3];   // SW
    if (keep_s == null) keep_s = k.keys[4];   // SE
    // Avoid duplicating the same KV on both directions (e.g. Q with
    // N=esc set manually AND base SE=esc would otherwise promote esc
    // onto S as well).
    if (keep_n != null && keep_s != null && keep_s.sameKey(keep_n))
      keep_s = null;
    for (int i = 1; i <= 6; i++)
      if (k.keys[i] != null) k = k.withKeyValue(i, null);
    if (keep_n != null && k.keys[7] == null) k = k.withKeyValue(7, keep_n);
    if (keep_s != null && k.keys[8] == null) k = k.withKeyValue(8, keep_s);
    return k;
  }

  private void add_split_rect(KeyboardData.Key k, float lx, float by0,
      float rx, float by1, float top, float bottom, float outerX,
      boolean L, boolean R, boolean T, boolean B)
  {
    if (k == null)
      return;
    k = strip_outward(apply_custom_swipes(k), L, R, T, B);
    _split_keys.add(new TriKey(k, _split_label_size, top, bottom, outerX,
          lx, by0, rx, by0, rx, by1, lx, by1));
  }

  /** Lay [keys] left to right as rectangles, with the space bar given extra
      width. */
  private void add_rect_row(ArrayList<KeyboardData.Key> keys, float x0,
      float by0, float x1, float by1, float oby0, float oby1, float top,
      float bottom, float outerX, boolean left_half, boolean T, boolean B)
  {
    int n = keys.size();
    float total = 0f;
    float[] w = new float[n];
    for (int i = 0; i < n; i++)
    {
      // Space bar is wider than other keys, but reduced from the original
      // 2.5x so the modifier/enter keys next to it grow into more space.
      w[i] = is_space(keys.get(i)) ? 2.0f : 1f;
      total += w[i];
    }
    float cx = x0;
    for (int i = 0; i < n; i++)
    {
      float ww = (x1 - x0) * w[i] / total;
      boolean L = left_half && i == 0;
      boolean R = !left_half && i == n - 1;
      // The outer (edge) key uses the possibly-extended camera bounds.
      float ky0 = (L || R) ? oby0 : by0;
      float ky1 = (L || R) ? oby1 : by1;
      add_split_rect(keys.get(i), cx, ky0, cx + ww, ky1, top, bottom, outerX, L, R, T, B);
      cx += ww;
    }
  }

  /** Tessellate keys [from,to) into the rectangle as triangle pairs. All
      hypotenuses lean the same way per half: "/" on the left, "\" on the
      right. Empty slots are skipped (not drawn). */
  private void tess_triangles(ArrayList<KeyboardData.Key> keys, int from,
      int to, float lx0, float by0, float rx0, float by1, float top,
      float bottom, float outerX, boolean left_half, boolean T, boolean B)
  {
    int m = to - from;
    if (m <= 0 || rx0 <= lx0)
      return;
    int nCells = (int)Math.ceil(m / 2.0);
    float cw = (rx0 - lx0) / nCells;
    float labelSize = _split_label_size;
    int idx = from;
    for (int c = 0; c < nCells; c++)
    {
      float lx = lx0 + c * cw, rx = lx + cw;
      KeyboardData.Key kA = idx < to ? keys.get(idx) : null; idx++;
      KeyboardData.Key kB = idx < to ? keys.get(idx) : null; idx++;
      if (kA != null) kA = strip_outward(apply_custom_swipes(kA), false, false, T, B);
      if (kB != null) kB = strip_outward(apply_custom_swipes(kB), false, false, T, B);
      // The cell's two keys are interlocking trapezoids: the dividing diagonal
      // is offset by [d] at top and bottom so neither key is pointed. Each key
      // is near-rectangular with one horizontal side fatter than the other.
      float d = (rx - lx) * SPLIT_TRAP_F;
      if (left_half)
      {
        // "/" divider. Upper-left key: fat top, narrow bottom.
        if (kA != null)
          _split_keys.add(new TriKey(kA, labelSize, top, bottom, outerX, lx, by0, rx - d, by0, lx + d, by1, lx, by1));
        // Lower-right key: narrow top, fat bottom.
        if (kB != null)
          _split_keys.add(new TriKey(kB, labelSize, top, bottom, outerX, rx - d, by0, rx, by0, rx, by1, lx + d, by1));
      }
      else
      {
        // "\" divider. First key in the list is visually-leftmost, so it
        // gets the lower-left polygon (fat bottom, narrow top). Second
        // key gets the upper-right polygon. Without this swap the right
        // half reads as u,y,o,i instead of y,u,i,o.
        if (kA != null)
          _split_keys.add(new TriKey(kA, labelSize, top, bottom, outerX, lx, by0, lx + d, by0, rx - d, by1, lx, by1));
        if (kB != null)
          _split_keys.add(new TriKey(kB, labelSize, top, bottom, outerX, lx + d, by0, rx, by0, rx, by1, rx - d, by1));
      }
    }
  }

  /** Build the rendered (inset, rounded) path for a key. Triangles are first
      truncated at their pointed tip into a trapezoid. Hit-testing still uses
      the full polygon, so the visible key is a bit smaller than its touch
      area. */
  private void build_key_path(Path p, TriKey tk)
  {
    // The key polygon is already a trapezoid/rectangle; just inset it.
    inset_path(p, tk.xs, tk.ys);
  }

  /** Build [p] from the polygon, with every vertex pulled toward the centroid
      by [_split_gap_px] so adjacent keys show a small gap. */
  private void inset_path(Path p, float[] xs, float[] ys)
  {
    int n = xs.length;
    float cx = 0f, cy = 0f;
    for (int i = 0; i < n; i++) { cx += xs[i]; cy += ys[i]; }
    cx /= n; cy /= n;
    p.reset();
    for (int i = 0; i < n; i++)
    {
      float vx = xs[i] - cx, vy = ys[i] - cy;
      float d = (float)Math.hypot(vx, vy);
      float s = (d > _split_gap_px) ? (d - _split_gap_px) / d : 1f;
      float ix = cx + vx * s, iy = cy + vy * s;
      if (i == 0) p.moveTo(ix, iy); else p.lineTo(ix, iy);
    }
    p.close();
  }

  /** Draw the triangle keys of both halves. */
  private void drawSplitTriangles(Canvas canvas)
  {
    for (TriKey tk : _split_keys)
    {
      if (tk.key == null) // don't render empty slots
        continue;
      boolean down = _pointers.isKeyDown(tk.key);
      Theme.Computed.Key tc_key = down ? _tc.key_activated : _tc.key;
      build_key_path(_tri_path, tk);
      tc_key.bg_paint.setPathEffect(_round_effect);
      canvas.drawPath(_tri_path, tc_key.bg_paint);
      _tri_border_paint.setPathEffect(_round_effect);
      canvas.drawPath(_tri_path, _tri_border_paint);
      if (tk.key.keys[0] != null)
      {
        KeyValue kv = modifyKey(tk.key.keys[0], _mods);
        if (kv != null)
        {
          Paint p = tc_key.label_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT),
              labelColor(kv, down, false), tk.labelSize * _config.split_main_label_scale);
          p.setTextAlign(Paint.Align.CENTER);
          // If the camera cutout sits over this key, move the label into the
          // clear area above (or below) the hole so the letter stays visible.
          // Only consider outer-column keys (i.e. those with a vertex on the
          // physical screen edge) — inner-column keys can overlap the
          // cutout's x range geometrically (e.g. S/D in the A row, K in the
          // L row) without actually being occluded by it, since the cutout
          // sits at the screen edge over the outer key.
          float lblx = tk.cx, lbly = tk.cy;
          boolean onOuterEdge = false;
          for (int v = 0; v < tk.onEdge.length; v++)
            if (tk.onEdge[v]) { onOuterEdge = true; break; }
          if (_has_cutout && onOuterEdge)
          {
            float kxMin = tk.xs[0], kxMax = tk.xs[0], kyMin = tk.ys[0], kyMax = tk.ys[0];
            for (int v = 1; v < tk.xs.length; v++)
            {
              kxMin = Math.min(kxMin, tk.xs[v]); kxMax = Math.max(kxMax, tk.xs[v]);
              kyMin = Math.min(kyMin, tk.ys[v]); kyMax = Math.max(kyMax, tk.ys[v]);
            }
            float hcy = _cutout_rect.centerY();
            boolean overlaps = kxMin <= _cutout_rect.right && kxMax >= _cutout_rect.left
                && kyMin <= hcy && kyMax >= hcy;
            if (overlaps)
            {
              float aboveH = _cutout_rect.top - kyMin;
              float belowH = kyMax - _cutout_rect.bottom;
              lbly = (aboveH >= belowH) ? (kyMin + _cutout_rect.top) / 2f
                                        : (_cutout_rect.bottom + kyMax) / 2f;
            }
          }
          canvas.drawText(kv.getString(), lblx,
              lbly - (p.ascent() + p.descent()) / 2f, p);
        }
      }
      // Swipe sub-keys: only on corners that aren't on a physical screen edge.
      // A trapezoid's two narrow-side vertices map to the same direction, so
      // dedupe to avoid drawing a glyph twice.
      boolean[] shown = new boolean[9];
      for (int i = 0; i < tk.xs.length; i++)
      {
        if (tk.onEdge[i])
          continue;
        float dx = tk.xs[i] - tk.cx, dy = tk.ys[i] - tk.cy;
        // Map the corner direction to one of the four diagonal sub-key slots:
        // 1=NW, 2=NE, 3=SW, 4=SE (see LABEL_POSITION_*).
        int sub = (dx < 0) ? (dy < 0 ? 1 : 3) : (dy < 0 ? 2 : 4);
        if (shown[sub] || tk.key.keys[sub] == null)
          continue;
        KeyValue sk = modifyKey(tk.key.keys[sub], _mods);
        if (sk == null)
          continue;
        shown[sub] = true;
        // More horizontal inset so glyphs don't touch the side edges; keep the
        // vertical position (top/bottom spacing already looks right).
        float ax = tk.xs[i] + (tk.cx - tk.xs[i]) * 0.5f;
        float ay = tk.ys[i] + (tk.cy - tk.ys[i]) * 0.32f;
        Paint p = tc_key.sublabel_paint(sk.hasFlagsAny(KeyValue.FLAG_KEY_FONT),
            labelColor(sk, down, true), tk.labelSize * 0.78f * _config.split_sublabel_scale,
            Paint.Align.CENTER);
        canvas.drawText(sk.getString(), ax, ay - (p.ascent() + p.descent()) / 2f, p);
      }
      // Orthogonal swipe sub-keys (N/S/E/W) drawn at edge midpoints. Only for
      // rectangles — a triangle has no clean N/S/E/W edge (its hypotenuse
      // would mis-place the glyph), so triangles use corner swipes only.
      int n = tk.xs.length;
      for (int i = 0; n == 4 && i < n; i++)
      {
        int j = (i + 1) % n;
        if (tk.onEdge[i] && tk.onEdge[j])
          continue;
        float mx = (tk.xs[i] + tk.xs[j]) / 2f, my = (tk.ys[i] + tk.ys[j]) / 2f;
        float dx = mx - tk.cx, dy = my - tk.cy;
        // 5=W 6=E 7=N 8=S
        int sub = (Math.abs(dx) > Math.abs(dy)) ? (dx > 0 ? 6 : 5) : (dy > 0 ? 8 : 7);
        if (tk.key.keys[sub] == null)
          continue;
        KeyValue sk = modifyKey(tk.key.keys[sub], _mods);
        if (sk == null)
          continue;
        float ax = mx + (tk.cx - mx) * 0.30f;
        float ay = my + (tk.cy - my) * 0.30f;
        Paint p = tc_key.sublabel_paint(sk.hasFlagsAny(KeyValue.FLAG_KEY_FONT),
            labelColor(sk, down, true), tk.labelSize * 0.78f * _config.split_sublabel_scale,
            Paint.Align.CENTER);
        canvas.drawText(sk.getString(), ax, ay - (p.ascent() + p.descent()) / 2f, p);
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
