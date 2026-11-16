package git.artdeell.mojo.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
import java.util.HashSet;
import java.util.Set;

/**
 * Simple Modrinth mod browser: search, download, and automatically download required dependencies.
 * The whole UI is built in code, so no layout XML is needed.
 */
public class ModBrowserFragment extends Fragment {
    public static final String TAG = "ModBrowserFragment";

    private static final String USER_AGENT = "talhakerem193434-cell/CubeLaunch/0.1";
    private static final String API = "https://api.modrinth.com/v2";
    private static final String CDN_PREFIX = "https://cdn.modrinth.com/";
    private static final String[] LOADERS = {"fabric", "forge", "neoforge", "quilt"};

    private int mLoaderIndex = 0;
    private EditText mQuery;
    private EditText mVersion;
    private Button mLoaderButton;
    private TextView mStatus;
    private LinearLayout mResults;
    private volatile boolean mBusy = false;

    public ModBrowserFragment() {
        super();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams rowParams(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private void ui(Runnable r) {
        if (getActivity() != null && isAdded()) getActivity().runOnUiThread(r);
    }

    private void setStatus(String s) {
        if (mStatus != null) mStatus.setText(s);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundColor(Color.parseColor("#181818"));

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(requireContext());
        title.setText("MODLAR (Modrinth)");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        root.addView(title, rowParams(0));

        mVersion = new EditText(requireContext());
        mVersion.setHint("Minecraft sürümü (örn. 1.20.1)");
        mVersion.setTextColor(Color.WHITE);
        mVersion.setHintTextColor(Color.parseColor("#B2B2B2"));
        mVersion.setSingleLine(true);
        root.addView(mVersion, rowParams(16));

        mLoaderButton = new Button(requireContext());
        updateLoaderButton();
        mLoaderButton.setOnClickListener(v -> {
            mLoaderIndex = (mLoaderIndex + 1) % LOADERS.length;
            updateLoaderButton();
        });
        root.addView(mLoaderButton, rowParams(8));

        mQuery = new EditText(requireContext());
        mQuery.setHint("Mod ara (örn. sodium)");
        mQuery.setTextColor(Color.WHITE);
        mQuery.setHintTextColor(Color.parseColor("#B2B2B2"));
        mQuery.setSingleLine(true);
        root.addView(mQuery, rowParams(8));

        Button searchButton = new Button(requireContext());
        searchButton.setText("ARA");
        searchButton.setTextColor(Color.parseColor("#0E1A08"));
        searchButton.setBackgroundColor(Color.parseColor("#5DAA3B"));
        searchButton.setOnClickListener(v -> startSearch());
        root.addView(searchButton, rowParams(8));

        mStatus = new TextView(requireContext());
        mStatus.setTextSize(14);
        mStatus.setTextColor(Color.parseColor("#B2B2B2"));
        mStatus.setText("Sürümü yaz, yükleyiciyi seç ve ara. Bir moda dokununca bağımlılıklarıyla birlikte indirilir.");
        root.addView(mStatus, rowParams(12));

        mResults = new LinearLayout(requireContext());
        mResults.setOrientation(LinearLayout.VERTICAL);
        root.addView(mResults, rowParams(8));

        return scroll;
    }

    private void updateLoaderButton() {
        mLoaderButton.setText("Yükleyici: " + LOADERS[mLoaderIndex] + " (değiştirmek için dokun)");
    }

    private static String cleanVersion(String raw) {
        return raw.replaceAll("[^0-9A-Za-z.\\-]", "");
    }

    private void startSearch() {
        if (mBusy) return;
        final String query = mQuery.getText().toString().trim();
        final String version = cleanVersion(mVersion.getText().toString().trim());
        final String loader = LOADERS[mLoaderIndex];
        mBusy = true;
        setStatus("Aranıyor...");
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
            }
        }).start();
    }

    private void showResults(JSONArray hits, String version, String loader) {
        mResults.removeAllViews();
        if (hits.length() == 0) {
            setStatus("Sonuç bulunamadı.");
            return;
        }
        setStatus(hits.length() + " sonuç. Bir moda dokununca indirilir.");
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.optJSONObject(i);
            if (h == null) continue;
            final String id = h.optString("project_id");
            final String name = h.optString("title");
            String desc = h.optString("description");

            TextView row = new TextView(requireContext());
            row.setText(name + "\n" + desc);
            row.setTextColor(Color.WHITE);
            row.setTextSize(15);
            row.setBackgroundColor(Color.parseColor("#232323"));
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setOnClickListener(v -> install(id, name, version, loader));
            mResults.addView(row, rowParams(8));
        }
    }

    private void install(final String projectId, final String name, final String version, final String loader) {
        if (mBusy) return;
        if (version.isEmpty()) {
            setStatus("Önce Minecraft sürümünü yaz (örn. 1.20.1).");
            return;
        }
        mBusy = true;
        setStatus(name + " indiriliyor...");
        new Thread(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new Exception("Seçili profil yok");
                File modsDir = new File(instance.getGameDirectory(), "mods");
                if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
                    throw new Exception("mods klasörü oluşturulamadı");
                }
                Set<String> done = new HashSet<>();
                final int count = installProject(projectId, version, loader, modsDir, done, 0);
                ui(() -> setStatus("Bitti: " + count + " dosya indirildi (gerekli bağımlılıklar dahil)."));
            } catch (Exception e) {
                final String msg = "Hata: " + e.getMessage();
                ui(() -> setStatus(msg));
            } finally {
                mBusy = false;
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
            if (depth == 0) throw new Exception("Bu sürüm ve yükleyici için uygun dosya yok");
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
            throw new Exception("Geçersiz dosya adı");
        }

        int count = 0;
        if (!dest.exists()) {
            ui(() -> setStatus("İndiriliyor: " + fileName));
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

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Sunucu hatası: " + code);
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
            if (code != 200) throw new Exception("İndirme hatası: " + code);
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
