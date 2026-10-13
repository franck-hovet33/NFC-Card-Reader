package com.example.nfccardreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final int MODE_NONE = 0;
    private static final int MODE_READ = 1;
    private static final int MODE_WRITE = 2;

    private static final int BG = Color.rgb(8, 18, 34);
    private static final int PANEL = Color.rgb(19, 36, 58);
    private static final int PANEL_LIGHT = Color.rgb(27, 49, 75);
    private static final int BLUE = Color.rgb(0, 132, 255);
    private static final int GREEN = Color.rgb(0, 190, 136);
    private static final int WHITE = Color.rgb(242, 247, 255);
    private static final int MUTED = Color.rgb(166, 187, 210);
    private static final int RED = Color.rgb(255, 92, 111);

    private NfcAdapter nfc;
    private SharedPreferences prefs;
    private TextView status;
    private TextView details;
    private LinearLayout history;
    private ScrollView pageScroll;
    private LinearLayout screenRoot;
    private LinearLayout pageContent;
    private LinearLayout pickerList;
    private TextView pickerSelection;
    private TextView nfcStateInfo;
    private TextView nfcPageHeading;
    private String currentPage = "home";
    private String historyQuery = "";
    private String pickerQuery = "";
    private int selectedContentType = 0;
    private int selectedHistoryIndex = -1;
    private String lastCardUid = "";
    private String lastCardDisplay = "";
    private volatile int scanMode = MODE_NONE;
    private volatile boolean processing = false;
    private volatile NdefMessage pendingMessage;
    private volatile String pendingSource = "Nouveau contenu";
    private final Handler mainHandler = new Handler();

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(5, 14, 27));
        getWindow().setNavigationBarColor(Color.rgb(5, 14, 27));

        prefs = getSharedPreferences("cards", MODE_PRIVATE);
        nfc = NfcAdapter.getDefaultAdapter(this);
        buildUI();
        updateNfcStatus();
    }

    private void buildUI() {
        showHome();
    }

    private void createPage(String title, boolean backButton) {
        screenRoot = new LinearLayout(this);
        screenRoot.setOrientation(LinearLayout.VERTICAL);
        screenRoot.setBackgroundColor(BG);

        if (title != null && !title.isEmpty()) {
            LinearLayout toolbar = new LinearLayout(this);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setPadding(dp(10), dp(5), dp(10), dp(5));
            toolbar.setBackgroundColor(Color.rgb(12, 31, 53));
            if (backButton) {
                TextView back = text("‹", 34, WHITE, false);
                back.setGravity(Gravity.CENTER);
                toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
                back.setOnClickListener(v -> goBack());
            }
            TextView titleView = text(title, 18, WHITE, true);
            titleView.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.addView(titleView, new LinearLayout.LayoutParams(0, dp(48), 1));
            TextView settings = text("⚙", 22, WHITE, true);
            settings.setGravity(Gravity.CENTER);
            toolbar.addView(settings, new LinearLayout.LayoutParams(dp(38), dp(48)));
            settings.setOnClickListener(v -> showSettingsPage());
            screenRoot.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        }

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        pageScroll.setBackgroundColor(BG);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(dp(18), dp(16), dp(18), dp(24));
        pageScroll.addView(pageContent, new ScrollView.LayoutParams(-1, -2));
        screenRoot.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        history = null;
        status = null;
        details = null;
        nfcStateInfo = null;
        nfcPageHeading = null;
        pickerList = null;
        pickerSelection = null;
        setContentView(screenRoot);
    }

    private void goBack() {
        if ("home".equals(currentPage)) return;
        if (scanMode != MODE_NONE && ("read".equals(currentPage) || "write_wait".equals(currentPage))) {
            stopScanning("Opération annulée.");
        }
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (!"home".equals(currentPage)) {
            goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void showHome() {
        currentPage = "home";
        createPage("", false);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_nfc_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        logoParams.rightMargin = dp(14);
        header.addView(logo, logoParams);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(text("NFC Manager", 25, WHITE, true));
        brand.addView(text("Lire  •  Enregistrer  •  Formater", 13, MUTED, false));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        pageContent.addView(header, marginParams(-1, -2, 0, 0, 0, 20));

        LinearLayout stateCard = panel();
        stateCard.setOrientation(LinearLayout.VERTICAL);
        stateCard.addView(text("ÉTAT DU NFC", 12, MUTED, true), marginParams(-1, -2, 0, 0, 0, 6));
        status = text("Vérification du NFC…", 16, WHITE, true);
        status.setLineSpacing(dp(2), 1f);
        stateCard.addView(status);
        pageContent.addView(stateCard, marginParams(-1, -2, 0, 0, 0, 12));

        Button nfcSettings = button("⚡  Activer / régler le NFC", PANEL_LIGHT);
        nfcSettings.setOnClickListener(v -> showNfcPage());
        pageContent.addView(nfcSettings, marginParams(-1, dp(52), 0, 0, 0, 18));

        pageContent.addView(createActionCard("▣", "Lire une carte",
                "Lire l’identifiant, les technologies et les données disponibles", BLUE,
                v -> beginRead()), marginParams(-1, dp(104), 0, 0, 0, 12));
        pageContent.addView(createActionCard("✎", "Formater une carte",
                "Écrire un contenu ou reprendre les données d’une carte de l’historique",
                Color.rgb(85, 75, 230), v -> showFormatOptions()),
                marginParams(-1, dp(104), 0, 0, 0, 15));

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        addShortcut(shortcuts, "◷", "Historique", v -> showHistoryPage());
        addShortcut(shortcuts, "⚙", "Paramètres", v -> showSettingsPage());
        addShortcut(shortcuts, "ⓘ", "À propos", v -> showAboutPage());
        pageContent.addView(shortcuts, marginParams(-1, dp(78), 0, 0, 0, 20));

        pageContent.addView(text("Dernière carte détectée", 20, WHITE, true), marginParams(-1, -2, 0, 0, 0, 9));
        LinearLayout recent = panel();
        recent.setOrientation(LinearLayout.VERTICAL);
        String recentText = lastCardDisplay;
        if (recentText == null || recentText.trim().isEmpty()) {
            recentText = getLatestCardSummary();
        }
        if (recentText == null || recentText.trim().isEmpty()) {
            recentText = "Aucune carte scannée pour le moment.\n\nAppuie sur « Lire une carte », puis approche une carte du dos du téléphone.";
        }
        details = text(recentText, 14, MUTED, false);
        details.setLineSpacing(dp(3), 1f);
        recent.addView(details);
        pageContent.addView(recent, marginParams(-1, -2, 0, 0, 0, 18));
        TextView foot = text("Hors ligne  •  Données stockées sur ce téléphone", 12, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        pageContent.addView(foot, marginParams(-1, -2, 0, 0, 0, 2));
        updateNfcStatus();
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setPadding(dp(15), dp(14), dp(15), dp(14));
        p.setBackground(round(PANEL, 17));
        return p;
    }

    private void showNfcPage() {
        currentPage = "nfc_settings";
        createPage("Activation du NFC", true);
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(12), dp(26), dp(12), dp(14));
        TextView icon = text(")))", 40, WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Color.rgb(18, 67, 111), 100));
        hero.addView(icon, marginParams(dp(130), dp(130), 0, 4, 0, 24));
        nfcPageHeading = text(nfc != null && nfc.isEnabled() ? "Le NFC est activé" : "Le NFC est désactivé", 22, WHITE, true);
        nfcPageHeading.setGravity(Gravity.CENTER);
        hero.addView(nfcPageHeading, marginParams(-1, -2, 0, 0, 0, 12));
        TextView description = text("Pour lire ou écrire des cartes NFC, Android doit autoriser l’accès au NFC. L’application ne peut pas activer le composant silencieusement.", 15, MUTED, false);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(dp(3), 1f);
        hero.addView(description);
        pageContent.addView(hero, marginParams(-1, -2, 0, 0, 0, 16));

        nfcStateInfo = text("", 15, WHITE, true);
        nfcStateInfo.setGravity(Gravity.CENTER);
        nfcStateInfo.setPadding(dp(12), dp(14), dp(12), dp(14));
        nfcStateInfo.setBackground(round(PANEL, 14));
        pageContent.addView(nfcStateInfo, marginParams(-1, -2, 0, 0, 0, 14));
        Button activate = button("⚡  Ouvrir les paramètres NFC", BLUE);
        activate.setOnClickListener(v -> openNfcSettings());
        pageContent.addView(activate, marginParams(-1, dp(54), 0, 0, 0, 18));
        TextView tip = text("Astuce\nActive le bouton NFC dans les paramètres Android, puis reviens ici. Tu pourras ensuite appuyer sur LIRE ou FORMATER.", 14, MUTED, false);
        tip.setPadding(dp(14), dp(15), dp(14), dp(15));
        tip.setBackground(round(PANEL_LIGHT, 14));
        pageContent.addView(tip);
        updateNfcStatus();
        addBottomNav();
    }

    private void showReadPage() {
        currentPage = "read";
        createPage("Lire une carte NFC", true);
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(10), dp(25), dp(10), dp(16));
        TextView illustration = text("▣ )))", 35, BLUE, true);
        illustration.setGravity(Gravity.CENTER);
        illustration.setBackground(round(Color.rgb(18, 47, 78), 100));
        hero.addView(illustration, marginParams(dp(150), dp(150), 0, 0, 0, 24));
        TextView headline = text("En attente d’une carte…", 21, WHITE, true);
        headline.setGravity(Gravity.CENTER);
        hero.addView(headline, marginParams(-1, -2, 0, 0, 0, 10));
        TextView desc = text("Approche une carte NFC de l’arrière du téléphone et garde-la en place quelques secondes.", 15, MUTED, false);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(3), 1f);
        hero.addView(desc);
        pageContent.addView(hero, marginParams(-1, -2, 0, 0, 0, 14));

        status = text("", 14, WHITE, true);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackground(round(PANEL, 14));
        pageContent.addView(status, marginParams(-1, -2, 0, 0, 0, 12));
        LinearLayout tips = panel();
        tips.setOrientation(LinearLayout.VERTICAL);
        tips.addView(text("✓  Le NFC doit être activé", 14, WHITE, false), marginParams(-1, -2, 0, 0, 0, 8));
        tips.addView(text("✓  Place la carte près de l’antenne NFC", 14, WHITE, false), marginParams(-1, -2, 0, 0, 0, 8));
        tips.addView(text("✓  Retire une coque épaisse si nécessaire", 14, WHITE, false));
        pageContent.addView(tips, marginParams(-1, -2, 0, 0, 0, 14));
        Button settings = button("Activer / régler le NFC", PANEL_LIGHT);
        settings.setOnClickListener(v -> openNfcSettings());
        pageContent.addView(settings, marginParams(-1, dp(48), 0, 0, 0, 9));
        Button cancel = button("Annuler la lecture", RED);
        cancel.setOnClickListener(v -> { stopScanning("Lecture arrêtée."); showHome(); });
        pageContent.addView(cancel, marginParams(-1, dp(48), 0, 0, 0, 5));
        updateNfcStatus();
    }

    private LinearLayout createActionCard(String glyph, String title, String subtitle,
                                          int color, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(12), dp(15), dp(12));
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{color, darken(color)});
        gradient.setCornerRadius(dp(19));
        card.setBackground(gradient);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);

        TextView icon = text(glyph, 28, WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Color.argb(45, 255, 255, 255), 15));
        card.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(13), 0, dp(4), 0);
        words.addView(text(title, 19, WHITE, true));
        TextView description = text(subtitle, 13, Color.rgb(228, 239, 255), false);
        description.setPadding(0, dp(4), 0, 0);
        words.addView(description);
        card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(text("›", 32, WHITE, true));
        return card;
    }

    private void addShortcut(LinearLayout parent, String glyph, String label,
                             View.OnClickListener listener) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(3), dp(7), dp(3), dp(6));
        tile.setBackground(round(PANEL, 14));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(listener);
        TextView icon = text(glyph, 23, WHITE, true);
        icon.setGravity(Gravity.CENTER);
        tile.addView(icon);
        TextView caption = text(label, 12, MUTED, false);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(4), 0, 0);
        tile.addView(caption);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(tile, params);
    }

    private void showSettingsPage() {
        currentPage = "settings";
        createPage("Paramètres", true);
        pageContent.addView(text("NFC", 15, MUTED, true), marginParams(-1, -2, 0, 0, 0, 8));
        LinearLayout state = panel();
        state.setOrientation(LinearLayout.VERTICAL);
        state.addView(text("Activer ou vérifier le NFC", 17, WHITE, true), marginParams(-1, -2, 0, 0, 0, 5));
        nfcStateInfo = text("", 14, MUTED, false);
        state.addView(nfcStateInfo);
        pageContent.addView(state, marginParams(-1, -2, 0, 0, 0, 10));
        Button nfcBtn = button("Ouvrir les paramètres NFC", BLUE);
        nfcBtn.setOnClickListener(v -> openNfcSettings());
        pageContent.addView(nfcBtn, marginParams(-1, dp(50), 0, 0, 0, 20));

        pageContent.addView(text("Données", 15, MUTED, true), marginParams(-1, -2, 0, 0, 0, 8));
        Button export = button("Partager / exporter l’historique", PANEL_LIGHT);
        export.setOnClickListener(v -> exportHistory());
        pageContent.addView(export, marginParams(-1, dp(48), 0, 0, 0, 9));
        Button clear = button("Effacer l’historique", RED);
        clear.setOnClickListener(v -> confirmClearHistory());
        pageContent.addView(clear, marginParams(-1, dp(48), 0, 0, 0, 20));
        pageContent.addView(text("NFC Manager • Version 3.0\nFonctionnement hors ligne. L’écriture est limitée aux cartes inscriptibles compatibles NDEF.", 13, MUTED, false), marginParams(-1, -2, 0, 0, 0, 12));
        updateNfcStatus();
        addBottomNav();
    }

    private void showAboutPage() {
        currentPage = "about";
        createPage("À propos", true);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_nfc_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(100), dp(100));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(16);
        pageContent.addView(logo, lp);
        TextView name = text("NFC Manager", 25, WHITE, true);
        name.setGravity(Gravity.CENTER);
        pageContent.addView(name, marginParams(-1, -2, 0, 0, 0, 8));
        TextView description = text("Lire • Enregistrer • Renommer • Écrire", 15, MUTED, false);
        description.setGravity(Gravity.CENTER);
        pageContent.addView(description, marginParams(-1, -2, 0, 0, 0, 20));
        pageContent.addView(text("L’application lit les informations exposées par Android, conserve un historique local et peut écrire des données NDEF sur des tags compatibles.", 15, WHITE, false), marginParams(-1, -2, 0, 0, 0, 14));
        pageContent.addView(text("Important : l’UID matériel d’une carte et ses clés de sécurité ne sont pas copiés. L’écriture peut remplacer les données NDEF présentes sur le tag cible.", 14, MUTED, false), marginParams(-1, -2, 0, 0, 0, 14));
        addBottomNav();
    }

    private void addBottomNav() {
        if (screenRoot == null) return;
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        nav.setBackgroundColor(Color.rgb(7, 20, 36));
        addNavItem(nav, "⌂", "Accueil", () -> showHome());
        addNavItem(nav, "◷", "Historique", () -> showHistoryPage());
        addNavItem(nav, "⚙", "Paramètres", () -> showSettingsPage());
        screenRoot.addView(nav, new LinearLayout.LayoutParams(-1, dp(60)));
    }

    private void addNavItem(LinearLayout nav, String icon, String label, Runnable click) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER);
        item.setOrientation(LinearLayout.VERTICAL);
        TextView iconView = text(icon, 20, WHITE, true);
        iconView.setGravity(Gravity.CENTER);
        item.addView(iconView);
        TextView labelView = text(label, 11, MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        item.addView(labelView);
        item.setOnClickListener(v -> click.run());
        nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void exportHistory() {
        JSONArray data = getCards();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Historique NFC Manager");
        send.putExtra(Intent.EXTRA_TEXT, data.toString());
        try {
            startActivity(Intent.createChooser(send, "Exporter l’historique"));
        } catch (Exception e) {
            toast("Impossible d’ouvrir le partage.");
        }
    }

    private String getLatestCardSummary() {
        JSONArray array = getCards();
        if (array.length() == 0) return "";
        JSONObject card = array.optJSONObject(array.length() - 1);
        if (card == null) return "";
        String name = card.optString("name", "").trim();
        if (name.isEmpty()) name = "Carte NFC";
        return name + "\nUID : " + card.optString("uid", "?") + "\n\n" + card.optString("records", "Aucune donnée enregistrée.");
    }

    private void scrollToHistory() {
        showHistoryPage();
    }

    private void showAbout() {
        showAboutPage();
    }

    private int darken(int color) {
        return Color.rgb((int) (Color.red(color) * 0.72f),
                (int) (Color.green(color) * 0.72f),
                (int) (Color.blue(color) * 0.72f));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNfcStatus();
        if (scanMode != MODE_NONE && nfc != null && nfc.isEnabled()) {
            activateReaderMode();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        disableReaderMode();
    }

    private void updateNfcStatus() {
        String message;
        if (nfc == null) {
            message = "NFC indisponible : ce téléphone ne semble pas être équipé du NFC.";
        } else if (!nfc.isEnabled()) {
            message = "NFC désactivé. Ouvre les paramètres Android pour l’activer.";
        } else if (scanMode == MODE_READ) {
            message = "📡 Mode LIRE actif — approche une carte NFC.";
        } else if (scanMode == MODE_WRITE) {
            message = "✍️ Mode ÉCRITURE actif — approche une carte inscriptible.";
        } else {
            message = "NFC activé. Choisis LIRE ou FORMATER.";
        }
        setStatus(message);
        if (nfcStateInfo != null) {
            nfcStateInfo.setText(nfc == null ? "NFC indisponible sur ce téléphone."
                    : (nfc.isEnabled() ? "✓ NFC activé sur ce téléphone." : "⚠ NFC désactivé sur ce téléphone."));
        }
        if (nfcPageHeading != null) {
            nfcPageHeading.setText(nfc != null && nfc.isEnabled() ? "Le NFC est activé" : "Le NFC est désactivé");
        }
    }

    private void beginRead() {
        if (nfc == null) {
            toast("Ce téléphone ne possède pas de NFC.");
            showNfcPage();
            return;
        }
        scanMode = MODE_READ;
        showReadPage();
        if (!nfc.isEnabled()) {
            setStatus("Active le NFC dans les paramètres Android, puis reviens dans l’application.");
        } else {
            activateReaderMode();
        }
    }

    private void activateReaderMode() {
        if (nfc == null || !nfc.isEnabled() || scanMode == MODE_NONE) {
            return;
        }
        try {
            int flags = NfcAdapter.FLAG_READER_NFC_A
                    | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F
                    | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_NFC_BARCODE;
            nfc.enableReaderMode(this, this, flags, null);
            if (scanMode == MODE_READ) {
                setStatus("📡 Mode LIRE actif — approche une carte NFC.");
            } else {
                setStatus("✍️ Prêt à écrire « " + pendingSource + " ». Approche une carte inscriptible.");
            }
        } catch (Exception e) {
            setStatus("Impossible de démarrer le lecteur NFC : " + safeMessage(e));
        }
    }

    private void disableReaderMode() {
        if (nfc != null) {
            try { nfc.disableReaderMode(this); } catch (Exception ignored) { }
        }
    }

    private void stopScanning(String message) {
        scanMode = MODE_NONE;
        pendingMessage = null;
        disableReaderMode();
        setStatus(message);
    }

    private void openNfcSettings() {
        if (nfc == null) {
            toast("Ce téléphone ne possède pas de NFC.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Exception ex) { toast("Ouvre les Paramètres Android puis active le NFC manuellement."); }
        } catch (Exception e) {
            toast("Ouvre les Paramètres Android puis active le NFC manuellement.");
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        if (processing) return;
        processing = true;
        final int currentMode = scanMode;
        try {
            if (currentMode == MODE_WRITE) {
                NdefMessage message = pendingMessage;
                if (message == null) {
                    runOnUiThread(() -> setStatus("Aucun contenu prêt à écrire. Reviens à FORMATER."));
                } else {
                    String error = writeToTag(tag, message);
                    if (error != null) {
                        runOnUiThread(() -> {
                            setStatus("Écriture impossible : " + error);
                            toast("Écriture impossible : " + error);
                        });
                    } else {
                        final String writtenSource = pendingSource;
                        ReadData data = readTagData(tag);
                        if (data.rawMessage == null || data.rawMessage.isEmpty()) {
                            data.rawMessage = Base64.encodeToString(message.toByteArray(), Base64.NO_WRAP);
                        }
                        saveCard(data.uid, data.tech, data.display, data.rawMessage);
                        runOnUiThread(() -> {
                            scanMode = MODE_NONE;
                            pendingMessage = null;
                            disableReaderMode();
                            lastCardUid = data.uid;
                            lastCardDisplay = data.display;
                            showCardResult(data, true, writtenSource);
                            toast("Carte écrite avec succès !");
                        });
                    }
                }
            } else if (currentMode == MODE_READ) {
                ReadData data = readTagData(tag);
                saveCard(data.uid, data.tech, data.display, data.rawMessage);
                runOnUiThread(() -> {
                    scanMode = MODE_NONE;
                    disableReaderMode();
                    lastCardUid = data.uid;
                    lastCardDisplay = data.display;
                    showCardResult(data, false, null);
                    toast("Carte NFC enregistrée dans l’historique.");
                });
            }
        } catch (Exception e) {
            final String error = safeMessage(e);
            runOnUiThread(() -> setStatus("Erreur NFC : " + error));
        } finally {
            mainHandler.postDelayed(() -> processing = false, 1100);
        }
    }

    private ReadData readTagData(Tag tag) {
        ReadData data = new ReadData();
        data.uid = bytesToHex(tag.getId());
        String[] techs = tag.getTechList();
        StringBuilder result = new StringBuilder();
        result.append("UID / identifiant exposé par Android :\n").append(data.uid).append("\n\n");
        result.append("Technologies NFC :\n");
        if (techs == null || techs.length == 0) {
            result.append("Aucune technologie signalée\n");
        } else {
            for (String tech : techs) {
                int dot = tech.lastIndexOf('.');
                result.append("• ").append(dot >= 0 ? tech.substring(dot + 1) : tech).append('\n');
            }
        }
        data.tech = techs == null ? "[]" : Arrays.toString(techs);

        NdefRead ndefRead = readNdef(tag);
        data.rawMessage = ndefRead.rawBase64;
        result.append("\nDonnées NDEF :\n").append(ndefRead.display);
        if (Ndef.get(tag) == null) {
            result.append("\n\nRemarque : l'UID ne permet pas de copier la carte. Seules les données NDEF peuvent être recopiées sur une carte compatible.");
        }
        data.display = result.toString();
        return data;
    }

    private NdefRead readNdef(Tag tag) {
        NdefRead result = new NdefRead();
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            result.display = "Pas de données NDEF lisibles (carte non NDEF, protégée ou technologie non prise en charge).";
            return result;
        }
        try {
            ndef.connect();
            NdefMessage message = ndef.getNdefMessage();
            if (message == null) {
                result.display = "Aucun message NDEF sur cette carte.";
                return result;
            }
            result.rawBase64 = Base64.encodeToString(message.toByteArray(), Base64.NO_WRAP);
            StringBuilder text = new StringBuilder();
            NdefRecord[] records = message.getRecords();
            if (records.length == 0) {
                text.append("Message NDEF vide.");
            } else {
                for (int i = 0; i < records.length; i++) {
                    text.append("Enregistrement ").append(i + 1).append(" :\n");
                    text.append(parseRecord(records[i])).append("\n\n");
                }
            }
            result.display = text.toString().trim();
        } catch (Exception e) {
            result.display = "NDEF détecté mais lecture impossible : " + safeMessage(e);
        } finally {
            try {
                ndef.close();
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private String writeToTag(Tag tag, NdefMessage message) {
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                if (!ndef.isWritable()) {
                    return "la carte est verrouillée en écriture";
                }
                int size = message.toByteArray().length;
                if (size > ndef.getMaxSize()) {
                    return "contenu trop volumineux (" + size + " octets, capacité " + ndef.getMaxSize() + " octets)";
                }
                ndef.writeNdefMessage(message);
                return null;
            } catch (IOException | FormatException | SecurityException e) {
                return safeMessage(e);
            } finally {
                try {
                    ndef.close();
                } catch (Exception ignored) {
                }
            }
        }

        NdefFormatable formatable = NdefFormatable.get(tag);
        if (formatable != null) {
            try {
                formatable.connect();
                formatable.format(message);
                return null;
            } catch (IOException | FormatException | SecurityException e) {
                return safeMessage(e);
            } finally {
                try {
                    formatable.close();
                } catch (Exception ignored) {
                }
            }
        }
        return "cette carte ne semble pas compatible avec l'écriture NDEF. Les cartes sécurisées/accès ne peuvent pas être clonées par cette fonction";
    }

    private String parseRecord(NdefRecord record) {
        try {
            short tnf = record.getTnf();
            byte[] type = record.getType();
            byte[] payload = record.getPayload();
            if (tnf == NdefRecord.TNF_WELL_KNOWN
                    && Arrays.equals(type, NdefRecord.RTD_TEXT)
                    && payload != null && payload.length > 1) {
                int statusByte = payload[0] & 0xFF;
                int languageLength = statusByte & 0x3F;
                boolean utf16 = (statusByte & 0x80) != 0;
                if (1 + languageLength > payload.length) {
                    return "Texte : données invalides";
                }
                Charset charset = Charset.forName(utf16 ? "UTF-16" : "UTF-8");
                String lang = new String(payload, 1, languageLength, Charset.forName("US-ASCII"));
                String body = new String(payload, 1 + languageLength, payload.length - 1 - languageLength, charset);
                return "📝 Texte (" + lang + ") : " + body;
            }
            if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_URI)) {
                return "🔗 URI : " + parseUriPayload(payload);
            }
            String mime = type == null ? "" : new String(type, Charset.forName("US-ASCII"));
            return "Type TNF : " + tnf + "\nType : " + mime + "\nPayload hex : " + bytesToHex(payload);
        } catch (Exception e) {
            return "Enregistrement NDEF illisible";
        }
    }

    private String parseUriPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "URI vide";
        }
        String[] prefixes = {
                "", "http://www.", "https://www.", "http://", "https://",
                "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
                "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://",
                "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://",
                "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
                "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:"
        };
        int index = payload[0] & 0xFF;
        String prefix = index < prefixes.length ? prefixes[index] : "";
        return prefix + new String(payload, 1, payload.length - 1, Charset.forName("UTF-8"));
    }

    private void showFormatOptions() {
        if (nfc == null) {
            toast("Ce téléphone ne possède pas de NFC.");
            showNfcPage();
            return;
        }
        selectedContentType = 0;
        showFormatTypePage();
    }

    private void showFormatTypePage() {
        currentPage = "format_types";
        createPage("Formater une carte NFC", true);
        pageContent.addView(text("Choisis les données à écrire sur une carte compatible.", 15, MUTED, false), marginParams(-1, -2, 0, 0, 0, 15));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button byContent = button("✎  Par contenu", BLUE);
        Button fromHistory = button("◷  Depuis l’historique", PANEL_LIGHT);
        tabs.addView(byContent, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(fromHistory, new LinearLayout.LayoutParams(0, dp(58), 1));
        pageContent.addView(tabs, marginParams(-1, -2, 0, 0, 0, 18));
        byContent.setOnClickListener(v -> showFormatTypePage());
        fromHistory.setOnClickListener(v -> showHistoryPicker());

        pageContent.addView(text("Type de données", 17, WHITE, true), marginParams(-1, -2, 0, 0, 0, 9));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        String[] names = {"Texte", "URL / lien", "Numéro de téléphone", "E-mail", "Nombre / code"};
        String[] examples = {"Ex. : Bonjour, NFC…", "Ex. : https://exemple.fr", "Ex. : +33 612345678", "Ex. : contact@exemple.fr", "Texte simple contenant un nombre ou un code"};
        String[] icons = {"T", "↗", "☎", "✉", "#"};
        final RadioButton[] typeRadios = new RadioButton[names.length];
        for (int i = 0; i < names.length; i++) {
            final int type = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), dp(10), dp(11), dp(10));
            row.setBackground(round(PANEL, 13));
            TextView icon = text(icons[i], 19, WHITE, true);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(round(i == 0 ? BLUE : PANEL_LIGHT, 11));
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(10), 0, 0, 0);
            labels.addView(text(names[i], 15, WHITE, true));
            labels.addView(text(examples[i], 12, MUTED, false));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            RadioButton rb = new RadioButton(this);
            rb.setId(300 + i);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
            rb.setChecked(i == selectedContentType);
            typeRadios[i] = rb;
            row.addView(rb);
            group.addView(row, marginParams(-1, -2, 0, 0, 0, 7));
            row.setOnClickListener(v -> {
                selectedContentType = type;
                for (int j = 0; j < typeRadios.length; j++) if (typeRadios[j] != null) typeRadios[j].setChecked(j == type);
            });
            rb.setOnClickListener(v -> {
                selectedContentType = type;
                for (int j = 0; j < typeRadios.length; j++) if (typeRadios[j] != null) typeRadios[j].setChecked(j == type);
            });
        }
        for (int j = 0; j < typeRadios.length; j++) if (typeRadios[j] != null) typeRadios[j].setChecked(j == selectedContentType);
        Button next = button("Continuer  →", BLUE);
        next.setOnClickListener(v -> showNewContentPage(selectedContentType));
        pageContent.addView(next, marginParams(-1, dp(54), 0, 14, 0, 10));
        TextView warning = text("Attention : l’écriture remplace le contenu NDEF existant. Elle ne copie ni l’UID ni les protections d’une carte.", 12, MUTED, false);
        warning.setPadding(dp(12), dp(12), dp(12), dp(12));
        warning.setBackground(round(PANEL_LIGHT, 12));
        pageContent.addView(warning);
    }

    private void showNewContentPage(int type) {
        selectedContentType = type;
        currentPage = "write_content";
        createPage("Contenu à écrire", true);
        String[] names = {"Texte", "URL / lien", "Numéro de téléphone", "E-mail", "Nombre / code"};
        String[] hints = {"Saisis ton texte…", "https://exemple.fr", "+33 612345678", "contact@exemple.fr", "Saisis le nombre ou le code…"};
        String[] icons = {"T", "↗", "☎", "✉", "#"};
        LinearLayout card = panel();
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(icons[type], 24, WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(BLUE, 12));
        typeRow.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout typeText = new LinearLayout(this);
        typeText.setOrientation(LinearLayout.VERTICAL);
        typeText.setPadding(dp(12), 0, 0, 0);
        typeText.addView(text("Type : " + names[type], 17, WHITE, true));
        typeText.addView(text("Le contenu sera enregistré en NDEF", 12, MUTED, false));
        typeRow.addView(typeText);
        card.addView(typeRow, marginParams(-1, -2, 0, 0, 0, 18));
        card.addView(text("Données", 15, WHITE, true), marginParams(-1, -2, 0, 0, 0, 7));
        EditText input = new EditText(this);
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setHint(hints[type]);
        input.setTextSize(15);
        input.setMinLines(type == 0 || type == 4 ? 4 : 2);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(round(Color.rgb(12, 29, 49), 11));
        card.addView(input, marginParams(-1, -2, 0, 0, 0, 9));
        TextView byteHint = text("Taille estimée : environ 0 octet", 12, MUTED, false);
        card.addView(byteHint);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                byteHint.setText("Longueur : " + s.length() + " caractères • environ " + s.toString().getBytes(Charset.forName("UTF-8")).length + " octets");
            }
            public void afterTextChanged(Editable e) { }
        });
        pageContent.addView(card, marginParams(-1, -2, 0, 0, 0, 15));
        Button write = button("✎  Écrire sur la carte", BLUE);
        write.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("Saisis un contenu"); return; }
            try {
                NdefMessage message = createMessage(type, value);
                confirmAndArmWrite(message, names[type]);
            } catch (Exception e) {
                toast("Contenu invalide : " + safeMessage(e));
            }
        });
        pageContent.addView(write, marginParams(-1, dp(54), 0, 2, 0, 9));
        TextView note = text("Approche la carte cible uniquement après confirmation. La carte doit être compatible et inscriptible.", 13, MUTED, false);
        pageContent.addView(note, marginParams(-1, -2, 0, 0, 0, 8));
    }

    private void showHistoryPicker() {
        currentPage = "format_history";
        createPage("Formater depuis l’historique", true);
        pageContent.addView(text("Sélectionne une carte dont les données NDEF lisibles seront reprises sur une autre carte compatible.", 14, MUTED, false), marginParams(-1, -2, 0, 0, 0, 12));
        EditText search = new EditText(this);
        search.setTextColor(WHITE);
        search.setHintTextColor(MUTED);
        search.setSingleLine(true);
        search.setHint("⌕  Rechercher une carte…");
        search.setPadding(dp(13), dp(10), dp(13), dp(10));
        search.setBackground(round(PANEL_LIGHT, 13));
        pageContent.addView(search, marginParams(-1, dp(48), 0, 0, 0, 12));
        pickerList = new LinearLayout(this);
        pickerList.setOrientation(LinearLayout.VERTICAL);
        pageContent.addView(pickerList, new LinearLayout.LayoutParams(-1, -2));
        pickerSelection = text("Aucune carte sélectionnée", 13, MUTED, false);
        pickerSelection.setPadding(dp(4), dp(12), dp(4), dp(8));
        pageContent.addView(pickerSelection);
        selectedHistoryIndex = -1;
        pickerQuery = "";
        refreshHistoryPicker();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            public void onTextChanged(CharSequence s, int st, int before, int count) { pickerQuery = s.toString(); refreshHistoryPicker(); }
            public void afterTextChanged(Editable e) { }
        });
        Button write = button("Formater avec cette carte", BLUE);
        write.setOnClickListener(v -> {
            if (selectedHistoryIndex < 0) { toast("Sélectionne une carte dans la liste."); return; }
            writeFromHistory(selectedHistoryIndex);
        });
        pageContent.addView(write, marginParams(-1, dp(54), 0, 10, 0, 10));
    }

    private void refreshHistoryPicker() {
        if (pickerList == null) return;
        pickerList.removeAllViews();
        JSONArray array = getCards();
        int shown = 0;
        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject card = array.optJSONObject(i);
            if (card == null) continue;
            String name = card.optString("name", "").trim();
            if (name.isEmpty()) name = "Carte NFC";
            final String displayName = name;
            String uid = card.optString("uid", "?");
            if (!(displayName + " " + uid).toLowerCase(Locale.ROOT).contains(pickerQuery.toLowerCase(Locale.ROOT))) continue;
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(round(index == selectedHistoryIndex ? Color.rgb(20, 68, 111) : PANEL, 13));
            TextView icon = text("▣", 20, WHITE, true);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(round(BLUE, 10));
            row.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(10), 0, 0, 0);
            labels.addView(text(displayName, 15, WHITE, true));
            labels.addView(text("UID : " + uid + "\n" + shortDate(card.optString("date", "")), 11, MUTED, false));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(text(index == selectedHistoryIndex ? "◉" : "○", 22, BLUE, true));
            row.setOnClickListener(v -> {
                selectedHistoryIndex = index;
                if (pickerSelection != null) pickerSelection.setText("Sélectionnée : " + displayName);
                refreshHistoryPicker();
            });
            pickerList.addView(row, marginParams(-1, -2, 0, 0, 0, 7));
            shown++;
        }
        if (shown == 0) {
            TextView empty = text(array.length() == 0 ? "L’historique est vide. Scanne une carte NDEF pour commencer." : "Aucun résultat.", 14, MUTED, false);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            empty.setBackground(round(PANEL, 12));
            pickerList.addView(empty);
        }
        if (pickerSelection != null && selectedHistoryIndex >= 0) {
            JSONObject selected = getCards().optJSONObject(selectedHistoryIndex);
            if (selected != null) {
                String name = selected.optString("name", "").trim();
                pickerSelection.setText("Sélectionnée : " + (name.isEmpty() ? "Carte NFC" : name));
            }
        }
    }

    private NdefMessage createMessage(int type, String content) {
        NdefRecord record;
        if (type == 1) {
            String url = content;
            if (!url.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                url = "https://" + url;
            }
            record = NdefRecord.createUri(Uri.parse(url));
        } else if (type == 2) {
            String phone = content.replace(" ", "");
            if (!phone.startsWith("tel:")) phone = "tel:" + phone;
            record = NdefRecord.createUri(Uri.parse(phone));
        } else if (type == 3) {
            String email = content;
            if (!email.startsWith("mailto:")) email = "mailto:" + email;
            record = NdefRecord.createUri(Uri.parse(email));
        } else {
            record = NdefRecord.createTextRecord(Locale.getDefault().getLanguage(), content);
        }
        return new NdefMessage(new NdefRecord[]{record});
    }

    private void writeFromHistory(int index) {
        try {
            JSONObject card = getCards().getJSONObject(index);
            NdefMessage message = messageFromHistory(card);
            if (message == null) {
                new AlertDialog.Builder(this)
                        .setTitle("Copie impossible")
                        .setMessage("Aucune donnée NDEF réutilisable n'a été enregistrée pour cette carte. Son UID matériel ne peut pas être copié. Scanne une carte NDEF lisible, puis réessaie.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            String name = card.optString("name", "").trim();
            if (name.isEmpty()) name = "carte de l'historique";
            confirmAndArmWrite(message, "copie de « " + name + " »");
        } catch (Exception e) {
            toast("Impossible de charger cette carte : " + safeMessage(e));
        }
    }

    private NdefMessage messageFromHistory(JSONObject card) {
        String encoded = card.optString("ndefBase64", "");
        if (!encoded.isEmpty()) {
            try {
                return new NdefMessage(Base64.decode(encoded, Base64.DEFAULT));
            } catch (Exception ignored) {
            }
        }
        return messageFromLegacyDisplay(card.optString("records", ""));
    }

    private NdefMessage messageFromLegacyDisplay(String display) {
        if (display == null) return null;
        String[] lines = display.split("\\r?\\n");
        for (String line : lines) {
            int marker = line.indexOf(" : ");
            if (marker < 0 || marker + 3 >= line.length()) continue;
            String value = line.substring(marker + 3).trim();
            if (line.contains("📝 Texte") && !value.isEmpty()) {
                return createMessage(0, value);
            }
            if (line.contains("🔗 URI") && !value.isEmpty()) {
                return createMessage(1, value);
            }
        }
        return null;
    }

    private void confirmAndArmWrite(NdefMessage message, String source) {
        int bytes = message.toByteArray().length;
        new AlertDialog.Builder(this)
                .setTitle("Confirmer l’écriture")
                .setMessage("Contenu : " + source + "\nTaille estimée : " + bytes + " octets.\n\nL’écriture remplace les données NDEF déjà présentes sur la carte cible. Elle ne copie pas l’UID ni les clés d’une carte sécurisée. Continuer ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Préparer l’écriture", (dialog, which) -> {
                    pendingMessage = message;
                    pendingSource = source;
                    scanMode = MODE_WRITE;
                    showWriteWaitingPage();
                    if (nfc == null) {
                        toast("Ce téléphone ne possède pas de NFC.");
                        scanMode = MODE_NONE;
                    } else if (!nfc.isEnabled()) {
                        setStatus("Active le NFC dans les paramètres, puis reviens ici pour écrire.");
                    } else {
                        activateReaderMode();
                    }
                })
                .show();
    }

    private void showWriteWaitingPage() {
        currentPage = "write_wait";
        createPage("Écrire sur une carte", true);
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(10), dp(22), dp(10), dp(16));
        TextView icon = text("✎ )))", 32, WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Color.rgb(18, 67, 111), 100));
        hero.addView(icon, marginParams(dp(135), dp(135), 0, 0, 0, 22));
        TextView title = text("Approche la carte cible", 22, WHITE, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, marginParams(-1, -2, 0, 0, 0, 9));
        TextView description = text("Contenu prêt : " + pendingSource + "\nGarde la carte contre le téléphone jusqu’à la confirmation.", 15, MUTED, false);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(dp(3), 1f);
        hero.addView(description);
        pageContent.addView(hero, marginParams(-1, -2, 0, 0, 0, 12));
        status = text("", 14, WHITE, true);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackground(round(PANEL, 14));
        pageContent.addView(status, marginParams(-1, -2, 0, 0, 0, 12));
        Button settings = button("Activer / régler le NFC", PANEL_LIGHT);
        settings.setOnClickListener(v -> openNfcSettings());
        pageContent.addView(settings, marginParams(-1, dp(48), 0, 0, 0, 9));
        Button cancel = button("Annuler l’écriture", RED);
        cancel.setOnClickListener(v -> { stopScanning("Écriture annulée."); showHome(); });
        pageContent.addView(cancel, marginParams(-1, dp(48), 0, 0, 0, 5));
        updateNfcStatus();
    }

    private void saveCard(String uid, String tech, String records, String rawNdef) {
        try {
            JSONArray array = getCards();
            JSONObject card = null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject old = array.getJSONObject(i);
                if (uid.equals(old.optString("uid"))) {
                    card = old;
                    break;
                }
            }
            if (card == null) {
                card = new JSONObject();
                card.put("uid", uid);
                card.put("name", "");
                array.put(card);
            }
            card.put("tech", tech);
            card.put("records", records);
            card.put("ndefBase64", rawNdef == null ? "" : rawNdef);
            card.put("date", dateFormat.format(new Date()));
            prefs.edit().putString("data", array.toString()).apply();
            runOnUiThread(this::refreshHistory);
        } catch (Exception e) {
            runOnUiThread(() -> toast("Erreur lors de l'enregistrement de la carte."));
        }
    }

    private JSONArray getCards() {
        try {
            return new JSONArray(prefs.getString("data", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void showHistoryPage() {
        currentPage = "history";
        historyQuery = "";
        createPage("Historique des cartes", true);
        EditText search = new EditText(this);
        search.setTextColor(WHITE);
        search.setHintTextColor(MUTED);
        search.setSingleLine(true);
        search.setHint("⌕  Rechercher une carte…");
        search.setPadding(dp(13), dp(10), dp(13), dp(10));
        search.setBackground(round(PANEL_LIGHT, 13));
        pageContent.addView(search, marginParams(-1, dp(48), 0, 0, 0, 12));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("Cartes enregistrées", 18, WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = button("Effacer", RED);
        clear.setTextSize(12);
        heading.addView(clear, new LinearLayout.LayoutParams(-2, dp(38)));
        clear.setOnClickListener(v -> confirmClearHistory());
        pageContent.addView(heading, marginParams(-1, -2, 0, 0, 0, 10));
        history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        pageContent.addView(history, new LinearLayout.LayoutParams(-1, -2));
        refreshHistory();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            public void onTextChanged(CharSequence s, int st, int before, int count) { historyQuery = s.toString(); refreshHistory(); }
            public void afterTextChanged(Editable e) { }
        });
        addBottomNav();
    }

    private void refreshHistory() {
        if (history == null) return;
        history.removeAllViews();
        JSONArray array = getCards();
        int shown = 0;
        for (int i = array.length() - 1; i >= 0; i--) {
            final int index = i;
            try {
                JSONObject card = array.getJSONObject(i);
                String name = card.optString("name", "").trim();
                if (name.isEmpty()) name = "Carte NFC";
                String uid = card.optString("uid", "?");
                if (!(name + " " + uid + " " + card.optString("records", "")).toLowerCase(Locale.ROOT).contains(historyQuery.toLowerCase(Locale.ROOT))) continue;

                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp(13), dp(12), dp(13), dp(12));
                item.setBackground(round(PANEL, 15));
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView icon = text(historyIcon(index), 22, WHITE, true);
                icon.setGravity(Gravity.CENTER);
                icon.setBackground(round(historyColor(index), 12));
                row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout labels = new LinearLayout(this);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(11), 0, 0, 0);
                labels.addView(text(name, 16, WHITE, true));
                labels.addView(text("UID : " + uid + "\n" + shortDate(card.optString("date", "")), 12, MUTED, false));
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                TextView more = text("⋮", 24, MUTED, true);
                more.setGravity(Gravity.CENTER);
                row.addView(more, new LinearLayout.LayoutParams(dp(26), dp(44)));
                row.setOnClickListener(v -> showCardDetails(index));
                more.setOnClickListener(v -> showCardDetails(index));
                item.addView(row, marginParams(-1, -2, 0, 0, 0, 10));

                LinearLayout buttons = new LinearLayout(this);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                Button detailsBtn = button("Détails", PANEL_LIGHT);
                detailsBtn.setTextSize(12);
                detailsBtn.setOnClickListener(v -> showCardDetails(index));
                Button rename = button("✎ Renommer", PANEL_LIGHT);
                rename.setTextSize(12);
                rename.setOnClickListener(v -> renameCard(index));
                Button copy = button("Copier / écrire", BLUE);
                copy.setTextSize(12);
                copy.setOnClickListener(v -> writeFromHistory(index));
                buttons.addView(detailsBtn, new LinearLayout.LayoutParams(0, dp(42), 1));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(42), 1);
                rp.leftMargin = dp(5); rp.rightMargin = dp(5);
                buttons.addView(rename, rp);
                buttons.addView(copy, new LinearLayout.LayoutParams(0, dp(42), 1));
                item.addView(buttons);
                history.addView(item, marginParams(-1, -2, 0, 0, 0, 9));
                shown++;
            } catch (Exception ignored) { }
        }
        if (shown == 0) {
            TextView empty = text(array.length() == 0 ? "Aucune carte enregistrée pour le moment.\n\nAppuie sur LIRE pour scanner ta première carte." : "Aucune carte ne correspond à ta recherche.", 14, MUTED, false);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            empty.setBackground(round(PANEL, 13));
            history.addView(empty);
        }
    }

    private int historyColor(int index) {
        int[] colors = {BLUE, Color.rgb(32, 170, 105), Color.rgb(255, 136, 0), Color.rgb(133, 78, 235), Color.rgb(0, 172, 193)};
        return colors[Math.abs(index) % colors.length];
    }

    private String historyIcon(int index) {
        String[] icons = {"▣", "⌂", "🚗", "↗", "☎"};
        return icons[Math.abs(index) % icons.length];
    }

    private void showCardResult(ReadData data, boolean written, String source) {
        currentPage = "card_result";
        createPage(written ? "Écriture réussie" : "Carte NFC détectée", true);
        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        TextView check = text(written ? "✓" : "✓", 28, WHITE, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(round(GREEN, 100));
        hero.addView(check, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(12), 0, 0, 0);
        heroText.addView(text(written ? "Données écrites" : "Carte NFC détectée", 20, WHITE, true));
        heroText.addView(text(written ? "Source : " + source : "Lecture terminée et enregistrée", 12, MUTED, false));
        hero.addView(heroText);
        pageContent.addView(hero, marginParams(-1, -2, 0, 0, 0, 18));

        int index = findCardIndexByUid(data.uid);
        String cardName = index >= 0 ? cardNameAt(index) : "Carte NFC";
        LinearLayout namePanel = panel();
        namePanel.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("Nom de la carte\n" + cardName, 15, WHITE, true);
        namePanel.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button rename = button("✎", BLUE);
        rename.setOnClickListener(v -> { int idx = findCardIndexByUid(data.uid); if (idx >= 0) renameCard(idx); });
        namePanel.addView(rename, new LinearLayout.LayoutParams(dp(48), dp(44)));
        pageContent.addView(namePanel, marginParams(-1, -2, 0, 0, 0, 10));

        addInfoPanel("UID / identifiant", data.uid);
        String techText = prettyTechnologies(data.tech);
        addInfoPanel("Technologies NFC", techText);
        String ndef = data.display;
        int marker = ndef.indexOf("Données NDEF :");
        if (marker >= 0) ndef = ndef.substring(marker + "Données NDEF :".length()).trim();
        addInfoPanel("Données NDEF", ndef.isEmpty() ? "Aucune donnée NDEF affichée." : ndef);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("▢  Copier", PANEL_LIGHT);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Informations NFC", data.display));
                toast("Informations copiées.");
            }
        });
        Button done = button("Historique", BLUE);
        done.setOnClickListener(v -> showHistoryPage());
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        doneParams.leftMargin = dp(8);
        actions.addView(done, doneParams);
        pageContent.addView(actions, marginParams(-1, -2, 0, 15, 0, 8));
        if (index >= 0) {
            Button format = button("Copier / écrire ces données sur une autre carte", Color.rgb(85, 75, 230));
            format.setOnClickListener(v -> writeFromHistory(findCardIndexByUid(data.uid)));
            pageContent.addView(format, marginParams(-1, dp(50), 0, 0, 0, 10));
        }
    }

    private void addInfoPanel(String title, String value) {
        LinearLayout box = panel();
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 14, MUTED, true), marginParams(-1, -2, 0, 0, 0, 7));
        TextView val = text(value == null || value.trim().isEmpty() ? "Non disponible" : value, 14, WHITE, false);
        val.setTextIsSelectable(true);
        val.setLineSpacing(dp(3), 1f);
        box.addView(val);
        pageContent.addView(box, marginParams(-1, -2, 0, 0, 0, 9));
    }

    private String prettyTechnologies(String raw) {
        if (raw == null || raw.isEmpty()) return "Non disponible";
        return raw.replace("[", "").replace("]", "").replace(", ", "\n• ")
                .replace("android.nfc.tech.", "");
    }

    private int findCardIndexByUid(String uid) {
        JSONArray array = getCards();
        for (int i = 0; i < array.length(); i++) {
            JSONObject card = array.optJSONObject(i);
            if (card != null && uid != null && uid.equals(card.optString("uid"))) return i;
        }
        return -1;
    }

    private String cardNameAt(int index) {
        JSONObject card = getCards().optJSONObject(index);
        if (card == null) return "Carte NFC";
        String name = card.optString("name", "").trim();
        return name.isEmpty() ? "Carte NFC" : name;
    }

    private void showCardDetails(int index) {
        JSONObject card = getCards().optJSONObject(index);
        if (card == null) { toast("Carte introuvable."); return; }
        currentPage = "card_details";
        createPage("Détails de la carte", true);
        String name = card.optString("name", "").trim();
        if (name.isEmpty()) name = "Carte NFC";
        TextView title = text(name, 22, WHITE, true);
        pageContent.addView(title, marginParams(-1, -2, 0, 0, 0, 14));
        addInfoPanel("UID / identifiant", card.optString("uid", ""));
        addInfoPanel("Dernier scan", card.optString("date", ""));
        addInfoPanel("Technologies NFC", prettyTechnologies(card.optString("tech", "")));
        addInfoPanel("Informations lues", card.optString("records", "Aucune donnée enregistrée."));
        Button rename = button("✎  Renommer la carte", PANEL_LIGHT);
        rename.setOnClickListener(v -> renameCard(index));
        pageContent.addView(rename, marginParams(-1, dp(48), 0, 10, 0, 8));
        Button copy = button("Copier / écrire sur une autre carte", BLUE);
        copy.setOnClickListener(v -> writeFromHistory(index));
        pageContent.addView(copy, marginParams(-1, dp(50), 0, 0, 0, 8));
        Button back = button("Retour à l’historique", PANEL_LIGHT);
        back.setOnClickListener(v -> showHistoryPage());
        pageContent.addView(back, marginParams(-1, dp(46), 0, 0, 0, 8));
    }

    private void renameCard(int index) {
        try {
            JSONArray array = getCards();
            JSONObject card = array.getJSONObject(index);
            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setTextColor(WHITE);
            input.setHintTextColor(MUTED);
            input.setHint("Ex. : Badge du travail");
            input.setText(card.optString("name", ""));
            input.setPadding(dp(12), dp(10), dp(12), dp(10));
            input.setBackground(round(PANEL_LIGHT, 10));
            LinearLayout container = new LinearLayout(this);
            container.setPadding(dp(20), dp(8), dp(20), 0);
            container.addView(input, new LinearLayout.LayoutParams(-1, -2));
            new AlertDialog.Builder(this)
                    .setTitle("Renommer la carte")
                    .setView(container)
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Enregistrer", (dialog, which) -> {
                        try {
                            card.put("name", input.getText().toString().trim());
                            prefs.edit().putString("data", array.toString()).apply();
                            refreshHistory();
                            if ("card_details".equals(currentPage)) showCardDetails(index);
                        } catch (Exception ignored) {
                        }
                    })
                    .show();
        } catch (Exception e) {
            toast("Impossible de renommer cette carte.");
        }
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Effacer l'historique ?")
                .setMessage("Toutes les cartes enregistrées dans l'application seront supprimées. Cela n'efface pas les cartes NFC physiques.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Effacer", (dialog, which) -> {
                    prefs.edit().remove("data").apply();
                    lastCardDisplay = "";
                    lastCardUid = "";
                    refreshHistory();
                    toast("Historique effacé.");
                    if ("history".equals(currentPage)) showHistoryPage();
                    else if ("home".equals(currentPage)) showHome();
                })
                .show();
    }

    private String shortDate(String date) {
        if (date == null || date.length() < 16) return date == null ? "" : date;
        return date.substring(0, 16);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(WHITE);
        button.setPadding(dp(8), dp(7), dp(8), dp(7));
        button.setBackground(round(color, 12));
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
        if (nfcStateInfo != null) {
            if (nfc == null) nfcStateInfo.setText("NFC indisponible sur ce téléphone.");
            else nfcStateInfo.setText(nfc.isEnabled() ? "✓ NFC activé sur ce téléphone." : "⚠ NFC désactivé sur ce téléphone.");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "(vide)";
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.US, "%02X", value));
        return result.toString();
    }

    private String safeMessage(Exception e) {
        String value = e.getMessage();
        if (value == null || value.trim().isEmpty()) value = e.getClass().getSimpleName();
        return value;
    }

    private static class ReadData {
        String uid = "";
        String tech = "[]";
        String display = "";
        String rawMessage = "";
    }

    private static class NdefRead {
        String display = "";
        String rawBase64 = "";
    }
}
