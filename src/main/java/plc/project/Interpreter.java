package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Interpreter implements Ast.Visitor<Environment.PlcObject> {

    private Scope scope = new Scope(null);

    public Interpreter(Scope parent) {
        scope = new Scope(parent);
        scope.defineFunction("print", 1, args -> {
            System.out.println(args.get(0).getValue());
            return Environment.NIL;
        });
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Environment.PlcObject visit(Ast.Source ast) {
        for (Ast.Field field : ast.getFields()) {
            visit(field);
        }
        for (Ast.Method method : ast.getMethods()) {
            visit(method);
        }
        return scope.lookupFunction("main", 0).invoke(new ArrayList<>());
    }

    @Override
    public Environment.PlcObject visit(Ast.Field ast) {
        if (ast.getValue().isPresent()) {
            scope.defineVariable(ast.getName(), ast.getConstant(), visit(ast.getValue().get()));
        } else {
            scope.defineVariable(ast.getName(), ast.getConstant(), Environment.NIL);
        }
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Method ast) {
        Scope defScope = scope;

        scope.defineFunction(ast.getName(), ast.getParameters().size(), args -> {
            Scope caller = scope;
            try {
                scope = new Scope(defScope);
                for (int i = 0; i < ast.getParameters().size(); i++) {
                    scope.defineVariable(ast.getParameters().get(i), false, args.get(i));
                }
                for (Ast.Statement stmt : ast.getStatements()) {
                    visit(stmt);
                }
            } catch (Return returnValue) {
                return returnValue.value;
            } finally {
                scope = caller;
            }
            return Environment.NIL;
        });

        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Expression ast) {
        visit(ast.getExpression());
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Declaration ast) {
        if (ast.getValue().isPresent()) {
            scope.defineVariable(ast.getName(), false, visit(ast.getValue().get()));
        } else {
            scope.defineVariable(ast.getName(), false, Environment.NIL);
        }

        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expression.Access access)) {
            throw new RuntimeException("Can only assign to a variable or field.");
        }
        Environment.PlcObject value = visit(ast.getValue());

        if (access.getReceiver().isPresent()) {
            // obj.field = value
            Environment.PlcObject object = visit(access.getReceiver().get());
            object.setField(access.getName(), value);
        } else {
            // name = value
            Environment.Variable variable = scope.lookupVariable(access.getName());
            variable.setValue(value);
        }
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.If ast) {
        boolean condition = requireType(Boolean.class, visit(ast.getCondition()));

        List<Ast.Statement> statements;
        if (condition) {
            statements = ast.getThenStatements();
        } else {
            statements = ast.getElseStatements();
        }

        try {
            scope = new Scope(scope);
            for (Ast.Statement stmt : statements) {
                visit(stmt);
            }
        } finally {
            scope = scope.getParent();
        }
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.For ast) {
        visit(ast.getInitialization());

        while (requireType(Boolean.class, visit(ast.getCondition()))) {
            try {
                scope = new Scope(scope);
                for (Ast.Statement stmt : ast.getStatements()) {
                    visit(stmt);
                }
            } finally {
                scope = scope.getParent();
            }
            visit(ast.getIncrement());
        }
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.While ast) {
        while (requireType(Boolean.class, visit(ast.getCondition()))) {
            try {
                scope = new Scope(scope);
                for (Ast.Statement stmt : ast.getStatements()) {
                    visit(stmt);
                }
            } finally {
                scope = scope.getParent();
            }
        }
        return Environment.NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Return ast) {
        throw new Return(visit(ast.getValue()));
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Literal ast) {
        if (ast.getLiteral() == null) {
            return Environment.NIL;
        }
        return Environment.create(ast.getLiteral());
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Group ast) {
        return visit(ast.getExpression());
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Binary ast) {
        String op = ast.getOperator();
        Environment.PlcObject left = visit(ast.getLeft());

        if (op.equals("AND")) {
            if (!requireType(Boolean.class, left)) {
                return Environment.create(false);
            }
            return Environment.create(requireType(Boolean.class, visit(ast.getRight())));
        }
        if (op.equals("OR")) {
            if (requireType(Boolean.class, left)) {
                return Environment.create(true);
            }
            return Environment.create(requireType(Boolean.class, visit(ast.getRight())));
        }

        Environment.PlcObject right = visit(ast.getRight());

        switch (op) {
            case "<":
            case "<=":
            case ">":
            case ">=": {
                Comparable l = requireType(Comparable.class, left);
                Object r = requireType(l.getClass(), right);
                int c = l.compareTo(r);

                boolean result = switch (op) {
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
                return Environment.create(result);
            }
            case "==":
                return Environment.create(Objects.equals(left.getValue(), right.getValue()));
            case "!=":
                return Environment.create(!Objects.equals(left.getValue(), right.getValue()));
            case "+":
                if (left.getValue() instanceof String || right.getValue() instanceof String) {
                    return Environment.create(String.valueOf(left.getValue() + String.valueOf(right.getValue())));
                }
            case "-":
            case "*":
            case "/": {
                if (left.getValue() instanceof BigInteger l) {
                    BigInteger r = requireType(BigInteger.class, right);
                    return switch (op) {
                        case "+" -> Environment.create(l.add(r));
                        case "-" -> Environment.create(l.subtract(r));
                        case "*" -> Environment.create(l.multiply(r));
                        default -> {
                            if (r.signum() == 0) throw new RuntimeException("Cannot divide by zero.");
                            yield Environment.create(l.divide(r));
                        }
                    };
                } else if (left.getValue() instanceof BigDecimal l) {
                    BigDecimal r = requireType(BigDecimal.class, right);
                    return switch (op) {
                        case "+" -> Environment.create(l.add(r));
                        case "-" -> Environment.create(l.subtract(r));
                        case "*" -> Environment.create(l.multiply(r));
                        default -> {
                            if (r.signum() == 0) throw new RuntimeException("Cannot divide by zero.");
                            yield Environment.create(l.divide(r, RoundingMode.HALF_EVEN));
                        }
                    };
                }
                throw new RuntimeException("Cannot apply " + op + " to the given operands.");
            }
            default:
                throw new RuntimeException("Unknown operator: " + op);
        }
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Access ast) {
        if (ast.getReceiver().isPresent()) {
            Environment.PlcObject object = visit(ast.getReceiver().get());
            return object.getField(ast.getName()).getValue();
        }
        return scope.lookupVariable(ast.getName()).getValue();
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Function ast) {
        List<Environment.PlcObject> args = new ArrayList<>();
        for (Ast.Expression arg : ast.getArguments()) {
            args.add(visit(arg));
        }

        if (ast.getReceiver().isPresent()) {
            // obj.method(args)
            Environment.PlcObject obj = visit(ast.getReceiver().get());
            return obj.callMethod(ast.getName(), args);
        }

        // name(args)
        Environment.Function function = scope.lookupFunction(ast.getName(), args.size());
        return function.invoke(args);
    }

    /**
     * Helper function to ensure an object is of the appropriate type.
     */
    private static <T> T requireType(Class<T> type, Environment.PlcObject object) {
        if (type.isInstance(object.getValue())) {
            return type.cast(object.getValue());
        } else {
            throw new RuntimeException("Expected type " + type.getName() + ", received " + object.getValue().getClass().getName() + ".");
        }
    }

    /**
     * Exception class for returning values.
     */
    private static class Return extends RuntimeException {

        private final Environment.PlcObject value;

        private Return(Environment.PlcObject value) {
            this.value = value;
        }

    }

}
