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
