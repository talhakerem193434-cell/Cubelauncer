package git.artdeell.mojo.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Modrinth mod browser with a card based layout.
 * Search, install, one-tap update, installed mods list with delete, and automatic
 * download of required dependencies.
 * The whole UI is built in code, so no layout XML is needed.
 */
public class ModBrowserFragment extends Fragment {
    public static final String TAG = "ModBrowserFragment";

    private static final String USER_AGENT = "talhakerem193434-cell/CubeLaunch/0.4";
    private static final String API = "https://api.modrinth.com/v2";
    private static final String CDN_PREFIX = "https://cdn.modrinth.com/";
    private static final String[] LOADERS = {"fabric", "forge", "neoforge", "quilt"};
    private static final String[] LOADER_LABELS = {"Fabric", "Forge", "NeoForge", "Quilt"};

    private static final String COLOR_BG = "#181818";
    private static final String COLOR_CARD = "#232323";
    private static final String COLOR_CHIP = "#2B2B2B";
    private static final String COLOR_GREEN = "#5DAA3B";
    private static final String COLOR_GREEN_DARK_TEXT = "#0E1A08";
    private static final String COLOR_MUTED = "#B2B2B2";

    private int mLoaderIndex = 0;
    private EditText mQuery;
    private EditText mVersion;
    private TextView[] mChips;
    private TextView mStatus;
    private ProgressBar mProgress;
    private LinearLayout mResults;
    private volatile boolean mBusy = false;
    private final Map<String, Bitmap> mIconCache =
            Collections.synchronizedMap(new HashMap<String, Bitmap>());

    public ModBrowserFragment() {
        super();
    }

