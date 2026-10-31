package monggodb.qo.lexer;

import monggodb.contracts.shared.QOException;
import monggodb.qo.lexer.SqlLexer;
import monggodb.qo.lexer.SqlLexer.Token;
import monggodb.qo.lexer.SqlLexer.TokenStream;
import monggodb.qo.lexer.SqlLexer.TokenType;

/** Pemeriksaan mandiri lexer; jalankan dengan assertions aktif (-ea). */
public class SqlLexerTest {
    public static void main(String[] args) throws QOException {
        SqlLexer lexer = new SqlLexer();
        TokenStream tokens = lexer.tokenize("SeLeCt S.Name\nFROM Student AS S WHERE S.gpa >= 3.14 AND S.name = 'O''Brien';");
        TokenType[] types = {TokenType.SELECT, TokenType.IDENTIFIER, TokenType.DOT,
                TokenType.IDENTIFIER, TokenType.FROM, TokenType.IDENTIFIER, TokenType.AS,
                TokenType.IDENTIFIER, TokenType.WHERE, TokenType.IDENTIFIER, TokenType.DOT,
                TokenType.IDENTIFIER, TokenType.GREATER_EQUAL, TokenType.FLOAT_LITERAL,
                TokenType.AND, TokenType.IDENTIFIER, TokenType.DOT, TokenType.IDENTIFIER,
                TokenType.EQUAL, TokenType.STRING_LITERAL, TokenType.SEMICOLON, TokenType.EOF};
        for (TokenType type : types) {
            assert tokens.peek().type() == type : tokens.peek();
            Token token = tokens.consume();
            if (type == TokenType.STRING_LITERAL) {
                assert token.lexeme().equals("O'Brien");
            }
            if (token.position() == 7) {
                assert token.lexeme().equals("s");
            }
        }
        assert tokens.isAtEnd();
        assert tokens.consume().type() == TokenType.EOF;

        tokens = lexer.tokenize("<= <> < >= > = + - * / , . ( ) ; 42 1.5");
        TokenType[] operators = {TokenType.LESS_EQUAL, TokenType.NOT_EQUAL, TokenType.LESS_THAN,
                TokenType.GREATER_EQUAL, TokenType.GREATER_THAN, TokenType.EQUAL,
                TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH,
                TokenType.COMMA, TokenType.DOT, TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN,
                TokenType.SEMICOLON, TokenType.INTEGER_LITERAL, TokenType.FLOAT_LITERAL};
        for (TokenType type : operators) {
            assert tokens.consume().type() == type;
        }
        assert tokens.isAtEnd();
        assert lexer.tokenize(" \t\n").isAtEnd();

        tokens = lexer.tokenize("CREATE INDEX idx ON t (id) USING BTREE");
        TokenType[] createIndex = {TokenType.CREATE, TokenType.INDEX, TokenType.IDENTIFIER,
                TokenType.ON, TokenType.IDENTIFIER, TokenType.LEFT_PAREN, TokenType.IDENTIFIER,
                TokenType.RIGHT_PAREN, TokenType.USING, TokenType.BTREE};
        for (TokenType type : createIndex) {
            assert tokens.consume().type() == type;
        }
        assert tokens.isAtEnd();
        assert lexer.tokenize("hash").consume().type() == TokenType.HASH;

        tokens = lexer.tokenize("SELECT *\r\nFROM 'One\nTwo' WHERE id = 1");
        assert tokens.consume().line() == 1;
        tokens.consume();
        Token from = tokens.consume();
        assert from.position() == 10 && from.line() == 2 && from.column() == 1;
        Token literal = tokens.consume();
        assert literal.line() == 2 && literal.column() == 6;
        Token where = tokens.consume();
        assert where.line() == 3 && where.column() == 6;
        while (!tokens.isAtEnd()) tokens.consume();
        assert tokens.peek().line() == 3 && tokens.peek().column() == 18;

        tokens = lexer.tokenize("x\ry\tZ\n");
        tokens.consume();
        Token y = tokens.consume();
        assert y.position() == 2 && y.line() == 2 && y.column() == 1;
        Token z = tokens.consume();
        assert z.line() == 2 && z.column() == 3;
        assert tokens.peek().line() == 3 && tokens.peek().column() == 1;

        expectError(lexer, "SELECT @", 7, "@");
        expectError(lexer, "SELECT 'Andi", 7, "'Andi");
        expectError(lexer, null, 0, null);
        System.out.println("SqlLexerTest: PASS");
    }

    private static void expectError(SqlLexer lexer, String sql, int position, String token) {
        try {
            lexer.tokenize(sql);
            throw new AssertionError("Kesalahan leksikal tidak terdeteksi");
        } catch (QOException error) {
            assert error.getErrorCode() == QOException.Code.SYNTAX_ERROR;
            assert error.getPosition() == position;
            assert java.util.Objects.equals(error.getToken(), token);
        }
    }
}
