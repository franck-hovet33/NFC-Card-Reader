package com.example.nfccardreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.nfc.Ndef;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.*;
import android.content.SharedPreferences;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private NfcAdapter nfc;
    private SharedPreferences prefs;
    private TextView status, details;
    private LinearLayout history;
    private boolean processing = false;
    private final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cards", MODE_PRIVATE);
        nfc = NfcAdapter.getDefaultAdapter(this);
        buildUI();
        if (nfc == null) status.setText("❌ NFC non disponible");
        else if (!nfc.isEnabled()) status.setText("⚠️ NFC désactivé");
        else status.setText("📡 NFC prêt — approchez une carte");
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,25,28,20);
        TextView title = new TextView(this); title.setText("📡 NFC Card Reader"); title.setTextSize(25); title.setGravity(Gravity.CENTER); root.addView(title);
        status = new TextView(this); status.setTextSize(18); status.setPadding(0,20,0,15); root.addView(status);
        Button scan = new Button(this); scan.setText("📲 Approchez une carte NFC");
        scan.setOnClickListener(v -> { if(nfc==null) toast("NFC non disponible"); else if(!nfc.isEnabled()) toast("Activez le NFC"); else status.setText("📲 Approchez la carte…"); }); root.addView(scan);
        details = new TextView(this); details.setTextSize(16); details.setPadding(0,15,0,15); root.addView(details);
        TextView h = new TextView(this); h.setText("Cartes enregistrées"); h.setTextSize(21); root.addView(h);
        ScrollView scroll = new ScrollView(this); history = new LinearLayout(this); history.setOrientation(LinearLayout.VERTICAL); scroll.addView(history); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button clear = new Button(this); clear.setText("Effacer l'historique"); clear.setOnClickListener(v->{prefs.edit().remove("data").apply();refreshHistory();}); root.addView(clear);
        setContentView(root); refreshHistory();
    }

    @Override protected void onResume() {
        super.onResume();
        if(nfc!=null && nfc.isEnabled()) {
            int flags=NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_NFC_B|NfcAdapter.FLAG_READER_NFC_F|NfcAdapter.FLAG_READER_NFC_V;
            nfc.enableReaderMode(this,this,flags,null);
        }
    }
    @Override protected void onPause() { super.onPause(); if(nfc!=null) try{nfc.disableReaderMode(this);}catch(Exception ignored){} }

    @Override public void onTagDiscovered(Tag tag) {
        if(processing)return; processing=true;
        runOnUiThread(()->{ try{readTag(tag);}finally{new Handler().postDelayed(()->processing=false,1000);} });
    }

    private void readTag(Tag tag) {
        String uid=bytesToHex(tag.getId());
        StringBuilder result=new StringBuilder("UID : ").append(uid).append("\n\nTechnologies NFC :\n");
        for(String tech:tag.getTechList()) result.append("• ").append(tech.substring(tech.lastIndexOf('.')+1)).append("\n");
        String ndef=readNdef(tag);
        result.append("\nDonnées NDEF :\n").append(ndef);
        details.setText(result.toString()); status.setText("✅ CARTE NFC DÉTECTÉE");
        saveCard(uid,Arrays.toString(tag.getTechList()),ndef); toast("Carte NFC détectée !");
    }

    private String readNdef(Tag tag) {
        Ndef ndef=Ndef.get(tag);
        if(ndef==null)return "Cette carte ne contient pas de NDEF lisible.";
        try {
            ndef.connect(); NdefMessage message=ndef.getNdefMessage();
            if(message==null){ndef.close();return "Aucun message NDEF.";}
            StringBuilder s=new StringBuilder();
            for(NdefRecord record:message.getRecords()) s.append(parseRecord(record)).append("\n");
            ndef.close(); return s.toString();
        } catch(Exception e) { try{ndef.close();}catch(Exception ignored){} return "NDEF détecté mais lecture impossible : "+e.getMessage(); }
    }

    private String parseRecord(NdefRecord r) {
        try {
            short tnf=r.getTnf(); byte[] type=r.getType(), payload=r.getPayload();
            if(tnf==NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type,NdefRecord.RTD_TEXT) && payload.length>1) {
                int lang=payload[0]&0x3F; String charset=(payload[0]&0x80)!=0?"UTF-16":"UTF-8";
                return "📝 Texte : "+new String(payload,1+lang,payload.length-1-lang,Charset.forName(charset));
            }
            if(tnf==NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type,NdefRecord.RTD_URI) && payload.length>0)
                return "🔗 URL : "+new String(payload,1,payload.length-1,Charset.forName("UTF-8"));
            return "Type : "+bytesToHex(type)+"\nPayload : "+bytesToHex(payload);
        } catch(Exception e){return "Enregistrement illisible";}
    }

    private void saveCard(String uid,String tech,String records) {
        try {
            JSONArray a=new JSONArray(prefs.getString("data","[]"));
            for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(uid.equals(o.optString("uid"))){o.put("tech",tech);o.put("records",records);o.put("date",date.format(new Date()));prefs.edit().putString("data",a.toString()).apply();refreshHistory();return;}}
            JSONObject o=new JSONObject();o.put("uid",uid);o.put("tech",tech);o.put("records",records);o.put("date",date.format(new Date()));o.put("name","");a.put(o);prefs.edit().putString("data",a.toString()).apply();refreshHistory();
        }catch(Exception e){toast("Erreur d'enregistrement");}
    }

    private void refreshHistory() {
        history.removeAllViews();
        try { JSONArray a=new JSONArray(prefs.getString("data","[]"));
            for(int i=a.length()-1;i>=0;i--){JSONObject o=a.getJSONObject(i);String name=o.optString("name","").trim();TextView t=new TextView(this);t.setText((name.isEmpty()?"Carte NFC":name)+"\n"+o.optString("uid")+" • "+o.optString("date"));t.setTextSize(16);t.setPadding(5,12,5,8);final int idx=i;t.setOnClickListener(v->showCard(idx));history.addView(t);Button r=new Button(this);r.setText("✏️ Renommer");r.setOnClickListener(v->renameCard(idx));history.addView(r);}
        }catch(Exception ignored){}
    }

    private void showCard(int i){try{JSONObject o=new JSONArray(prefs.getString("data","[]")).getJSONObject(i);String name=o.optString("name","").trim();if(name.isEmpty())name="Carte NFC";new AlertDialog.Builder(this).setTitle(name).setMessage("Nom : "+name+"\n\nUID : "+o.optString("uid")+"\n\nDate : "+o.optString("date")+"\n\nTechnologies :\n"+o.optString("tech")+"\n\nNDEF :\n"+o.optString("records")).setPositiveButton("OK",null).show();}catch(Exception ignored){}}
    private void renameCard(int i){try{JSONArray a=new JSONArray(prefs.getString("data","[]"));JSONObject o=a.getJSONObject(i);EditText input=new EditText(this);input.setSingleLine(true);input.setText(o.optString("name",""));new AlertDialog.Builder(this).setTitle("Renommer la carte").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{try{o.put("name",input.getText().toString().trim());prefs.edit().putString("data",a.toString()).apply();refreshHistory();}catch(Exception ignored){}}).show();}catch(Exception ignored){}}
    private static String bytesToHex(byte[] b){if(b==null||b.length==0)return "(vide)";StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02X",x));return s.toString();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
