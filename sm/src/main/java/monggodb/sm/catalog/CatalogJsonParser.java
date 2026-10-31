package monggodb.sm.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.ColumnStats;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.ForeignKeyRef;
import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TableStats;
import monggodb.contracts.shared.Value;

/**
 * Parser dan formatter format berkas JSON katalog untuk komponen Storage Manager (SM4).
 * Mengimplementasikan penguraian format JSON mandiri tanpa pustaka eksternal pihak ketiga.
 */
public class CatalogJsonParser {

    /**
     * Mengurai string JSON menjadi representasi CatalogData.
     *
     * @param json string JSON isi berkas katalog
     * @return data katalog yang berhasil diurai
     * @throws StorageException jika format JSON tidak valid atau struktur tidak sesuai
     */
    public CatalogData parse(String json) throws StorageException {
        if (json == null || json.isBlank()) {
            return new CatalogData(List.of(), List.of(), Map.of());
        }

        Object rootObj;
        try {
            rootObj = new JsonReader(json).parse();
        } catch (Exception e) {
            throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                    "Gagal mengurai JSON katalog: " + e.getMessage(), e);
        }

        if (!(rootObj instanceof Map<?, ?> rootMap)) {
            throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                    "Format JSON katalog tidak valid: root harus berupa objek JSON");
        }

        try {
            List<Schema> tables = parseTables(rootMap.get("tables"));
            List<IndexInfo> indexes = parseIndexes(rootMap.get("indexes"));
            Map<String, TableStats> stats = parseStats(rootMap.get("stats"), tables);
            return new CatalogData(tables, indexes, stats);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                    "Kesalahan isi data pada JSON katalog: " + e.getMessage(), e);
        }
    }

    private List<Schema> parseTables(Object tablesObj) {
        List<Schema> schemas = new ArrayList<>();
        if (tablesObj == null) {
            return schemas;
        }
        if (!(tablesObj instanceof List<?> tableList)) {
            throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                    "Elemen 'tables' harus berupa array JSON");
        }

        for (Object item : tableList) {
            if (!(item instanceof Map<?, ?> tableMap)) {
                throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                        "Entri tabel harus berupa objek JSON");
            }
            String tableName = getString(tableMap, "tableName", null);
            if (tableName == null || tableName.isBlank()) {
                throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                        "Entri tabel wajib memiliki field 'tableName'");
            }

            Object colsObj = tableMap.get("columns");
            if (!(colsObj instanceof List<?> colList)) {
                throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                        "Elemen 'columns' pada tabel '" + tableName + "' harus berupa array JSON");
            }

            List<Column> columns = new ArrayList<>();
            for (Object colItem : colList) {
                if (!(colItem instanceof Map<?, ?> colMap)) {
                    throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                            "Entri kolom pada tabel '" + tableName + "' harus berupa objek JSON");
                }
                String name = getString(colMap, "name", null);
                String colTable = getString(colMap, "table", tableName);
                String dataTypeStr = getString(colMap, "dataType", null);
                if (name == null || dataTypeStr == null) {
                    throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                            "Kolom pada tabel '" + tableName + "' wajib memiliki field 'name' dan 'dataType'");
                }

                DataType dataType;
                try {
                    dataType = DataType.valueOf(dataTypeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new StorageException(StorageException.Code.UNSUPPORTED_DATA_TYPE,
                            "Tipe data tidak didukung: " + dataTypeStr);
                }

                int length = getInt(colMap, "length", 0);
                boolean primaryKey = Boolean.TRUE.equals(colMap.get("primaryKey"));
                boolean notNull = Boolean.TRUE.equals(colMap.get("notNull")) || primaryKey;

                ForeignKeyRef ref = null;
                Object refObj = colMap.get("references");
                if (refObj instanceof Map<?, ?> refMap) {
                    String refTable = getString(refMap, "table", null);
                    String refCol = getString(refMap, "column", null);
                    if (refTable != null && refCol != null) {
                        ref = new ForeignKeyRef(refTable, refCol);
                    }
                }

                columns.add(new Column(name, colTable, dataType, length, primaryKey, notNull, ref));
            }

            schemas.add(new Schema(tableName, columns));
        }

        return schemas;
    }

    private List<IndexInfo> parseIndexes(Object indexesObj) {
        List<IndexInfo> indexes = new ArrayList<>();
        if (indexesObj == null) {
            return indexes;
        }
        if (!(indexesObj instanceof List<?> idxList)) {
            throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                    "Elemen 'indexes' harus berupa array JSON");
        }

        for (Object item : idxList) {
            if (!(item instanceof Map<?, ?> idxMap)) {
                throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                        "Entri index harus berupa objek JSON");
            }
            String indexName = getString(idxMap, "indexName", null);
            String table = getString(idxMap, "table", getString(idxMap, "tableName", null));
            String column = getString(idxMap, "column", getString(idxMap, "columnName", null));
            String typeStr = getString(idxMap, "type", getString(idxMap, "indexType", null));

            if (indexName == null || table == null || column == null || typeStr == null) {
                throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                        "Entri index wajib memiliki 'indexName', 'table', 'column', dan 'type'");
            }

            IndexType type;
            try {
                type = IndexType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new StorageException(StorageException.Code.MALFORMED_RECORD,
                        "Tipe index tidak valid: " + typeStr);
            }

            indexes.add(new IndexInfo(indexName, table, column, type));
        }

        return indexes;
    }

    private Map<String, TableStats> parseStats(Object statsObj, List<Schema> tables) {
        Map<String, TableStats> statsMap = new LinkedHashMap<>();
        if (statsObj == null) {
            return statsMap;
        }

        if (statsObj instanceof List<?> statsList) {
            for (Object item : statsList) {
                if (!(item instanceof Map<?, ?> statMap)) {
                    continue;
                }
                TableStats ts = parseSingleTableStats(statMap);
                if (ts != null) {
                    statsMap.put(ts.getTableName().toLowerCase(), ts);
                }
            }
        } else if (statsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> statMap) {
                    TableStats ts = parseSingleTableStats(statMap);
                    if (ts != null) {
                        statsMap.put(ts.getTableName().toLowerCase(), ts);
                    }
                }
            }
        }

        return statsMap;
    }

    private TableStats parseSingleTableStats(Map<?, ?> statMap) {
        String tableName = getString(statMap, "tableName", null);
        if (tableName == null) {
            return null;
        }
        long recordCount = getLong(statMap, "recordCount", 0L);
        long blockCount = getLong(statMap, "blockCount", 0L);
        double avgRecordSize = getDouble(statMap, "avgRecordSize", 0.0);
        double blockingFactor = getDouble(statMap, "blockingFactor", 0.0);
        int blockSize = getInt(statMap, "blockSize", 4096);

        Map<String, ColumnStats> colStatsMap = new LinkedHashMap<>();
        Object colsObj = statMap.get("columns");
        if (colsObj instanceof List<?> colList) {
            for (Object item : colList) {
                if (!(item instanceof Map<?, ?> colMap)) {
                    continue;
                }
                String col = getString(colMap, "column", null);
                if (col == null) continue;
                long distinct = getLong(colMap, "distinctCount", 0L);
                long nulls = getLong(colMap, "nullCount", 0L);
                Value min = parseValue(colMap.get("minValue"));
                Value max = parseValue(colMap.get("maxValue"));
                colStatsMap.put(col, new ColumnStats(col, distinct, nulls, min, max));
            }
        } else if (colsObj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> colMap)) {
                    continue;
                }
                String col = entry.getKey().toString();
                long distinct = getLong(colMap, "distinctCount", 0L);
                long nulls = getLong(colMap, "nullCount", 0L);
                Value min = parseValue(colMap.get("minValue"));
                Value max = parseValue(colMap.get("maxValue"));
                colStatsMap.put(col, new ColumnStats(col, distinct, nulls, min, max));
            }
        }

        return new TableStats(tableName, recordCount, blockCount, avgRecordSize, blockingFactor, blockSize, colStatsMap);
    }

    private Value parseValue(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Map<?, ?> vMap) {
            String typeStr = getString(vMap, "dataType", null);
            if (typeStr == null) return null;
            DataType dt = DataType.valueOf(typeStr.toUpperCase());
            Object val = vMap.get("value");
            if (val == null) {
                return Value.ofNull(dt);
            }
            return switch (dt) {
                case INTEGER -> Value.ofInteger(((Number) val).intValue());
                case FLOAT -> Value.ofFloat(((Number) val).floatValue());
                case CHAR -> Value.ofChar(val.toString());
                case VARCHAR -> Value.ofVarchar(val.toString());
            };
        }
        return null;
    }

    /**
     * Memformat CatalogData menjadi string JSON dengan indentasi rapi.
     *
     * @param data representasi data katalog yang akan diserialisasi
     * @return string JSON hasil format
     */
    public String format(CatalogData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // 1. Tables
        sb.append("  \"tables\": [\n");
        List<Schema> tables = data.tables() != null ? data.tables() : List.of();
        for (int i = 0; i < tables.size(); i++) {
            Schema schema = tables.get(i);
            sb.append("    {\n");
            sb.append("      \"tableName\": ").append(quote(schema.getTableName())).append(",\n");
            sb.append("      \"columns\": [\n");
            List<Column> columns = schema.getColumns();
            for (int j = 0; j < columns.size(); j++) {
                Column col = columns.get(j);
                sb.append("        {\n");
                sb.append("          \"name\": ").append(quote(col.getName())).append(",\n");
                sb.append("          \"table\": ").append(quote(col.getTable())).append(",\n");
                sb.append("          \"dataType\": ").append(quote(col.getDataType().name())).append(",\n");
                sb.append("          \"length\": ").append(col.getLength()).append(",\n");
                sb.append("          \"primaryKey\": ").append(col.isPrimaryKey()).append(",\n");
                sb.append("          \"notNull\": ").append(col.isNotNull()).append(",\n");
                sb.append("          \"references\": ");
                if (col.getReferences() != null) {
                    sb.append("{\n");
                    sb.append("            \"table\": ").append(quote(col.getReferences().getTable())).append(",\n");
                    sb.append("            \"column\": ").append(quote(col.getReferences().getColumn())).append("\n");
                    sb.append("          }\n");
                } else {
                    sb.append("null\n");
                }
                sb.append("        }").append(j < columns.size() - 1 ? "," : "").append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }").append(i < tables.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        // 2. Indexes
        sb.append("  \"indexes\": [\n");
        List<IndexInfo> indexes = data.indexes() != null ? data.indexes() : List.of();
        for (int i = 0; i < indexes.size(); i++) {
            IndexInfo idx = indexes.get(i);
            sb.append("    {\n");
            sb.append("      \"indexName\": ").append(quote(idx.getIndexName())).append(",\n");
            sb.append("      \"table\": ").append(quote(idx.getIndexTable())).append(",\n");
            sb.append("      \"column\": ").append(quote(idx.getIndexColumn())).append(",\n");
            sb.append("      \"type\": ").append(quote(idx.getIndexType().name())).append("\n");
            sb.append("    }").append(i < indexes.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        // 3. Stats
        sb.append("  \"stats\": [\n");
        List<TableStats> statsList = data.stats() != null ? new ArrayList<>(data.stats().values()) : List.of();
        for (int i = 0; i < statsList.size(); i++) {
            TableStats ts = statsList.get(i);
            sb.append("    {\n");
            sb.append("      \"tableName\": ").append(quote(ts.getTableName())).append(",\n");
            sb.append("      \"recordCount\": ").append(ts.getRecordCount()).append(",\n");
            sb.append("      \"blockCount\": ").append(ts.getBlockCount()).append(",\n");
            sb.append("      \"avgRecordSize\": ").append(ts.getAvgRecordSize()).append(",\n");
            sb.append("      \"blockingFactor\": ").append(ts.getBlockingFactor()).append(",\n");
            sb.append("      \"blockSize\": ").append(ts.getBlockSize()).append(",\n");
            sb.append("      \"columns\": [\n");
            List<ColumnStats> colStats = new ArrayList<>(ts.getColumnStats().values());
            for (int j = 0; j < colStats.size(); j++) {
                ColumnStats cs = colStats.get(j);
                sb.append("        {\n");
                sb.append("          \"column\": ").append(quote(cs.getColumn())).append(",\n");
                sb.append("          \"distinctCount\": ").append(cs.getDistinctCount()).append(",\n");
                sb.append("          \"nullCount\": ").append(cs.getNullCount()).append(",\n");
                sb.append("          \"minValue\": ").append(formatValue(cs.getMinValue())).append(",\n");
                sb.append("          \"maxValue\": ").append(formatValue(cs.getMaxValue())).append("\n");
                sb.append("        }").append(j < colStats.size() - 1 ? "," : "").append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }").append(i < statsList.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n");

        sb.append("}\n");
        return sb.toString();
    }

    private static String formatValue(Value v) {
        if (v == null || v.isNull()) {
            return "null";
        }
        String valStr = switch (v.dataType()) {
            case INTEGER -> String.valueOf(v.asInteger());
            case FLOAT -> String.valueOf(v.asFloat());
            case CHAR, VARCHAR -> quote(v.asString());
        };
        return "{\"dataType\": " + quote(v.dataType().name()) + ", \"value\": " + valStr + "}";
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String getString(Map<?, ?> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private static int getInt(Map<?, ?> map, String key, int defaultVal) {
        Object val = map.get(key);
        return (val instanceof Number n) ? n.intValue() : defaultVal;
    }

    private static long getLong(Map<?, ?> map, String key, long defaultVal) {
        Object val = map.get(key);
        return (val instanceof Number n) ? n.longValue() : defaultVal;
    }

    private static double getDouble(Map<?, ?> map, String key, double defaultVal) {
        Object val = map.get(key);
        return (val instanceof Number n) ? n.doubleValue() : defaultVal;
    }

    /**
     * Pengurai string JSON sederhana dan mandiri berbasis rekursif.
     */
    private static class JsonReader {
        private final String src;
        private int pos = 0;

        JsonReader(String src) {
            this.src = src;
        }

        Object parse() {
            skipWhitespace();
            Object val = readValue();
            skipWhitespace();
            if (pos < src.length()) {
                throw new IllegalArgumentException("Karakter berlebih setelah JSON: " + src.substring(pos));
            }
            return val;
        }

        private Object readValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Akhir input JSON tidak terduga");
            }
            char c = src.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
            throw new IllegalArgumentException("Karakter tidak terduga '" + c + "' pada indeks " + pos);
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object val = readValue();
                map.put(key, val);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                } else if (c == '}') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Diharapkan ',' atau '}', ditemukan: '" + c + "' pada indeks " + pos);
                }
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                } else if (c == ']') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Diharapkan ',' atau ']', ditemukan: '" + c + "' pada indeks " + pos);
                }
            }
            return list;
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) throw new IllegalArgumentException("Escape tidak lengkap di akhir string");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) throw new IllegalArgumentException("Unicode escape tidak lengkap");
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("String tidak tertutup sampai akhir input");
        }

        private Number readNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            boolean isFloating = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String numStr = src.substring(start, pos);
            if (isFloating) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private Boolean readBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Bukan nilai boolean yang valid pada indeks " + pos);
        }

        private Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Bukan token null yang valid pada indeks " + pos);
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private void expect(char expected) {
            if (pos >= src.length() || src.charAt(pos) != expected) {
                char found = pos < src.length() ? src.charAt(pos) : '\0';
                throw new IllegalArgumentException("Diharapkan karakter '" + expected + "', ditemukan: '" + found + "' pada indeks " + pos);
            }
            pos++;
        }

        private char peek() {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Akhir input tidak terduga saat membaca karakter");
            }
            return src.charAt(pos);
        }
    }
}
