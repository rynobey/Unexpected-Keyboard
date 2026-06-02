package juloo.keyboard2.prefs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import juloo.keyboard2.*;
import org.json.JSONException;
import org.json.JSONObject;

/** Per-key swipe-direction overrides. Each item maps a key (by the name of
    its center value, e.g. "q" or "space") to replacement keys for any of the
    8 swipe directions. Applied to the split-mode layout, where user
    overrides win over the built-in swipe assignments. */
public class CustomKeySwipesPreference extends ListGroupPreference<CustomKeySwipesPreference.KeySwipes>
{
  /** This pref stores a list of JSON objects. */
  static final String KEY = "custom_key_swipes";
  /** Direction names, indexed like [KeyboardData.Key.keys]
      (1=NW 2=NE 3=SW 4=SE 5=W 6=E 7=N 8=S). Index 0 is the center key. */
  static final String[] DIR_NAMES =
    new String[]{ null, "nw", "ne", "sw", "se", "w", "e", "n", "s" };
  static final KeySwipesSerializer SERIALIZER = new KeySwipesSerializer();

  public CustomKeySwipesPreference(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    setKey(KEY);
  }

  /** Map from center key value to swipe overrides, indexed like
      [KeyboardData.Key.keys]. A [null] entry leaves the layout's default;
      the REMOVED placeholder ("none"/"removed") clears the slot. */
  public static Map<KeyValue, KeyValue[]> get(SharedPreferences prefs)
  {
    Map<KeyValue, KeyValue[]> m = new HashMap<KeyValue, KeyValue[]>();
    List<KeySwipes> items = load_from_preferences(KEY, prefs, null, SERIALIZER);
    if (items == null)
      return m;
    for (KeySwipes ks : items)
    {
      if (ks.key == null || ks.key.trim().isEmpty())
        continue;
      KeyValue[] ov = new KeyValue[9];
      boolean any = false;
      for (int i = 1; i < 9; i++)
      {
        String d = ks.dirs[i];
        if (d == null)
          continue;
        d = d.trim();
        if (d.isEmpty())
          continue;
        try
        {
          ov[i] = KeyValue.getKeyByName(d.equals("none") ? "removed" : d);
          any = true;
        }
        catch (Exception e) {} // Ignore unparsable entries
      }
      if (!any)
        continue;
      try { m.put(KeyValue.getKeyByName(ks.key.trim()), ov); }
      catch (Exception e) {}
    }
    return m;
  }

  String label_of_value(KeySwipes value, int i)
  {
    StringBuilder sb = new StringBuilder(value.key);
    for (int d = 1; d < 9; d++)
      if (value.dirs[d] != null && !value.dirs[d].trim().isEmpty())
        sb.append("  ").append(DIR_NAMES[d]).append("→").append(value.dirs[d].trim());
    return sb.toString();
  }

  static final int[] FIELD_IDS = new int[]{
    R.id.key_center, R.id.swipe_nw, R.id.swipe_ne, R.id.swipe_sw,
    R.id.swipe_se, R.id.swipe_w, R.id.swipe_e, R.id.swipe_n, R.id.swipe_s
  };

  @Override
  void select(final SelectionCallback<KeySwipes> callback, KeySwipes old_value)
  {
    View content = View.inflate(getContext(), R.layout.dialog_key_swipes, null);
    final EditText[] fields = new EditText[9];
    for (int i = 0; i < 9; i++)
      fields[i] = (EditText)content.findViewById(FIELD_IDS[i]);
    if (old_value != null)
    {
      fields[0].setText(old_value.key);
      for (int i = 1; i < 9; i++)
        if (old_value.dirs[i] != null)
          fields[i].setText(old_value.dirs[i]);
    }
    new AlertDialog.Builder(getContext())
      .setView(content)
      .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener(){
        public void onClick(DialogInterface dialog, int which)
        {
          String key = fields[0].getText().toString().trim();
          if (key.isEmpty())
            return;
          String[] dirs = new String[9];
          for (int i = 1; i < 9; i++)
          {
            String d = fields[i].getText().toString().trim();
            dirs[i] = d.isEmpty() ? null : d;
          }
          callback.select(new KeySwipes(key, dirs));
        }
      })
      .setNegativeButton(android.R.string.cancel, null)
      .show();
  }

  @Override
  Serializer<KeySwipes> get_serializer() { return SERIALIZER; }

  public static class KeySwipes
  {
    /** Name of the key's center value, e.g. "q", "space", "shift". */
    public final String key;
    /** Swipe overrides as entered by the user, indexed like
        [KeyboardData.Key.keys]. [null] = keep the layout's default. */
    public final String[] dirs;

    public KeySwipes(String key_, String[] dirs_)
    {
      key = key_;
      dirs = dirs_;
    }
  }

  /** Serialized as {"key": "q", "nw": "esc", ...}, omitting empty slots. */
  static class KeySwipesSerializer implements Serializer<KeySwipes>
  {
    public KeySwipes load_item(Object obj) throws JSONException
    {
      JSONObject o = (JSONObject)obj;
      String[] dirs = new String[9];
      for (int i = 1; i < 9; i++)
        dirs[i] = o.has(DIR_NAMES[i]) ? o.getString(DIR_NAMES[i]) : null;
      return new KeySwipes(o.getString("key"), dirs);
    }

    public Object save_item(KeySwipes v) throws JSONException
    {
      JSONObject o = new JSONObject();
      o.put("key", v.key);
      for (int i = 1; i < 9; i++)
        if (v.dirs[i] != null && !v.dirs[i].trim().isEmpty())
          o.put(DIR_NAMES[i], v.dirs[i].trim());
      return o;
    }
  }
}
