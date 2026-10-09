package fr.ses10doigts.tradeIO5.service.backup;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Sauvegarde / rechargement des données historiques non reconstituables rapidement (bougies, flux ETF).
 * Export : un fichier {@code <table>.tsv.gz} par table (toutes colonnes sauf {@code id}), écrit en temporaire puis
 * déplacé atomiquement ; jamais écrasé par une table vide. Import : uniquement si la table est vide et le fichier existe.
 * Pas de FK sur ces tables => ordre sans importance.
 */
@Slf4j
@Service
public class HistoricalDataBackupService {

    public static final List<String> TABLES = List.of("candle", "etf_flow_snapshot");
    private static final String NULL = "\\N";
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final Path dir;

    public HistoricalDataBackupService(JdbcTemplate jdbc,
                                       @Value("${tradeio.backup.dir:backup/historical}") String dir) {
        this.jdbc = jdbc;
        this.dir = Path.of(dir);
    }

    public Path fileOf(String table) {
        return dir.resolve(table + ".tsv.gz");
    }

    /** Exporte toutes les tables ; retourne le nombre de lignes par table (0 = ignorée car vide). */
    public java.util.Map<String, Long> exportAll() {
        java.util.Map<String, Long> res = new java.util.LinkedHashMap<>();
        for (String t : TABLES) {
            try {
                res.put(t, export(t));
            } catch (Exception e) {
                log.error("Backup historique {} en échec : {}", t, e.toString());
                res.put(t, -1L);
            }
        }
        return res;
    }

    long export(String table) throws IOException {
        if (count(table) == 0) {
            log.info("Backup historique {} ignoré : table vide (fichier existant conservé).", table);
            return 0;
        }
        Files.createDirectories(dir);
        Path target = fileOf(table);
        Path tmp = dir.resolve(table + ".tsv.gz.tmp");
        long[] n = {0};
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            jdbc.query("select * from " + table + " order by id", rs -> {
                ResultSetMetaData md = rs.getMetaData();
                List<Integer> idx = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String c = md.getColumnLabel(i);
                    if (!"id".equalsIgnoreCase(c)) {
                        idx.add(i);
                        names.add(c.toLowerCase());
                    }
                }
                try {
                    w.write(String.join("\t", names));
                    w.write('\n');
                    do {
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < idx.size(); k++) {
                            if (k > 0) sb.append('\t');
                            String v = rs.getString(idx.get(k));
                            sb.append(v == null ? NULL : escape(v));
                        }
                        w.write(sb.append('\n').toString());
                        n[0]++;
                    } while (rs.next());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Backup historique {} : {} lignes -> {}", table, n[0], target);
        return n[0];
    }

    /** Recharge chaque table vide dont le fichier existe ; retourne les lignes insérées par table. */
    public java.util.Map<String, Long> importIfEmpty() {
        java.util.Map<String, Long> res = new java.util.LinkedHashMap<>();
        for (String t : TABLES) {
            try {
                res.put(t, importTable(t));
            } catch (Exception e) {
                log.error("Seed historique {} en échec : {}", t, e.toString());
                res.put(t, -1L);
            }
        }
        return res;
    }

    @Transactional
    public long importTable(String table) throws IOException {
        Path f = fileOf(table);
        if (!Files.exists(f)) return 0;
        if (count(table) > 0) return 0;
        long total = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) return 0;
            String[] cols = header.split("\t", -1);
            String sql = "insert into " + table + " (" + String.join(",", cols) + ") values ("
                    + String.join(",", java.util.Collections.nCopies(cols.length, "?")) + ")";
            List<Object[]> batch = new ArrayList<>(BATCH);
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                Object[] row = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    row[i] = NULL.equals(p[i]) ? null : unescape(p[i]);
                }
                batch.add(row);
                if (batch.size() == BATCH) {
                    jdbc.batchUpdate(sql, batch);
                    total += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbc.batchUpdate(sql, batch);
                total += batch.size();
            }
        }
        log.info("Seed historique {} : {} lignes rechargées depuis {}", table, total, f);
        return total;
    }

    private long count(String table) {
        Long c = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return c == null ? 0 : c;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(switch (n) { case 't' -> '\t'; case 'n' -> '\n'; case 'r' -> '\r'; default -> n; });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
