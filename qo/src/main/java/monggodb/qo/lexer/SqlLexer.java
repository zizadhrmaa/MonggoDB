package monggodb.qo.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import monggodb.contracts.shared.QOException;

/** Mengubah teks SQL menjadi token yang dapat dibaca parser QO. */
public class SqlLexer {
    /** Jenis token untuk sintaks SQL yang didukung QO. */
    public enum TokenType {
        SELECT, FROM, WHERE, ORDER, BY, ASC, DESC, JOIN, ON, NATURAL,
        INSERT, INTO, VALUES, UPDATE, SET, DELETE, BEGIN, TRANSACTION, COMMIT, ABORT,
        CREATE, INDEX, USING, BTREE, HASH, TABLE, DROP, LIMIT, AS, IN, EXISTS, IS, NOT, NULL, AND,
        INTEGER, FLOAT, CHAR, VARCHAR, PRIMARY, KEY, FOREIGN, REFERENCES, CASCADE, RESTRICT,
        IDENTIFIER, INTEGER_LITERAL, FLOAT_LITERAL, STRING_LITERAL,
        EQUAL, NOT_EQUAL, LESS_THAN, LESS_EQUAL, GREATER_THAN, GREATER_EQUAL,
        PLUS, MINUS, STAR, SLASH, COMMA, DOT, LEFT_PAREN, RIGHT_PAREN, SEMICOLON, EOF
    }

    /** Posisi berbasis UTF-16 dimulai dari 0; baris dan kolom dimulai dari 1. */
    public record Token(TokenType type, String lexeme, int position, int line, int column) { }

    /** Daftar token */
    public static class TokenStream {
        private final List<Token> tokens;
        private int position;

        private TokenStream(List<Token> tokens) {
            this.tokens = List.copyOf(tokens);
        }

        public Token peek() {
            return tokens.get(position);
        }

        public Token consume() {
            Token token = peek();
            if (!isAtEnd()) {
                position++;
            }
            return token;
        }

