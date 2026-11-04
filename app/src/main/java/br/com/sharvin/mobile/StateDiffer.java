package br.com.sharvin.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compara snapshots reais da API do Painel Operacional.
 * Nenhuma alteração é feita na API ou no banco: apenas leitura.
 */
final class StateDiffer {
    static final String ALERT = "alert";
    static final String PRODUCTION = "production";
    static final String DISPATCH = "dispatch";
    static final String CODE = "code";

    static final class Event {
        final String category;
        final String title;
        final String message;
        final boolean urgent;
        Event(String category, String title, String message, boolean urgent) {
            this.category = category;
            this.title = title;
            this.message = message;
            this.urgent = urgent;
        }
    }

    private StateDiffer() {}

    private static Map<String, JSONObject> index(JSONArray items, String key) {
        Map<String, JSONObject> map = new HashMap<>();
        if (items == null) return map;
        for (int i = 0; i < items.length(); i++) {
            JSONObject obj = items.optJSONObject(i);
            if (obj != null && !obj.optString(key, "").isEmpty()) map.put(obj.optString(key), obj);
        }
        return map;
    }

    private static String safe(String txt) {
        if (txt == null) return "";
        return txt.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String text) {
        String value = safe(text);
        return value.length() > 420 ? value.substring(0, 417) + "..." : value;
    }

    private static boolean changed(JSONObject a, JSONObject b, String... fields) {
        for (String field : fields)
            if (!value(a, field).equals(value(b, field))) return true;
        return false;
    }

