package com.example.nfccardreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        pageScroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_nfc_logo);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(62), dp(62));
        logoParams.rightMargin = dp(13);
        header.addView(logo, logoParams);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("NFC Card Reader", 23, WHITE, true);
        brand.addView(title);
        TextView subtitle = text("Lire  •  Renommer  •  Écrire", 13, MUTED, false);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header, marginParams(-1, -2, 0, 0, 0, 18));

        status = text("Initialisation du NFC…", 15, WHITE, true);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackground(round(PANEL_LIGHT, 14));
        root.addView(status, marginParams(-1, -2, 0, 0, 0, 12));

        Button nfcSettings = button("⚡  Activer / régler le NFC", BLUE);
        nfcSettings.setOnClickListener(v -> openNfcSettings());
        root.addView(nfcSettings, marginParams(-1, -2, 0, 0, 0, 9));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button read = button("⌕  LIRE", BLUE);
        read.setOnClickListener(v -> beginRead());
        Button format = button("✎  FORMATER", GREEN);
        format.setOnClickListener(v -> showFormatOptions());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(54), 1);
        half.rightMargin = dp(6);
        actions.addView(read, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(54), 1);
        half2.leftMargin = dp(6);
        actions.addView(format, half2);
        root.addView(actions, marginParams(-1, -2, 0, 0, 0, 8));

        Button stop = button("Arrêter la lecture / écriture", PANEL_LIGHT);
        stop.setOnClickListener(v -> stopScanning("Lecture / écriture arrêtée."));
        root.addView(stop, marginParams(-1, -2, 0, 0, 0, 17));

        TextView detailsTitle = text("Dernière carte détectée", 19, WHITE, true);
        root.addView(detailsTitle, marginParams(-1, -2, 0, 0, 0, 8));

        details = text("Appuie sur LIRE, puis approche une carte du dos du téléphone.\n\nAvec FORMATER, tu peux écrire un contenu ou recopier les données NDEF d'une carte enregistrée.", 14, MUTED, false);
        details.setPadding(dp(14), dp(14), dp(14), dp(14));
        details.setMinHeight(dp(100));
        details.setBackground(round(PANEL, 14));
        root.addView(details, marginParams(-1, -2, 0, 0, 0, 22));

        LinearLayout historyHeader = new LinearLayout(this);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView historyTitle = text("Cartes enregistrées", 19, WHITE, true);
        historyHeader.addView(historyTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = button("Effacer", RED);
        clear.setTextSize(12);
        historyHeader.addView(clear, new LinearLayout.LayoutParams(-2, dp(40)));
        clear.setOnClickListener(v -> confirmClearHistory());
        root.addView(historyHeader, marginParams(-1, -2, 0, 0, 0, 10));

        history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        root.addView(history, new LinearLayout.LayoutParams(-1, -2));

        TextView footer = text("Hors ligne • Les données restent sur le téléphone\nL'écriture dépend du type et de la protection de la carte.", 12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, dp(2));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setContentView(pageScroll);
        refreshHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scanMode != MODE_NONE) {
            if (nfc != null && nfc.isEnabled()) {
                activateReaderMode();
            } else {
                updateNfcStatus();
            }
        } else {
            updateNfcStatus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        disableReaderMode();
    }

    private void updateNfcStatus() {
        if (nfc == null) {
            setStatus("NFC indisponible : ce téléphone ne semble pas être équipé du NFC.");
        } else if (!nfc.isEnabled()) {
            setStatus("NFC désactivé. Appuie sur « Activer / régler le NFC ».");
        } else if (scanMode == MODE_READ) {
            setStatus("Mode LIRE actif — approche une carte du dos du téléphone.");
        } else if (scanMode == MODE_WRITE) {
            setStatus("Mode ÉCRITURE actif — approche une carte inscriptible.");
        } else {
            setStatus("NFC activé. Choisis LIRE ou FORMATER.");
        }
    }

    private void beginRead() {
        if (nfc == null) {
            toast("Ce téléphone ne possède pas de NFC.");
            return;
        }
        scanMode = MODE_READ;
        if (!nfc.isEnabled()) {
            setStatus("Active le NFC dans les réglages, puis reviens dans l'application.");
            openNfcSettings();
            return;
        }
        activateReaderMode();
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
            try {
                nfc.disableReaderMode(this);
            } catch (Exception ignored) {
            }
        }
    }

    private void stopScanning(String message) {
        scanMode = MODE_NONE;
        pendingMessage = null;
        disableReaderMode();
        if (status != null) {
            setStatus(message);
        }
    }

    private void openNfcSettings() {
        if (nfc == null) {
            toast("Ce téléphone ne possède pas de NFC.");
            return;
        }
        if (nfc.isEnabled()) {
            toast("Le NFC est déjà activé.");
            updateNfcStatus();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ex) {
                toast("Ouvre les Paramètres Android puis active le NFC manuellement.");
            }
        } catch (Exception e) {
            toast("Ouvre les Paramètres Android puis active le NFC manuellement.");
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        if (processing) {
            return;
        }
        processing = true;
        final int currentMode = scanMode;
        try {
            if (currentMode == MODE_WRITE) {
                NdefMessage message = pendingMessage;
                if (message == null) {
                    runOnUiThread(() -> setStatus("Aucun contenu prêt à écrire. Appuie sur FORMATER."));
                } else {
                    String error = writeToTag(tag, message);
                    if (error != null) {
                        runOnUiThread(() -> {
                            setStatus("Écriture impossible : " + error + " Tu peux réessayer avec une carte compatible.");
                            toast("Écriture impossible : " + error);
                        });
                    } else {
                        final String writtenSource = pendingSource;
                        ReadData data = readTagData(tag);
                        if (data.rawMessage == null || data.rawMessage.isEmpty()) {
                            data.rawMessage = Base64.encodeToString(message.toByteArray(), Base64.NO_WRAP);
                        }
                        saveCard(data.uid, data.tech, data.display, data.rawMessage);
                        final String resultDetails = data.display;
                        runOnUiThread(() -> {
                            scanMode = MODE_NONE;
                            pendingMessage = null;
                            disableReaderMode();
                            details.setText("✅ ÉCRITURE RÉUSSIE\nSource : " + writtenSource
                                    + "\n\nRetire la carte, puis approche-la à nouveau en mode LIRE pour vérifier la lecture.\n\n"
                                    + resultDetails);
                            setStatus("Écriture terminée. Le contenu a été conservé dans l'historique.");
                            refreshHistory();
                            toast("Carte écrite avec succès !");
                        });
                    }
                }
            } else if (currentMode == MODE_READ) {
                ReadData data = readTagData(tag);
                saveCard(data.uid, data.tech, data.display, data.rawMessage);
                runOnUiThread(() -> {
                    details.setText(data.display);
                    setStatus("✅ CARTE NFC DÉTECTÉE — UID : " + data.uid);
                    refreshHistory();
                    toast("Carte NFC enregistrée dans l'historique.");
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
            return;
        }
        String[] choices = {"Créer un nouveau contenu (texte, URL, numéro, e-mail)", "Copier les données NDEF d'une carte de l'historique"};
        new AlertDialog.Builder(this)
                .setTitle("Formater / écrire une carte")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        showNewContentDialog();
                    } else {
                        showHistoryPicker();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showNewContentDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(2));

        TextView intro = text("Choisis le type, puis saisis le contenu à écrire.", 14, MUTED, false);
        form.addView(intro, marginParams(-1, -2, 0, 0, 0, 8));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        String[] types = {"Texte", "URL / lien web", "Numéro de téléphone", "Adresse e-mail", "Nombre / code"};
        for (int i = 0; i < types.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setText(types[i]);
            radio.setTextColor(WHITE);
            radio.setId(100 + i);
            group.addView(radio);
        }
        group.check(100);
        form.addView(group);

        EditText content = new EditText(this);
        content.setTextColor(WHITE);
        content.setHintTextColor(MUTED);
        content.setHint("Contenu à écrire…");
        content.setMinLines(2);
        content.setGravity(Gravity.TOP | Gravity.START);
        content.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.setBackground(round(PANEL_LIGHT, 10));
        form.addView(content, marginParams(-1, -2, 0, 12, 0, 0));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nouveau contenu NFC")
                .setView(form)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Continuer", null)
                .create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = content.getText().toString().trim();
            if (value.isEmpty()) {
                content.setError("Saisis un contenu");
                return;
            }
            int checked = group.getCheckedRadioButtonId() - 100;
            try {
                NdefMessage message = createMessage(checked, value);
                String[] typeNames = {"Texte", "URL", "numéro de téléphone", "e-mail", "nombre / code"};
                String source = checked >= 0 && checked < typeNames.length ? typeNames[checked] : "nouveau contenu";
                dialog.dismiss();
                confirmAndArmWrite(message, source);
            } catch (Exception e) {
                toast("Contenu invalide : " + safeMessage(e));
            }
        }));
        dialog.show();
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

    private void showHistoryPicker() {
        JSONArray array = getCards();
        if (array.length() == 0) {
            toast("L'historique est vide.");
            return;
        }
        final int[] originalIndices = new int[array.length()];
        String[] labels = new String[array.length()];
        int n = 0;
        for (int i = array.length() - 1; i >= 0; i--) {
            try {
                JSONObject card = array.getJSONObject(i);
                originalIndices[n] = i;
                String name = card.optString("name", "").trim();
                if (name.isEmpty()) name = "Carte NFC";
                labels[n] = name + "\nUID : " + card.optString("uid", "?") + " • " + shortDate(card.optString("date", ""));
                n++;
            } catch (Exception ignored) {
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Choisir une carte à recopier")
                .setItems(labels, (dialog, which) -> writeFromHistory(originalIndices[which]))
                .setNegativeButton("Annuler", null)
                .show();
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
                .setTitle("Confirmer l'écriture")
                .setMessage("Contenu : " + source + "\nTaille estimée : " + bytes + " octets.\n\nL'écriture remplace les données NDEF déjà présentes sur la carte cible. Elle ne copie pas l'UID ni les clés d'une carte sécurisée. Continuer ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Préparer l'écriture", (dialog, which) -> {
                    pendingMessage = message;
                    pendingSource = source;
                    scanMode = MODE_WRITE;
                    if (nfc == null) {
                        toast("Ce téléphone ne possède pas de NFC.");
                        scanMode = MODE_NONE;
                    } else if (!nfc.isEnabled()) {
                        setStatus("Active le NFC dans les réglages, puis reviens dans l'application pour écrire.");
                        openNfcSettings();
                    } else {
                        activateReaderMode();
                    }
                })
                .show();
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

    private void refreshHistory() {
        if (history == null) return;
        history.removeAllViews();
        JSONArray array = getCards();
        if (array.length() == 0) {
            TextView empty = text("Aucune carte enregistrée pour le moment.", 14, MUTED, false);
            empty.setPadding(dp(12), dp(14), dp(12), dp(14));
            empty.setBackground(round(PANEL, 12));
            history.addView(empty);
            return;
        }
        for (int i = array.length() - 1; i >= 0; i--) {
            final int index = i;
            try {
                JSONObject card = array.getJSONObject(i);
                String name = card.optString("name", "").trim();
                if (name.isEmpty()) name = "Carte NFC";
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp(13), dp(11), dp(13), dp(11));
                item.setBackground(round(PANEL, 13));

                TextView nameView = text("▣  " + name + "  › détails", 16, WHITE, true);
                nameView.setOnClickListener(v -> showCardDetails(index));
                item.addView(nameView);
                TextView id = text("UID : " + card.optString("uid", "?") + "\n" + card.optString("date", ""), 12, MUTED, false);
                id.setPadding(0, dp(5), 0, dp(8));
                item.addView(id);

                LinearLayout buttons = new LinearLayout(this);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                Button rename = button("✎ Renommer", PANEL_LIGHT);
                rename.setTextSize(12);
                rename.setOnClickListener(v -> renameCard(index));
                Button copy = button("Copier / écrire", BLUE);
                copy.setTextSize(12);
                copy.setOnClickListener(v -> writeFromHistory(index));
                LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(42), 1);
                b1.rightMargin = dp(5);
                buttons.addView(rename, b1);
                LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(42), 1);
                b2.leftMargin = dp(5);
                buttons.addView(copy, b2);
                item.addView(buttons);
                history.addView(item, marginParams(-1, -2, 0, 0, 0, 9));
            } catch (Exception ignored) {
            }
        }
    }


    private void showCardDetails(int index) {
        try {
            JSONObject card = getCards().getJSONObject(index);
            String name = card.optString("name", "").trim();
            if (name.isEmpty()) name = "Carte NFC";
            String message = "Nom : " + name
                    + "\n\nUID : " + card.optString("uid", "")
                    + "\n\nDate du dernier scan : " + card.optString("date", "")
                    + "\n\nTechnologies NFC :\n" + card.optString("tech", "")
                    + "\n\nInformations lues :\n" + card.optString("records", "")
                    + "\n\nCopie : seules les données NDEF enregistrées peuvent être réécrites. L'UID matériel n'est pas copié.";
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            toast("Impossible d'afficher les détails de cette carte.");
        }
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
                    refreshHistory();
                    toast("Historique effacé.");
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
