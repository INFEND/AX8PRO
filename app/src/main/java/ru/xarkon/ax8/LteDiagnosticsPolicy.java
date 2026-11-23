package ru.xarkon.ax8;

import java.util.Locale;

/**
 * Read-only interpretations of AX8 modem values. The AX8 firmware encoding is
 * not independently documented: standards-based mappings are candidates, not
 * claims about the physical radio signal. No router I/O is performed here.
 */
public final class LteDiagnosticsPolicy {
    private LteDiagnosticsPolicy() {}

    public static Double number(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String cleaned = raw.trim().toLowerCase(Locale.ROOT)
                    .replace("dbm", "").replace("db", "").replace(',', '.').trim();
            double v = Double.parseDouble(cleaned);
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String rsrp(String raw) {
        Double value = number(raw);
        if (value == null) return "— (не получено)";
        if (value < 0 && value >= -160 && value <= -35) return format(value) + " dBm (отрицательное значение прошивки)";
        if (isInteger(value) && value >= 0 && value <= 97) {
            int code = value.intValue();
            String estimate = code == 0 ? "ниже −140 dBm" :
                    code == 97 ? "от −44 dBm" :
                    "от " + (code - 141) + " до " + (code - 140) + " dBm";
            return "код " + code + " (по таблице LTE: " + estimate +
                    "; кодировка AX8 не подтверждена)";
        }
        return "не интерпретировано (raw: " + safe(raw) + ")";
    }

    public static String rsrq(String raw) {
        Double value = number(raw);
        if (value == null) return "— (не получено)";
        if (value < 0 && value >= -35 && value <= 0) return format(value) + " dB (отрицательное значение прошивки)";
        if (isInteger(value) && value >= 0 && value <= 34) {
            int code = value.intValue();
            String estimate = code == 0 ? "ниже −19,5 dB" :
                    code == 34 ? "от −3 dB" :
                    "от " + format(-20.0 + code * 0.5) + " до " +
                    format(-19.5 + code * 0.5) + " dB";
            return "код " + code + " (по таблице LTE: " + estimate +
                    "; кодировка AX8 не подтверждена)";
        }
        return "не интерпретировано (raw: " + safe(raw) + ")";
    }

    public static String bandwidth(String nativeValue, String rawCode) {
        if (nativeValue != null && !nativeValue.trim().isEmpty()) {
            String value = nativeValue.trim();
            return value.toLowerCase(Locale.ROOT).contains("mhz") ? value : value +
                    " (единицы не подтверждены)";
        }
        if (rawCode == null || rawCode.trim().isEmpty()) return "— (не получено)";
        String code = rawCode.trim();
        if ("1".equals(code) || "2".equals(code)) {
            return "код " + code + " (таблица приложения: " +
                    ("1".equals(code) ? "20" : "10") +
                    " МГц; проверить по прошивке/сети)";
        }
        return "код/значение " + safe(code) + " (не расшифровано)";
    }

    public static String provider(String provider, String plmn) {
        String p = provider == null ? "" : provider.trim();
        String n = plmn == null ? "" : plmn.trim();
        String candidate = n.matches("[0-9]{5,6}") ? n : p.matches("[0-9]{5,6}") ? p : "";
        if (candidate.isEmpty()) return p.isEmpty() ? "—" : safe(p);
        String summary = candidate + " (возможный PLMN: MCC " +
                candidate.substring(0,3) + ", MNC " + candidate.substring(3) +
                "; сверить с полем регистрации)";
        if (candidate.startsWith("999"))
            summary += "; MCC 999 зарезервирован для частных/испытательных сетей";
        return summary;
    }

    public static String cellIdentity(String enodeb, String cell, String global) {
        Long base = whole(enodeb), sector = whole(cell), id = whole(global);
        if (base == null || sector == null || id == null || sector < 0 || sector > 255)
            return "недостаточно данных для проверки ECGI";
        long calculated = base * 256L + sector;
        return "eNodeB × 256 + Cell ID = " + calculated + "; " +
                (calculated == id ? "совпадает с глобальным ID" :
                 "НЕ совпадает с глобальным ID " + id + " (проверить формат)");
    }

    public static String bandFrequency(String band, String earfcn) {
        Long n = whole(earfcn);
        if (!"3".equals(band) || n == null || n < 1200 || n > 1949)
            return "частоты не рассчитаны: неизвестный Band/EARFCN";
        double dl = 1805 + 0.1 * (n - 1200), ul = 1710 + 0.1 * (n - 1200);
        return "LTE B3 / EARFCN " + n + ": DL " + format(dl) +
                " МГц, UL " + format(ul) + " МГц (расчёт по стандарту)";
    }

    public static String consistency(String rsrp, String rssi, String sinr,
                                      String cqi0, String cqi1, String tx) {
        StringBuilder result = new StringBuilder();
        Double rp = number(rsrp), ri = number(rssi), sn = number(sinr);
        // Candidate 3GPP RSRP encoding; this cannot prove firmware units.
        if (rp != null && isInteger(rp) && rp >= 0 && rp <= 97)
            rp = rp == 0 ? null : rp == 97 ? -44.0 : rp - 141;
        if (rp != null && ri != null && rp < 0 && ri < 0 && ri <= rp + 5)
            result.append("• RSSI близок к RSRP или ниже него: поля могут иметь разные определения, единицы или время измерения; не считать их сопоставимыми без проверки.\n");
        if (sn != null && (sn < -25 || sn > 45))
            result.append("• SINR за пределами ожидаемого диапазона; проверить единицы/масштаб.\n");
        if ("0".equals(cqi0) && "0".equals(cqi1))
            result.append("• CQI 0/0 может означать отсутствие актуального измерения в простое; сам по себе это не дефект LTE.\n");
        Double txValue = number(tx);
        if (txValue != null && txValue >= 22 && txValue <= 24)
            result.append("• TX power около 23: возможно dBm и высокая мощность UL, но единицы и момент измерения не подтверждены.\n");
        if (result.length() == 0) result.append("• Явных противоречий по доступным показателям не выявлено.\n");
        return result.toString();
    }

    public static String trend(java.util.List<String> rsrp, java.util.List<String> sinr) {
        if (rsrp == null || sinr == null || rsrp.isEmpty() || sinr.isEmpty())
            return "Повторные измерения не получены.";
        int validRsrp = 0, validSinr = 0;
        double minRsrp = Double.POSITIVE_INFINITY, maxRsrp = Double.NEGATIVE_INFINITY;
        double minSinr = Double.POSITIVE_INFINITY, maxSinr = Double.NEGATIVE_INFINITY;
        for (String s : rsrp) {
            Double v = number(s);
            if (v != null) { validRsrp++; minRsrp = Math.min(minRsrp, v); maxRsrp = Math.max(maxRsrp, v); }
        }
        for (String s : sinr) {
            Double v = number(s);
            if (v != null) { validSinr++; minSinr = Math.min(minSinr, v); maxSinr = Math.max(maxSinr, v); }
        }
        return "Получено " + Math.max(rsrp.size(),sinr.size()) + " последовательных снимков. " +
                (validRsrp > 0 ? "RSRP raw: " + format(minRsrp) + "…" + format(maxRsrp) +
                 " (диапазон кодов/значений, не dBm). " : "RSRP: —. ") +
                (validSinr > 0 ? "SINR raw: " + format(minSinr) + "…" + format(maxSinr) +
                 " (единицы прошивки). " : "SINR: —. ") +
                "Короткая серия не доказывает долгосрочную стабильность.";
    }

    private static Long whole(String raw) {
        Double n = number(raw);
        return n != null && isInteger(n) && n >= 0 && n < 10_000_000_000L ? n.longValue() : null;
    }
    private static boolean isInteger(Double v) { return v != null && v == Math.rint(v); }
    private static String format(double v) {
        return v == Math.rint(v) ? Long.toString((long)v) :
                String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }
    private static String safe(String raw) {
        String x = raw == null ? "" : raw.replace('\n',' ').replace('\r',' ').trim();
        return x.length() > 80 ? x.substring(0,80) + "…" : x;
    }
}