    // ---------- small UI helpers ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(String color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    private void ui(Runnable r) {
        if (getActivity() != null && isAdded()) getActivity().runOnUiThread(r);
    }

    private void setStatus(String s) {
        if (mStatus != null) mStatus.setText(s);
    }

    private void setWorking(boolean working) {
        if (mProgress != null) mProgress.setVisibility(working ? View.VISIBLE : View.GONE);
    }

    private static String formatCount(long n) {
        if (n >= 1000000L) return String.format(java.util.Locale.US, "%.1f Mn", n / 1000000.0);
        if (n >= 1000L) return String.format(java.util.Locale.US, "%.1f B", n / 1000.0);
        return String.valueOf(n);
    }

    private Button styledActionButton(String text) {
        Button b = new Button(requireContext());
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.parseColor(COLOR_GREEN));
        b.setBackground(rounded(COLOR_CHIP, 22));
        return b;
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.US, "%d KB", Math.max(1L, bytes / 1024L));
    }

    private static String cleanVersion(String raw) {
        return raw.replaceAll("[^0-9A-Za-z.\\-]", "");
    }

    // ---------- screen ----------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(COLOR_BG));
        root.setPadding(dp(16), dp(16), dp(16), 0);

        // Title
        TextView title = new TextView(requireContext());
        title.setText("MODLAR  \u00b7  Modrinth");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        root.addView(title, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        // Search row: rounded input + green button
        LinearLayout searchRow = new LinearLayout(requireContext());
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        mQuery = new EditText(requireContext());
        mQuery.setHint("Mod ara (\u00f6rn. sodium)");
        mQuery.setTextColor(Color.WHITE);
        mQuery.setHintTextColor(Color.parseColor(COLOR_MUTED));
        mQuery.setTextSize(15);
        mQuery.setSingleLine(true);
        mQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mQuery.setBackground(rounded(COLOR_CARD, 24));
        mQuery.setPadding(dp(16), 0, dp(16), 0);
        mQuery.setOnEditorActionListener((v, actionId, event) -> {
            startSearch();
            return true;
        });
        searchRow.addView(mQuery, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button searchButton = new Button(requireContext());
        searchButton.setText("ARA");
        searchButton.setTextColor(Color.parseColor(COLOR_GREEN_DARK_TEXT));
        searchButton.setBackground(rounded(COLOR_GREEN, 24));
        searchButton.setOnClickListener(v -> startSearch());
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(dp(80), dp(48));
        sbp.leftMargin = dp(8);
        searchRow.addView(searchButton, sbp);

        root.addView(searchRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        // Version input
        mVersion = new EditText(requireContext());
        mVersion.setHint("Minecraft s\u00fcr\u00fcm\u00fc (\u00f6rn. 1.20.1)");
        mVersion.setTextColor(Color.WHITE);
        mVersion.setHintTextColor(Color.parseColor(COLOR_MUTED));
        mVersion.setTextSize(15);
        mVersion.setSingleLine(true);
        mVersion.setBackground(rounded(COLOR_CARD, 24));
        mVersion.setPadding(dp(16), 0, dp(16), 0);
        root.addView(mVersion, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 8));

        // Loader chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(requireContext());
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(requireContext());
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        mChips = new TextView[LOADERS.length];
        for (int i = 0; i < LOADERS.length; i++) {
            final int index = i;
            TextView chip = new TextView(requireContext());
            chip.setText(LOADER_LABELS[i]);
            chip.setTextSize(14);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            chip.setOnClickListener(v -> {
                mLoaderIndex = index;
                styleChips();
            });
            mChips[i] = chip;
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = dp(8);
            chipRow.addView(chip, cp);
        }
        chipScroll.addView(chipRow);
        styleChips();
        root.addView(chipScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        // Action row: one-tap update + installed mods
        LinearLayout actionRow = new LinearLayout(requireContext());
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button updateButton = styledActionButton("G\u00dcNCELLE");
        updateButton.setOnClickListener(v -> startUpdate());
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        updateParams.rightMargin = dp(4);
        actionRow.addView(updateButton, updateParams);

        Button installedButton = styledActionButton("Y\u00dcKL\u00dc MODLAR");
        installedButton.setOnClickListener(v -> showInstalled());
        LinearLayout.LayoutParams installedParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        installedParams.leftMargin = dp(4);
        actionRow.addView(installedButton, installedParams);

        root.addView(actionRow, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        // Results list (scrolls)
        ScrollView scroll = new ScrollView(requireContext());
        mResults = new LinearLayout(requireContext());
        mResults.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mResults, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(8);
        root.addView(scroll, scrollParams);

        // Bottom status strip with progress
        LinearLayout strip = new LinearLayout(requireContext());
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(Color.parseColor(COLOR_CARD));
        strip.setPadding(dp(16), dp(8), dp(16), dp(8));

        mProgress = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.GONE);
        strip.addView(mProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        mStatus = new TextView(requireContext());
        mStatus.setTextSize(13);
        mStatus.setTextColor(Color.parseColor(COLOR_MUTED));
        mStatus.setText("S\u00fcr\u00fcm\u00fc yaz, y\u00fckleyiciyi se\u00e7 ve ara.");
        strip.addView(mStatus, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4));

        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripParams.leftMargin = -dp(16);
        stripParams.rightMargin = -dp(16);
        root.addView(strip, stripParams);

        return root;
    }

    private void styleChips() {
        for (int i = 0; i < mChips.length; i++) {
            boolean selected = (i == mLoaderIndex);
            mChips[i].setBackground(rounded(selected ? COLOR_GREEN : COLOR_CHIP, 20));
            mChips[i].setTextColor(Color.parseColor(selected ? COLOR_GREEN_DARK_TEXT : "#FFFFFF"));
        }
    }

    // ---------- search ----------

    private void startSearch() {
        if (mBusy) {
            setStatus("Bir i\u015flem s\u00fcr\u00fcyor, l\u00fctfen bekle.");
            return;
        }
        final String query = mQuery.getText().toString().trim();
        final String version = cleanVersion(mVersion.getText().toString().trim());
        final String loader = LOADERS[mLoaderIndex];
        mBusy = true;
        setWorking(true);
        setStatus("Aran\u0131yor...");
        new Thread(() -> {
            try {
                StringBuilder facets = new StringBuilder();
                facets.append("[[\"project_type:mod\"],[\"categories:").append(loader).append("\"]");
                if (!version.isEmpty()) {
                    facets.append(",[\"versions:").append(version).append("\"]");
                }
                facets.append("]");
                String url = API + "/search?query=" + URLEncoder.encode(query, "UTF-8")
                        + "&facets=" + URLEncoder.encode(facets.toString(), "UTF-8")
                        + "&limit=20";
                JSONObject json = new JSONObject(httpGet(url));
                final JSONArray hits = json.getJSONArray("hits");
                ui(() -> showResults(hits, version, loader));
            } catch (Exception e) {
                final String msg = "Hata: " + e.getMessage();
                ui(() -> setStatus(msg));
            } finally {
                mBusy = false;
                ui(() -> setWorking(false));
            }
        }).start();
    }

    private void showResults(JSONArray hits, String version, String loader) {
        mResults.removeAllViews();
        if (hits.length() == 0) {
            setStatus("Sonu\u00e7 bulunamad\u0131. S\u00fcr\u00fcm\u00fc ve y\u00fckleyiciyi kontrol et.");
            return;
        }
        setStatus(hits.length() + " sonu\u00e7. \u0130ND\u0130R'e dokununca ba\u011f\u0131ml\u0131l\u0131klarla birlikte iner.");
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.optJSONObject(i);
            if (h != null) addCard(h, version, loader);
        }
    }

    private void addCard(JSONObject h, final String version, final String loader) {
        final String id = h.optString("project_id");
        final String name = h.optString("title");
        String author = h.optString("author");
        String desc = h.optString("description");
        long downloads = h.optLong("downloads", 0L);
        String iconUrl = h.optString("icon_url", "");

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(COLOR_CARD, 14));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Icon
        ImageView icon = new ImageView(requireContext());
        icon.setBackground(rounded(COLOR_CHIP, 10));
        icon.setClipToOutline(true);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        loadIcon(icon, iconUrl);

        // Text column
        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(requireContext());
        nameView.setText(name);
        nameView.setTextSize(16);
        nameView.setTextColor(Color.WHITE);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(nameView);

        TextView authorView = new TextView(requireContext());
        authorView.setText(author + "  \u00b7  " + formatCount(downloads) + " indirme");
        authorView.setTextSize(12);
        authorView.setTextColor(Color.parseColor(COLOR_GREEN));
        authorView.setSingleLine(true);
        authorView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(authorView);

        TextView descView = new TextView(requireContext());
        descView.setText(desc);
        descView.setTextSize(13);
        descView.setTextColor(Color.parseColor(COLOR_MUTED));
        descView.setMaxLines(2);
        descView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(descView);

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        tp.rightMargin = dp(8);
        card.addView(textCol, tp);

        // Download button
        Button download = new Button(requireContext());
        download.setText("\u0130ND\u0130R");
        download.setTextSize(13);
        download.setTextColor(Color.parseColor(COLOR_GREEN_DARK_TEXT));
        download.setBackground(rounded(COLOR_GREEN, 20));
        download.setOnClickListener(v -> install(id, name, version, loader));
        card.addView(download, new LinearLayout.LayoutParams(dp(84), dp(40)));

        mResults.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    private void loadIcon(final ImageView view, final String url) {
        if (url == null || url.isEmpty() || !url.startsWith(CDN_PREFIX)) return;
        Bitmap cached = mIconCache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) return;
                try (InputStream in = c.getInputStream()) {
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        mIconCache.put(url, bmp);
                        ui(() -> view.setImageBitmap(bmp));
                    }
                }
            } catch (Exception ignored) {
                // Icons are optional, ignore failures
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    // ---------- install ----------

    private void install(final String projectId, final String name,
                         final String version, final String loader) {
        if (mBusy) {
            setStatus("Bir i\u015flem s\u00fcr\u00fcyor, l\u00fctfen bekle.");
            return;
        }
        if (version.isEmpty()) {
            setStatus("\u00d6nce Minecraft s\u00fcr\u00fcm\u00fcn\u00fc yaz (\u00f6rn. 1.20.1).");
            return;
        }
        mBusy = true;
        setWorking(true);
        setStatus(name + " indiriliyor...");
        new Thread(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new Exception("Se\u00e7ili profil yok");
                File modsDir = new File(instance.getGameDirectory(), "mods");
                if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
                    throw new Exception("mods klas\u00f6r\u00fc olu\u015fturulamad\u0131");
                }
                Set<String> done = new HashSet<>();
                final int count = installProject(projectId, version, loader, modsDir, done, 0);
                ui(() -> setStatus("Bitti: " + count
                        + " dosya indirildi (gerekli ba\u011f\u0131ml\u0131l\u0131klar dahil)."));
            } catch (Exception e) {
                final String msg = "Hata: " + e.getMessage();
                ui(() -> setStatus(msg));
            } finally {
                mBusy = false;
                ui(() -> setWorking(false));
            }
        }).start();
    }

    private int installProject(String projectId, String version, String loader, File modsDir,
                               Set<String> done, int depth) throws Exception {
        if (depth > 8 || !done.add(projectId)) return 0;

        String url = API + "/project/" + URLEncoder.encode(projectId, "UTF-8") + "/version"
                + "?loaders=" + URLEncoder.encode("[\"" + loader + "\"]", "UTF-8")
                + "&game_versions=" + URLEncoder.encode("[\"" + version + "\"]", "UTF-8");
        JSONArray versions = new JSONArray(httpGet(url));
        if (versions.length() == 0) {
            if (depth == 0) throw new Exception("Bu s\u00fcr\u00fcm ve y\u00fckleyici i\u00e7in uygun dosya yok");
            return 0;
        }

        JSONObject newest = versions.getJSONObject(0);
        JSONArray files = newest.getJSONArray("files");
        if (files.length() == 0) return 0;
        JSONObject file = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            if (f.optBoolean("primary", false)) {
                file = f;
                break;
            }
        }

        final String fileName = file.getString("filename").replace("/", "_").replace("\\", "_");
        String fileUrl = file.getString("url");
        if (!fileUrl.startsWith(CDN_PREFIX)) throw new Exception("Beklenmeyen indirme adresi");

        File dest = new File(modsDir, fileName);
        if (!dest.getCanonicalPath().startsWith(modsDir.getCanonicalPath())) {
            throw new Exception("Ge\u00e7ersiz dosya ad\u0131");
        }

        int count = 0;
        if (!dest.exists()) {
            ui(() -> setStatus("\u0130ndiriliyor: " + fileName));
            download(fileUrl, dest);
            count++;
        }

        JSONArray deps = newest.optJSONArray("dependencies");
        if (deps != null) {
            for (int i = 0; i < deps.length(); i++) {
                JSONObject d = deps.getJSONObject(i);
                if ("required".equals(d.optString("dependency_type")) && !d.isNull("project_id")) {
                    count += installProject(d.getString("project_id"), version, loader,
                            modsDir, done, depth + 1);
                }
            }
        }
        return count;
    }

    // ---------- installed mods ----------

    private void showInstalled() {
        if (mBusy) {
            setStatus("Bir i\u015flem s\u00fcr\u00fcyor, l\u00fctfen bekle.");
            return;
        }
        mBusy = true;
        setWorking(true);
        setStatus("Y\u00fckl\u00fc modlar okunuyor...");
        new Thread(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new Exception("Se\u00e7ili profil yok");
                File modsDir = new File(instance.getGameDirectory(), "mods");
                File[] jars = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));
                final List<File> list = new ArrayList<>();
                if (jars != null) {
                    Arrays.sort(jars, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                    list.addAll(Arrays.asList(jars));
                }
                ui(() -> renderInstalled(list));
            } catch (Exception e) {
                final String msg = "Hata: " + e.getMessage();
                ui(() -> setStatus(msg));
            } finally {
                mBusy = false;
                ui(() -> setWorking(false));
            }
        }).start();
    }

    private void renderInstalled(List<File> list) {
        mResults.removeAllViews();
        if (list.isEmpty()) {
            setStatus("mods klas\u00f6r\u00fcnde mod yok.");
            return;
        }
        long total = 0L;
        for (File f : list) total += f.length();
        setStatus(list.size() + " y\u00fckl\u00fc mod, toplam " + formatSize(total)
                + ". Silmek i\u00e7in S\u0130L'e dokun.");
        for (File f : list) addInstalledRow(f);
    }

    private void addInstalledRow(final File file) {
        final LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(COLOR_CARD, 14));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(requireContext());
        nameView.setText(file.getName());
        nameView.setTextSize(14);
        nameView.setTextColor(Color.WHITE);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textCol.addView(nameView);

        TextView sizeView = new TextView(requireContext());
        sizeView.setText(formatSize(file.length()));
        sizeView.setTextSize(12);
        sizeView.setTextColor(Color.parseColor(COLOR_MUTED));
        textCol.addView(sizeView);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.rightMargin = dp(8);
        row.addView(textCol, textParams);

        Button delete = new Button(requireContext());
        delete.setText("S\u0130L");
        delete.setTextSize(13);
        delete.setTextColor(Color.WHITE);
        delete.setBackground(rounded("#8C2F2F", 20));
        delete.setOnClickListener(v -> confirmDelete(file, row));
        row.addView(delete, new LinearLayout.LayoutParams(dp(72), dp(40)));

        mResults.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    private void confirmDelete(final File file, final View row) {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("Modu sil")
                .setMessage(file.getName() + " silinsin mi?\n\n"
                        + "Ba\u015fka modlar buna ba\u011fl\u0131 olabilir, silersen oyun a\u00e7\u0131lmayabilir.")
                .setPositiveButton("Sil", (d, w) -> {
                    if (file.delete()) {
                        mResults.removeView(row);
                        setStatus(file.getName() + " silindi.");
                    } else {
                        setStatus("Silinemedi: " + file.getName());
                    }
                })
                .setNegativeButton("Vazge\u00e7", null)
                .show();
    }

    // ---------- update ----------

    private void startUpdate() {
        if (mBusy) {
            setStatus("Bir i\u015flem s\u00fcr\u00fcyor, l\u00fctfen bekle.");
            return;
        }
        final String version = cleanVersion(mVersion.getText().toString().trim());
        if (version.isEmpty()) {
            setStatus("\u00d6nce Minecraft s\u00fcr\u00fcm\u00fcn\u00fc yaz (\u00f6rn. 1.20.1).");
            return;
        }
        final String loader = LOADERS[mLoaderIndex];
        mBusy = true;
        setWorking(true);
        setStatus("Modlar taran\u0131yor...");
        new Thread(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new Exception("Se\u00e7ili profil yok");
                File modsDir = new File(instance.getGameDirectory(), "mods");
                File[] jars = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars == null || jars.length == 0) {
                    throw new Exception("mods klas\u00f6r\u00fcnde .jar dosyas\u0131 yok");
                }

                Map<String, File> byHash = new HashMap<>();
                JSONArray hashArray = new JSONArray();
                for (File jar : jars) {
                    String h = sha1(jar);
                    byHash.put(h, jar);
                    hashArray.put(h);
                }

                JSONObject body = new JSONObject();
                body.put("hashes", hashArray);
                body.put("algorithm", "sha1");
                body.put("loaders", new JSONArray().put(loader));
                body.put("game_versions", new JSONArray().put(version));
                JSONObject result = new JSONObject(
                        httpPost(API + "/version_files/update", body.toString()));

                int updated = 0;
                int upToDate = 0;
                int unknown = 0;
                int failed = 0;
                int newDeps = 0;
                Set<String> done = new HashSet<>();

                for (Map.Entry<String, File> entry : byHash.entrySet()) {
                    final String oldHash = entry.getKey();
                    final File oldFile = entry.getValue();
                    JSONObject latest = result.optJSONObject(oldHash);
                    if (latest == null) {
                        unknown++;
                        continue;
                    }
                    try {
                        JSONArray files = latest.getJSONArray("files");
                        if (files.length() == 0) {
                            unknown++;
                            continue;
                        }
                        JSONObject file = files.getJSONObject(0);
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject f = files.getJSONObject(i);
                            if (f.optBoolean("primary", false)) {
                                file = f;
                                break;
                            }
                        }
                        JSONObject hashes = file.optJSONObject("hashes");
                        String newHash = hashes == null ? "" : hashes.optString("sha1", "");
                        if (newHash.equalsIgnoreCase(oldHash)) {
                            upToDate++;
                            continue;
                        }

                        String fileName = file.getString("filename")
                                .replace("/", "_").replace("\\", "_");
                        String fileUrl = file.getString("url");
                        if (!fileUrl.startsWith(CDN_PREFIX)) {
                            throw new Exception("Beklenmeyen indirme adresi");
                        }
                        File dest = new File(modsDir, fileName);
                        if (!dest.getCanonicalPath().startsWith(modsDir.getCanonicalPath())) {
                            throw new Exception("Ge\u00e7ersiz dosya ad\u0131");
                        }

                        final String shownName = oldFile.getName();
                        ui(() -> setStatus("G\u00fcncelleniyor: " + shownName));
                        if (!dest.exists() || dest.equals(oldFile)) {
                            download(fileUrl, dest);
                        }
                        if (!dest.equals(oldFile)) {
                            oldFile.delete();
                        }
                        updated++;

                        JSONArray deps = latest.optJSONArray("dependencies");
                        if (deps != null) {
                            for (int i = 0; i < deps.length(); i++) {
                                JSONObject d = deps.getJSONObject(i);
                                if ("required".equals(d.optString("dependency_type"))
                                        && !d.isNull("project_id")) {
                                    newDeps += installProject(d.getString("project_id"),
                                            version, loader, modsDir, done, 1);
                                }
                            }
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }

                String summary = "G\u00fcncelleme bitti: " + updated + " g\u00fcncellendi, "
                        + upToDate + " zaten g\u00fcncel, "
                        + unknown + " Modrinth'te bulunamad\u0131 (elle eklenmi\u015f olabilir), "
                        + failed + " hata.";
                if (newDeps > 0) {
                    summary += " " + newDeps + " yeni ba\u011f\u0131ml\u0131l\u0131k indirildi.";
                }
                final String finalSummary = summary;
                ui(() -> setStatus(finalSummary));
            } catch (Exception e) {
                final String msg = "Hata: " + e.getMessage();
                ui(() -> setStatus(msg));
            } finally {
                mBusy = false;
                ui(() -> setWorking(false));
            }
        }).start();
    }

    private static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String httpPost(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Sunucu hatas\u0131: " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ---------- network ----------

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Sunucu hatas\u0131: " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        try {
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("\u0130ndirme hatas\u0131: " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(dest)) throw new Exception("Dosya kaydedilemedi");
        } finally {
            c.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }
}
