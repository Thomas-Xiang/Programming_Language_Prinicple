package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The parser takes the sequence of tokens emitted by the lexer and turns that
 * into a structured representation of the program, called the Abstract Syntax
 * Tree (AST).
 *
 * The parser has a similar architecture to the lexer, just with {@link Token}s
 * instead of characters. As before, {@link #peek(Object...)} and {@link
 * #match(Object...)} are helpers to make the implementation easier.
 *
 * This type of parser is called <em>recursive descent</em>. Each rule in our
 * grammar will have it's own function, and reference to other rules correspond
 * to calling those functions.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    /**
     * Parses the {@code source} rule.
     *
     * source ::= field* method*
     */
    public Ast.Source parseSource() throws ParseException {
        List<Ast.Field> fields = new ArrayList<>();
        List<Ast.Method> methods = new ArrayList<>();

        while (peek("LET")) {
            fields.add(parseField());
        }
        while (peek("DEF")) {
            methods.add(parseMethod());
        }

        // Anything left over is not part of a valid source.
        if (tokens.has(0)) {
            throw new ParseException("Unexpected token.", currentIndex());
        }
        return new Ast.Source(fields, methods);
    }

    /**
     * Parses the {@code field} rule. This method should only be called if the
     * next tokens start a field, aka {@code LET}.
     *
     * field ::= 'LET' 'CONST'? identifier ':' identifier ('=' expression)? ';'
     */
    public Ast.Field parseField() throws ParseException {
        expect("LET");
        boolean constant = match("CONST");
        String name = expectIdentifier();
        expect(":");
        String typeName = expectIdentifier();

        Optional<Ast.Expression> value = Optional.empty();
        if (match("=")) {
            value = Optional.of(parseExpression());
        }
        expect(";");
        return new Ast.Field(name, typeName, constant, value);
    }

    /**
     * Parses the {@code method} rule. This method should only be called if the
     * next tokens start a method, aka {@code DEF}.
     *
     * method ::= 'DEF' identifier '(' (identifier ':' identifier (',' identifier ':' identifier)*)? ')' (':' identifier)? 'DO' statement* 'END'
     */
    public Ast.Method parseMethod() throws ParseException {
        expect("DEF");
        String name = expectIdentifier();
        expect("(");

        List<String> parameters = new ArrayList<>();
        List<String> parameterTypeNames = new ArrayList<>();
        if (!peek(")")) {
            do {
                parameters.add(expectIdentifier());
                expect(":");
                parameterTypeNames.add(expectIdentifier());
            } while (match(","));
        }
        expect(")");

        Optional<String> returnTypeName = Optional.empty();
        if (match(":")) {
            returnTypeName = Optional.of(expectIdentifier());
        }
        expect("DO");

        List<Ast.Statement> statements = new ArrayList<>();
        while (tokens.has(0) && !peek("END")) {
            statements.add(parseStatement());
        }
        expect("END");
        return new Ast.Method(name, parameters, parameterTypeNames, returnTypeName, statements);
    }

    /**
     * Parses the {@code statement} rule and delegates to the necessary method.
     * If the next tokens do not start a declaration, if, for, while, or return
     * statement, then it is an expression/assignment statement.
     */
    public Ast.Statement parseStatement() throws ParseException {
        if (peek("LET")) {
            return parseDeclarationStatement();
        } else if (peek("IF")) {
            return parseIfStatement();
        } else if (peek("FOR")) {
            return parseForStatement();
        } else if (peek("WHILE")) {
            return parseWhileStatement();
        } else if (peek("RETURN")) {
            return parseReturnStatement();
        }

        // expression ('=' expression)? ';'
        Ast.Expression expression = parseExpression();
        if (match("=")) {
            Ast.Expression value = parseExpression();
            expect(";");
            return new Ast.Statement.Assignment(expression, value);
        }
        expect(";");
        return new Ast.Statement.Expression(expression);
    }

    /**
     * Parses a declaration statement from the {@code statement} rule. This
     * method should only be called if the next tokens start a declaration
     * statement, aka {@code LET}.
     *
     * 'LET' identifier (':' identifier)? ('=' expression)? ';'
     */
    public Ast.Statement.Declaration parseDeclarationStatement() throws ParseException {
        expect("LET");
        String name = expectIdentifier();

        Optional<String> typeName = Optional.empty();
        if (match(":")) {
            typeName = Optional.of(expectIdentifier());
        }

        Optional<Ast.Expression> value = Optional.empty();
        if (match("=")) {
            value = Optional.of(parseExpression());
        }
        expect(";");
        return new Ast.Statement.Declaration(name, typeName, value);
    }

    /**
     * Parses an if statement from the {@code statement} rule. This method
     * should only be called if the next tokens start an if statement, aka
     * {@code IF}.
     *
     * 'IF' expression 'DO' statement* ('ELSE' statement*)? 'END'
     */
    public Ast.Statement.If parseIfStatement() throws ParseException {
        expect("IF");
        Ast.Expression condition = parseExpression();
        expect("DO");

        List<Ast.Statement> thenStatements = new ArrayList<>();
        while (tokens.has(0) && !peek("ELSE") && !peek("END")) {
            thenStatements.add(parseStatement());
        }

        List<Ast.Statement> elseStatements = new ArrayList<>();
        if (match("ELSE")) {
            while (tokens.has(0) && !peek("END")) {
                elseStatements.add(parseStatement());
            }
        }
        expect("END");
        return new Ast.Statement.If(condition, thenStatements, elseStatements);
    }

    /**
     * Parses a for statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a for statement, aka
     * {@code FOR}.
     *
     * 'FOR' '(' (identifier '=' expression)? ';' expression ';' (identifier '=' expression)? ')' statement* 'END'
     */
    public Ast.Statement.For parseForStatement() throws ParseException {
        expect("FOR");
        expect("(");

        Ast.Statement initialization = null;
        if (!peek(";")) {
            initialization = parseForAssignment();
        }
        expect(";");

        Ast.Expression condition = parseExpression();
        expect(";");

        Ast.Statement increment = null;
        if (!peek(")")) {
            increment = parseForAssignment();
        }
        expect(")");

        List<Ast.Statement> statements = new ArrayList<>();
        while (tokens.has(0) && !peek("END")) {
            statements.add(parseStatement());
        }
        expect("END");
        return new Ast.Statement.For(initialization, condition, increment, statements);
    }

    /**
     * Helper for the init/increment portions of a for loop:
     * identifier '=' expression
     */
    private Ast.Statement.Assignment parseForAssignment() throws ParseException {
        String name = expectIdentifier();
        expect("=");
        Ast.Expression value = parseExpression();
        return new Ast.Statement.Assignment(new Ast.Expression.Access(Optional.empty(), name), value);
    }

    /**
     * Parses a while statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a while statement, aka
     * {@code WHILE}.
     *
     * 'WHILE' expression 'DO' statement* 'END'
     */
    public Ast.Statement.While parseWhileStatement() throws ParseException {
        expect("WHILE");
        Ast.Expression condition = parseExpression();
        expect("DO");

        List<Ast.Statement> statements = new ArrayList<>();
        while (tokens.has(0) && !peek("END")) {
            statements.add(parseStatement());
        }
        expect("END");
        return new Ast.Statement.While(condition, statements);
    }

    /**
     * Parses a return statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a return statement, aka
     * {@code RETURN}.
     *
     * 'RETURN' expression ';'
     */
    public Ast.Statement.Return parseReturnStatement() throws ParseException {
        expect("RETURN");
        Ast.Expression value = parseExpression();
        expect(";");
        return new Ast.Statement.Return(value);
    }

    /**
     * Parses the {@code expression} rule.
     */
    public Ast.Expression parseExpression() throws ParseException {
        return parseLogicalExpression();
    }

    /**
     * Parses the {@code logical-expression} rule.
     *
     * comparison_expression (('AND' | 'OR') comparison_expression)*
     */
    public Ast.Expression parseLogicalExpression() throws ParseException {
        Ast.Expression left = parseEqualityExpression();
        while (peek("AND") || peek("OR")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression right = parseEqualityExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }
        return left;
    }

    /**
     * Parses the {@code equality-expression} rule. In our grammar this is the
     * {@code comparison_expression} rule.
     *
     * additive_expression (('<' | '<=' | '>' | '>=' | '==' | '!=') additive_expression)*
     */
    public Ast.Expression parseEqualityExpression() throws ParseException {
        Ast.Expression left = parseAdditiveExpression();
        while (peek("<") || peek("<=") || peek(">") || peek(">=") || peek("==") || peek("!=")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression right = parseAdditiveExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }
        return left;
    }

    /**
     * Parses the {@code additive-expression} rule.
     *
     * multiplicative_expression (('+' | '-') multiplicative_expression)*
     */
    public Ast.Expression parseAdditiveExpression() throws ParseException {
        Ast.Expression left = parseMultiplicativeExpression();
        while (peek("+") || peek("-")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression right = parseMultiplicativeExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }
        return left;
    }

    /**
     * Parses the {@code multiplicative-expression} rule.
     *
     * secondary_expression (('*' | '/') secondary_expression)*
     */
    public Ast.Expression parseMultiplicativeExpression() throws ParseException {
        Ast.Expression left = parseSecondaryExpression();
        while (peek("*") || peek("/")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression right = parseSecondaryExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }
        return left;
    }

    /**
     * Parses the {@code secondary-expression} rule.
     *
     * primary_expression ('.' identifier ('(' (expression (',' expression)*)? ')')?)*
     */
    public Ast.Expression parseSecondaryExpression() throws ParseException {
        Ast.Expression expression = parsePrimaryExpression();
        while (match(".")) {
            String name = expectIdentifier();
            if (match("(")) {
                List<Ast.Expression> arguments = parseArguments();
                expression = new Ast.Expression.Function(Optional.of(expression), name, arguments);
            } else {
                expression = new Ast.Expression.Access(Optional.of(expression), name);
            }
        }
        return expression;
    }

    /**
     * Parses the {@code primary-expression} rule. This is the top-level rule
     * for expressions and includes literal values, grouping, variables, and
     * functions. It may be helpful to break these up into other methods but is
     * not strictly necessary.
     */
    public Ast.Expression parsePrimaryExpression() throws ParseException {
        // Keywords are lexed as identifiers, so they must be checked first.
        if (match("NIL")) {
            return new Ast.Expression.Literal(null);
        } else if (match("TRUE")) {
            return new Ast.Expression.Literal(Boolean.TRUE);
        } else if (match("FALSE")) {
            return new Ast.Expression.Literal(Boolean.FALSE);
        } else if (peek(Token.Type.INTEGER)) {
            String literal = tokens.get(0).getLiteral();
            tokens.advance();
            return new Ast.Expression.Literal(new BigInteger(literal));
        } else if (peek(Token.Type.DECIMAL)) {
            String literal = tokens.get(0).getLiteral();
            tokens.advance();
            return new Ast.Expression.Literal(new BigDecimal(literal));
        } else if (peek(Token.Type.CHARACTER)) {
            String literal = tokens.get(0).getLiteral();
            tokens.advance();
            String inner = unescape(literal.substring(1, literal.length() - 1));
            return new Ast.Expression.Literal(inner.charAt(0));
        } else if (peek(Token.Type.STRING)) {
            String literal = tokens.get(0).getLiteral();
            tokens.advance();
            return new Ast.Expression.Literal(unescape(literal.substring(1, literal.length() - 1)));
        } else if (match("(")) {
            Ast.Expression expression = parseExpression();
            expect(")");
            return new Ast.Expression.Group(expression);
        } else if (peek(Token.Type.IDENTIFIER)) {
            String name = tokens.get(0).getLiteral();
            tokens.advance();
            if (match("(")) {
                List<Ast.Expression> arguments = parseArguments();
                return new Ast.Expression.Function(Optional.empty(), name, arguments);
            }
            return new Ast.Expression.Access(Optional.empty(), name);
        }
        throw new ParseException("Expected expression.", currentIndex());
    }

    /**
     * Parses (expression (',' expression)*)? ')' -- call this AFTER the
     * opening '(' has been consumed. Consumes the closing ')'.
     */
    private List<Ast.Expression> parseArguments() throws ParseException {
        List<Ast.Expression> arguments = new ArrayList<>();
        if (!peek(")")) {
            arguments.add(parseExpression());
            while (match(",")) {
                arguments.add(parseExpression());
            }
        }
        expect(")");
        return arguments;
    }

    /**
     * Replaces escape sequences (\b \n \r \t \' \" \\) with the characters
     * they represent. Done in a single left-to-right pass so that something
     * like {@code \\n} (escaped backslash followed by 'n') is handled properly.
     */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'b': sb.append('\b'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '\'': sb.append('\''); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(c).append(next); // lexer should prevent this
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Index to report in a ParseException: the index of the current token, or
     * if we've run out of tokens, the index just past the last token.
     */
    private int currentIndex() {
        if (tokens.has(0)) {
            return tokens.get(0).getIndex();
        }
        if (tokens.index > 0) {
            Token last = tokens.get(-1);
            return last.getIndex() + last.getLiteral().length();
        }
        return 0;
    }

    /**
     * Consumes the given pattern(s) or throws a ParseException at the current
     * position.
     */
    private void expect(Object... patterns) throws ParseException {
        if (!match(patterns)) {
            throw new ParseException("Expected " + patterns[0] + ".", currentIndex());
        }
    }

    /**
     * Consumes an identifier token and returns its literal, or throws.
     */
    private String expectIdentifier() throws ParseException {
        if (!peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier.", currentIndex());
        }
        String name = tokens.get(0).getLiteral();
        tokens.advance();
        return name;
    }

    /**
     * As in the lexer, returns {@code true} if the current sequence of tokens
     * matches the given patterns. Unlike the lexer, the pattern is not a regex;
     * instead it is either a {@link Token.Type}, which matches if the token's
     * type is the same, or a {@link String}, which matches if the token's
     * literal is the same.
     *
     * In other words, {@code Token(IDENTIFIER, "literal")} is matched by both
     * {@code peek(Token.Type.IDENTIFIER)} and {@code peek("literal")}.
     */
    private boolean peek(Object... patterns) {
        for (int i = 0; i < patterns.length; i++) {
            if (!tokens.has(i)) {
                return false;
            }
            Object pattern = patterns[i];
            Token token = tokens.get(i);
            if (pattern instanceof Token.Type) {
                if (token.getType() != pattern) {
                    return false;
                }
            } else if (pattern instanceof String) {
                if (!token.getLiteral().equals(pattern)) {
                    return false;
                }
            } else {
                throw new AssertionError("Invalid pattern object: " + pattern.getClass());
            }
        }
        return true;
    }

    /**
     * As in the lexer, returns {@code true} if {@link #peek(Object...)} is true
     * and advances the token stream.
     */
    private boolean match(Object... patterns) {
        boolean peek = peek(patterns);
        if (peek) {
            for (int i = 0; i < patterns.length; i++) {
                tokens.advance();
            }
        }
        return peek;
    }

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at index + offset.
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Gets the token at index + offset.
         */
        public Token get(int offset) {
            return tokens.get(index + offset);
        }

        /**
         * Advances to the next token, incrementing the index.
         */
        public void advance() {
            index++;
        }

    }

}