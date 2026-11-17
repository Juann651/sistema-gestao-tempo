package br.sgt;

/** Formatação de tempo (sempre em segundos) para telas e mensagens. */
public final class Tempo {
    private Tempo() { }

    /** 5405 -> "1h 30min 5s" */
    public static String legivel(long seg) {
        boolean neg = seg < 0;
        seg = Math.abs(seg);
        long h = seg / 3600, m = seg % 3600 / 60, s = seg % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append("h");
        if (m > 0) { if (b.length() > 0) b.append(' '); b.append(m).append("min"); }
        if (s > 0 || b.length() == 0) { if (b.length() > 0) b.append(' '); b.append(s).append("s"); }
        return (neg ? "-" : "") + b;
    }

    /** 5405 -> "01:30:05" */
    public static String relogio(long seg) {
        boolean neg = seg < 0;
        seg = Math.abs(seg);
        return (neg ? "-" : "") + String.format("%02d:%02d:%02d", seg / 3600, seg % 3600 / 60, seg % 60);
    }

    public static int horas(int seg) { return seg / 3600; }
    public static int minutos(int seg) { return seg % 3600 / 60; }
    public static int segundos(int seg) { return seg % 60; }
}