    private static String value(JSONObject obj, String field) {
        if (obj == null || obj.isNull(field)) return "";
        Object value = obj.opt(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static String date(String yyyyMmDd) {
        if (yyyyMmDd != null && yyyyMmDd.matches("\\d{4}-\\d{2}-\\d{2}"))
            return yyyyMmDd.substring(8, 10) + "/" + yyyyMmDd.substring(5, 7);
        return yyyyMmDd != null ? yyyyMmDd : "";
    }

    private static String name(String id, Map<String, JSONObject> primary, Map<String, JSONObject> fallback) {
        JSONObject obj = primary.get(id);
        if (obj == null) obj = fallback.get(id);
        return obj == null ? "Canal/produto" : safe(obj.optString("name", "Canal/produto"));
    }

    static List<Event> diff(JSONObject before, JSONObject after) {
        List<Event> out = new ArrayList<>();
        Map<String, JSONObject> oldAlerts = index(before.optJSONArray("alerts"), "id");
        Map<String, JSONObject> newAlerts = index(after.optJSONArray("alerts"), "id");
        for (Map.Entry<String, JSONObject> entry : newAlerts.entrySet()) {
            JSONObject task = entry.getValue();
            JSONObject old = oldAlerts.get(entry.getKey());
            boolean urgent = task.optBoolean("urgent", false);
            if (old == null) out.add(new Event(ALERT, urgent ? "AVISO URGENTE • SHARVIN" : "Novo aviso • Sharvin", shorten(task.optString("title", "Novo aviso operacional")), urgent));
            else if (urgent && !old.optBoolean("urgent", false)) out.add(new Event(ALERT, "Aviso marcado como urgente", shorten(task.optString("title")), true));
            else if (changed(old, task, "title")) out.add(new Event(ALERT, "Aviso atualizado", shorten(task.optString("title")), urgent));
        }
        for (Map.Entry<String, JSONObject> entry : oldAlerts.entrySet()) {
            if (!newAlerts.containsKey(entry.getKey()))
                out.add(new Event(ALERT, "Aviso retirado do painel", shorten(entry.getValue().optString("title")), false));
        }

        Map<String, JSONObject> currentProducts = index(after.optJSONArray("products"), "id");
        Map<String, JSONObject> oldProducts = index(before.optJSONArray("products"), "id");
        Map<String, JSONObject> currentChannels = index(after.optJSONArray("channels"), "id");
        Map<String, JSONObject> oldChannels = index(before.optJSONArray("channels"), "id");
        Map<String, JSONObject> oldProd = index(before.optJSONArray("production"), "id");
        Map<String, JSONObject> newProd = index(after.optJSONArray("production"), "id");
        for (Map.Entry<String, JSONObject> entry : newProd.entrySet()) {
            JSONObject prod = entry.getValue();
            JSONObject old = oldProd.get(entry.getKey());
            String channel = name(prod.optString("channelId"), currentChannels, oldChannels);
            String product = name(prod.optString("productId"), currentProducts, oldProducts);
            String details = prod.optInt("quantity") + " un. • " + product + " • despacho " + date(prod.optString("date"));
            String note = safe(prod.optString("note"));
            if (!note.isEmpty()) details += " • " + note;
            if (old == null) out.add(new Event(PRODUCTION, "Nova produção • " + channel, shorten(details), false));
            else if (changed(old, prod, "quantity", "date", "channelId", "productId", "note")) {
                if (old.optInt("quantity") != prod.optInt("quantity"))
                    details = old.optInt("quantity") + " → " + prod.optInt("quantity") + " un. • " + product + " • despacho " + date(prod.optString("date"));
                out.add(new Event(PRODUCTION, "Produção atualizada • " + channel, shorten(details), false));
            }
        }
        for (Map.Entry<String, JSONObject> entry : oldProd.entrySet()) {
            if (!newProd.containsKey(entry.getKey())) {
                JSONObject prod = entry.getValue();
                String channel = name(prod.optString("channelId"), currentChannels, oldChannels);
                String product = name(prod.optString("productId"), currentProducts, oldProducts);
                out.add(new Event(PRODUCTION, "Produção concluída • " + channel,
                        shorten(prod.optInt("quantity") + " un. • " + product + " • despacho " + date(prod.optString("date"))), false));
            }
        }

        Map<String, JSONObject> oldDispatch = dispatchMap(before.optJSONArray("dispatch"));
        Map<String, JSONObject> newDispatch = dispatchMap(after.optJSONArray("dispatch"));
        for (Map.Entry<String, JSONObject> entry : newDispatch.entrySet()) {
            JSONObject d = entry.getValue();
            JSONObject old = oldDispatch.get(entry.getKey());
            if (old == null || changed(old, d, "expected", "collectionStart", "collectionEnd", "collected", "note")) {
                String channel = name(d.optString("channelId"), currentChannels, oldChannels);
                String expected = d.isNull("expected") ? "Contagem pendente" : d.optInt("expected") + " pacotes";
                String start = d.optString("collectionStart", "");
                String end = d.optString("collectionEnd", "");
                String collect = d.optBoolean("collected") ? "Coleta realizada" : !start.isEmpty() ? "Coleta " + start + (!end.isEmpty() ? "–" + end : "") : "Horário pendente";
                String body = date(d.optString("date")) + " • " + expected + " • " + collect;
                String note = safe(d.optString("note"));
                if (!note.isEmpty()) body += " • " + note;
                out.add(new Event(DISPATCH, "Contagem e coleta • " + channel, shorten(body), false));
            }
        }
        for (Map.Entry<String, JSONObject> entry : oldDispatch.entrySet()) {
            if (!newDispatch.containsKey(entry.getKey())) {
                JSONObject d = entry.getValue();
                out.add(new Event(DISPATCH, "Coleta retirada do painel",
                        name(d.optString("channelId"), currentChannels, oldChannels) + " • " + date(d.optString("date")), false));
            }
        }

        JSONObject oldCodes = before.optJSONObject("codes");
        JSONObject newCodes = after.optJSONObject("codes");
        if (newCodes != null && oldCodes != null) {
            String[] fields = {"codigoDRR", "codigo2RR", "codigoRMC"};
            String[] names = {"DRR", "2RR", "RMC"};
            for (int i = 0; i < fields.length; i++) {
                if (!value(oldCodes, fields[i]).equals(value(newCodes, fields[i])))
                    out.add(new Event(CODE, "Código atualizado • " + names[i], "O código operacional de " + names[i] + " foi alterado no painel.", false));
            }
        }
        return out;
    }

    private static Map<String, JSONObject> dispatchMap(JSONArray dispatches) {
        Map<String, JSONObject> map = new HashMap<>();
        if (dispatches == null) return map;
        for (int i = 0; i < dispatches.length(); i++) {
            JSONObject d = dispatches.optJSONObject(i);
            if (d != null) map.put(d.optString("date") + "|" + d.optString("channelId"), d);
        }
        return map;
    }

    static String initialSummary(JSONObject state) {
        JSONArray alerts = state.optJSONArray("alerts"), productions = state.optJSONArray("production");
        int a = alerts == null ? 0 : alerts.length();
        int p = productions == null ? 0 : productions.length();
        return a + " avisos e " + p + " itens na fila de produção. Abra o aplicativo para conferir.";
    }
}