        public boolean isAtEnd() {
            return peek().type() == TokenType.EOF;
        }
    }

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("select", TokenType.SELECT), Map.entry("from", TokenType.FROM),
            Map.entry("where", TokenType.WHERE), Map.entry("order", TokenType.ORDER),
            Map.entry("by", TokenType.BY), Map.entry("asc", TokenType.ASC),
            Map.entry("desc", TokenType.DESC), Map.entry("join", TokenType.JOIN),
            Map.entry("on", TokenType.ON), Map.entry("natural", TokenType.NATURAL),
            Map.entry("insert", TokenType.INSERT), Map.entry("into", TokenType.INTO),
            Map.entry("values", TokenType.VALUES), Map.entry("update", TokenType.UPDATE),
            Map.entry("set", TokenType.SET), Map.entry("delete", TokenType.DELETE),
            Map.entry("begin", TokenType.BEGIN), Map.entry("transaction", TokenType.TRANSACTION),
            Map.entry("commit", TokenType.COMMIT), Map.entry("abort", TokenType.ABORT),
            Map.entry("create", TokenType.CREATE), Map.entry("index", TokenType.INDEX),
            Map.entry("using", TokenType.USING), Map.entry("btree", TokenType.BTREE),
            Map.entry("hash", TokenType.HASH),
            Map.entry("table", TokenType.TABLE), Map.entry("drop", TokenType.DROP),
            Map.entry("limit", TokenType.LIMIT), Map.entry("as", TokenType.AS),
            Map.entry("in", TokenType.IN), Map.entry("exists", TokenType.EXISTS),
            Map.entry("is", TokenType.IS), Map.entry("not", TokenType.NOT),
            Map.entry("null", TokenType.NULL), Map.entry("and", TokenType.AND),
            Map.entry("integer", TokenType.INTEGER), Map.entry("float", TokenType.FLOAT),
            Map.entry("char", TokenType.CHAR), Map.entry("varchar", TokenType.VARCHAR),
            Map.entry("primary", TokenType.PRIMARY), Map.entry("key", TokenType.KEY),
            Map.entry("foreign", TokenType.FOREIGN), Map.entry("references", TokenType.REFERENCES),
            Map.entry("cascade", TokenType.CASCADE), Map.entry("restrict", TokenType.RESTRICT));

    /** Memecah SQL menjadi token */
    public TokenStream tokenize(String sql) throws QOException {
        if (sql == null) {
            throw new QOException(QOException.Code.SYNTAX_ERROR, "SQL tidak boleh null", 0, null);
        }

        List<Token> tokens = new ArrayList<>();
        int position = 0;
        int line = 1;
        int lineStart = 0;
        while (position < sql.length()) {
            char current = sql.charAt(position);
            if (Character.isWhitespace(current)) {
                position++;
                if (current == '\n' || (current == '\r'
                        && (position == sql.length() || sql.charAt(position) != '\n'))) {
                    line++;
                    lineStart = position;
                }
                continue;
            }

            int start = position;
            int startLine = line;
            int startColumn = start - lineStart + 1;
            if (Character.isLetter(current) || current == '_') {
                position++;
                while (position < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(position)) || sql.charAt(position) == '_')) {
                    position++;
                }
                String word = sql.substring(start, position).toLowerCase(Locale.ROOT);
                tokens.add(new Token(KEYWORDS.getOrDefault(word, TokenType.IDENTIFIER), word,
                        start, startLine, startColumn));
                continue;
            }

            if (Character.isDigit(current)) {
                position++;
                while (position < sql.length() && Character.isDigit(sql.charAt(position))) {
                    position++;
                }
                TokenType type = TokenType.INTEGER_LITERAL;
                if (position + 1 < sql.length() && sql.charAt(position) == '.'
                        && Character.isDigit(sql.charAt(position + 1))) {
                    type = TokenType.FLOAT_LITERAL;
                    position++;
                    while (position < sql.length() && Character.isDigit(sql.charAt(position))) {
                        position++;
                    }
                }
                tokens.add(new Token(type, sql.substring(start, position), start, startLine, startColumn));
                continue;
            }

            if (current == '\'') {
                position++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (position < sql.length()) {
                    char c = sql.charAt(position++);
                    if (c == '\n' || (c == '\r'
                            && (position == sql.length() || sql.charAt(position) != '\n'))) {
                        line++;
                        lineStart = position;
                    }
                    if (c == '\'') {
                        if (position < sql.length() && sql.charAt(position) == '\'') {
                            value.append('\'');
                            position++;
                        } else {
                            closed = true;
                            break;
                        }
                    } else {
                        value.append(c);
                    }
                }
                if (!closed) {
                    throw new QOException(QOException.Code.SYNTAX_ERROR,
                            "String tidak memiliki tanda kutip penutup pada posisi " + start,
                            start, sql.substring(start));
                }
                tokens.add(new Token(TokenType.STRING_LITERAL, value.toString(), start, startLine, startColumn));
                continue;
            }

            position++;
            TokenType type = switch (current) {
                case '=' -> TokenType.EQUAL;
                case '+' -> TokenType.PLUS;
                case '-' -> TokenType.MINUS;
                case '*' -> TokenType.STAR;
                case '/' -> TokenType.SLASH;
                case ',' -> TokenType.COMMA;
                case '.' -> TokenType.DOT;
                case '(' -> TokenType.LEFT_PAREN;
                case ')' -> TokenType.RIGHT_PAREN;
                case ';' -> TokenType.SEMICOLON;
                case '<' -> {
                    if (position < sql.length() && sql.charAt(position) == '=') {
                        position++;
                        yield TokenType.LESS_EQUAL;
                    }
                    if (position < sql.length() && sql.charAt(position) == '>') {
                        position++;
                        yield TokenType.NOT_EQUAL;
                    }
                    yield TokenType.LESS_THAN;
                }
                case '>' -> {
                    if (position < sql.length() && sql.charAt(position) == '=') {
                        position++;
                        yield TokenType.GREATER_EQUAL;
                    }
                    yield TokenType.GREATER_THAN;
                }
                default -> throw new QOException(QOException.Code.SYNTAX_ERROR,
                        "Karakter tidak dikenal pada posisi " + start + ": " + current,
                        start, String.valueOf(current));
            };
            tokens.add(new Token(type, sql.substring(start, position), start, startLine, startColumn));
        }
        tokens.add(new Token(TokenType.EOF, "", position, line, position - lineStart + 1));
        return new TokenStream(tokens);
    }
}
