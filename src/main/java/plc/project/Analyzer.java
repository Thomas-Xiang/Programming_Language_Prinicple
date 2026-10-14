package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * See the specification for information about what the different visit
 * methods should do.
 */
public final class Analyzer implements Ast.Visitor<Void> {

    public Scope scope;
    private Ast.Method currentMethod; // Tracks the method being visited for return type checking

    public Analyzer(Scope parent) {
        scope = new Scope(parent);
        scope.defineFunction("print", "System.out.println", Arrays.asList(Environment.Type.ANY), Environment.Type.NIL, args -> Environment.NIL);
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Void visit(Ast.Source ast) {
        // Visit fields first, then methods
        for (Ast.Field field : ast.getFields()) {
            visit(field);
        }
        for (Ast.Method method : ast.getMethods()) {
            visit(method);
        }

        // Must have a main/0 function with Integer return type
        Environment.Function mainFunction = scope.lookupFunction("main", 0);
        if (mainFunction == null || !mainFunction.getReturnType().equals(Environment.Type.INTEGER)) {
            throw new RuntimeException("Source must contain a 'main' function taking 0 arguments with an Integer return type.");
        }

        return null;
    }

    @Override
    public Void visit(Ast.Field ast) {
        // Visit value if present before defining variable
        if (ast.getValue().isPresent()) {
            visit(ast.getValue().get());
        }

        // Constant field must have an initial value
        if (ast.getConstant() && !ast.getValue().isPresent()) {
            throw new RuntimeException("Constant field '" + ast.getName() + "' must have an initial value.");
        }

        // Get type registered in Environment
        Environment.Type fieldType = Environment.getType(ast.getTypeName());

        // Check value assignability if present
        if (ast.getValue().isPresent()) {
            requireAssignable(fieldType, ast.getValue().get().getType());
        }

        // Define variable in scope & decorate AST node
        Environment.Variable variable = scope.defineVariable(
                ast.getName(),
                ast.getName(),
                fieldType,
                ast.getConstant(),
                Environment.NIL
        );
        ast.setVariable(variable);

        return null;
    }

    @Override
    public Void visit(Ast.Method ast) {
        // Get return type (defaults to NIL if not provided)
        Environment.Type returnType = ast.getReturnTypeName().isPresent()
                ? Environment.getType(ast.getReturnTypeName().get())
                : Environment.Type.NIL;

        // Collect parameter types from environment
        List<Environment.Type> paramTypes = new ArrayList<>();
        for (String typeName : ast.getParameterTypeNames()) {
            paramTypes.add(Environment.getType(typeName));
        }

        // Define function in current scope
        Environment.Function function = scope.defineFunction(
                ast.getName(),
                ast.getName(),
                paramTypes,
                returnType,
                args -> Environment.NIL
        );
        ast.setFunction(function);

        // Visit statements inside a new scope with parameters defined
        Scope previousScope = scope;
        Ast.Method previousMethod = currentMethod;
        try {
            scope = new Scope(scope);
            currentMethod = ast;

            for (int i = 0; i < ast.getParameters().size(); i++) {
                String paramName = ast.getParameters().get(i);
                Environment.Type paramType = paramTypes.get(i);
                scope.defineVariable(paramName, paramName, paramType, false, Environment.NIL);
            }

            for (Ast.Statement stmt : ast.getStatements()) {
                visit(stmt);
            }
        } finally {
            scope = previousScope;
            currentMethod = previousMethod;
        }

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Expression ast) {
        // Only function expressions can cause side effects as standalone statements
        if (!(ast.getExpression() instanceof Ast.Expression.Function)) {
            throw new RuntimeException("Expression statements must be function calls.");
        }
        visit(ast.getExpression());
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Declaration ast) {
        if (!ast.getTypeName().isPresent() && !ast.getValue().isPresent()) {
            throw new RuntimeException("Declaration must have an explicit type or an initial value to infer type.");
        }

        if (ast.getValue().isPresent()) {
            visit(ast.getValue().get());
        }

        Environment.Type varType;
        if (ast.getTypeName().isPresent()) {
            varType = Environment.getType(ast.getTypeName().get());
            if (ast.getValue().isPresent()) {
                requireAssignable(varType, ast.getValue().get().getType());
            }
        } else {
            // Infer type from initialization expression
            varType = ast.getValue().get().getType();
        }

        Environment.Variable variable = scope.defineVariable(
                ast.getName(),
                ast.getName(),
                varType,
                false,
                Environment.NIL
        );
        ast.setVariable(variable);

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expression.Access)) {
            throw new RuntimeException("Assignment receiver must be an Access expression.");
        }

        visit(ast.getReceiver());
        visit(ast.getValue());

        Ast.Expression.Access receiver = (Ast.Expression.Access) ast.getReceiver();
        Environment.Variable variable = receiver.getVariable();

        // Check if attempting to reassign a constant field after declaration
        if (variable.getConstant()) {
            throw new RuntimeException("Cannot assign to constant variable/field '" + variable.getName() + "'.");
        }

        requireAssignable(variable.getType(), ast.getValue().getType());

        return null;
    }

    @Override
    public Void visit(Ast.Statement.If ast) {
        visit(ast.getCondition());

        if (!ast.getCondition().getType().equals(Environment.Type.BOOLEAN)) {
            throw new RuntimeException("If statement condition must be of type Boolean.");
        }

        if (ast.getThenStatements().isEmpty()) {
            throw new RuntimeException("If statement 'then' body cannot be empty.");
        }

        // Visit THEN block in a new scope
        Scope previousScope = scope;
        try {
            scope = new Scope(scope);
            for (Ast.Statement stmt : ast.getThenStatements()) {
                visit(stmt);
            }
        } finally {
            scope = previousScope;
        }

        // Visit ELSE block in a new scope if present
        if (!ast.getElseStatements().isEmpty()) {
            try {
                scope = new Scope(scope);
                for (Ast.Statement stmt : ast.getElseStatements()) {
                    visit(stmt);
                }
            } finally {
                scope = previousScope;
            }
        }

        return null;
    }

    @Override
    public Void visit(Ast.Statement.For ast) {
        if (ast.getStatements().isEmpty()) {
            throw new RuntimeException("For loop body cannot be empty.");
        }

        // Create new scope for for loop initialization and variables
        Scope previousScope = scope;
        try {
            scope = new Scope(scope);

            // Check initialization expression/variable if present
            if (ast.getInitialization() != null) {
                visit(ast.getInitialization());
            }

            visit(ast.getCondition());
            if (!ast.getCondition().getType().equals(Environment.Type.BOOLEAN)) {
                throw new RuntimeException("For loop condition must be of type Boolean.");
            }

            if (ast.getIncrement() != null) {
                visit(ast.getIncrement());
            }

            for (Ast.Statement stmt : ast.getStatements()) {
                visit(stmt);
            }
        } finally {
            scope = previousScope;
        }

        return null;
    }

    @Override
    public Void visit(Ast.Statement.While ast) {
        visit(ast.getCondition());

        if (!ast.getCondition().getType().equals(Environment.Type.BOOLEAN)) {
            throw new RuntimeException("While statement condition must be of type Boolean.");
        }

        Scope previousScope = scope;
        try {
            scope = new Scope(scope);
            for (Ast.Statement stmt : ast.getStatements()) {
                visit(stmt);
            }
        } finally {
            scope = previousScope;
        }

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Return ast) {
        if (currentMethod == null) {
            throw new RuntimeException("Return statement outside of method definition.");
        }

        visit(ast.getValue());

        Environment.Type expectedReturnType = currentMethod.getFunction().getReturnType();
        requireAssignable(expectedReturnType, ast.getValue().getType());

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Literal ast) {
        Object value = ast.getLiteral();

        if (value == null || value == Environment.NIL) {
            ast.setType(Environment.Type.NIL);
        } else if (value instanceof Boolean) {
            ast.setType(Environment.Type.BOOLEAN);
        } else if (value instanceof Character) {
            ast.setType(Environment.Type.CHARACTER);
        } else if (value instanceof String) {
            ast.setType(Environment.Type.STRING);
        } else if (value instanceof BigInteger) {
            BigInteger val = (BigInteger) value;
            if (val.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0 ||
                    val.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new RuntimeException("Integer literal out of 32-bit signed integer range: " + val);
            }
            ast.setType(Environment.Type.INTEGER);
        } else if (value instanceof BigDecimal) {
            BigDecimal val = (BigDecimal) value;
            double doubleVal = val.doubleValue();
            if (Double.isInfinite(doubleVal)) {
                throw new RuntimeException("Decimal literal out of 64-bit double range: " + val);
            }
            ast.setType(Environment.Type.DECIMAL);
        } else {
            throw new RuntimeException("Unknown literal type: " + value.getClass().getName());
        }

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Group ast) {
        // Group expression must wrap a binary expression
        if (!(ast.getExpression() instanceof Ast.Expression.Binary)) {
            throw new RuntimeException("Group expression must contain a Binary expression.");
        }

        visit(ast.getExpression());
        ast.setType(ast.getExpression().getType());

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Binary ast) {
        visit(ast.getLeft());
        visit(ast.getRight());

        String operator = ast.getOperator();
        Environment.Type leftType = ast.getLeft().getType();
        Environment.Type rightType = ast.getRight().getType();

        switch (operator) {
            case "&&":
            case "||":
            case "AND":
            case "OR":
                if (!leftType.equals(Environment.Type.BOOLEAN) || !rightType.equals(Environment.Type.BOOLEAN)) {
                    throw new RuntimeException("Logical operators require Boolean operands.");
                }
                ast.setType(Environment.Type.BOOLEAN);
                break;

            case "<":
            case "<=":
            case ">":
            case ">=":
            case "==":
            case "!=":
                requireAssignable(Environment.Type.COMPARABLE, leftType);
                requireAssignable(Environment.Type.COMPARABLE, rightType);
                if (!leftType.equals(rightType)) {
                    throw new RuntimeException("Comparison operators require operands of identical Comparable type.");
                }
                ast.setType(Environment.Type.BOOLEAN);
                break;

            case "+":
                if (leftType.equals(Environment.Type.STRING) || rightType.equals(Environment.Type.STRING)) {
                    ast.setType(Environment.Type.STRING);
                } else {
                    if (!leftType.equals(Environment.Type.INTEGER) && !leftType.equals(Environment.Type.DECIMAL)) {
                        throw new RuntimeException("Addition requires Integer, Decimal, or String operands.");
                    }
                    if (!leftType.equals(rightType)) {
                        throw new RuntimeException("Numeric addition requires matching LHS and RHS types.");
                    }
                    ast.setType(leftType);
                }
                break;

            case "-":
            case "*":
            case "/":
                if (!leftType.equals(Environment.Type.INTEGER) && !leftType.equals(Environment.Type.DECIMAL)) {
                    throw new RuntimeException("Arithmetic operators require Integer or Decimal operands.");
                }
                if (!leftType.equals(rightType)) {
                    throw new RuntimeException("Arithmetic operations require matching LHS and RHS types.");
                }
                ast.setType(leftType);
                break;

            default:
                throw new RuntimeException("Unsupported binary operator: " + operator);
        }

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Access ast) {
        if (ast.getReceiver().isPresent()) {
            visit(ast.getReceiver().get());
            Environment.Type receiverType = ast.getReceiver().get().getType();
            Environment.Variable variable = receiverType.getField(ast.getName());
            ast.setVariable(variable);
        } else {
            Environment.Variable variable = scope.lookupVariable(ast.getName());
            ast.setVariable(variable);
        }

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Function ast) {
        for (Ast.Expression arg : ast.getArguments()) {
            visit(arg);
        }

        Environment.Function function;

        if (ast.getReceiver().isPresent()) {
            visit(ast.getReceiver().get());
            Environment.Type receiverType = ast.getReceiver().get().getType();

            function = receiverType.getFunction(ast.getName(), ast.getArguments().size());

            for (int i = 0; i < ast.getArguments().size(); i++) {
                requireAssignable(function.getParameterTypes().get(i + 1), ast.getArguments().get(i).getType());
            }
        } else {
            function = scope.lookupFunction(ast.getName(), ast.getArguments().size());
            for (int i = 0; i < ast.getArguments().size(); i++) {
                requireAssignable(function.getParameterTypes().get(i), ast.getArguments().get(i).getType());
            }
        }

        ast.setFunction(function);

        return null;
    }

    /**
     * Checks whether a type is assignable to a target type according to language semantics.
     * Throws RuntimeException if the types are incompatible.
     */
    public static void requireAssignable(Environment.Type target, Environment.Type type) {
        if (target.equals(type)) {
            return;
        }

        if (target.equals(Environment.Type.ANY)) {
            return;
        }

        if (target.equals(Environment.Type.COMPARABLE)) {
            if (type.equals(Environment.Type.INTEGER) ||
                    type.equals(Environment.Type.DECIMAL) ||
                    type.equals(Environment.Type.CHARACTER) ||
                    type.equals(Environment.Type.STRING)) {
                return;
            }
        }

        throw new RuntimeException("Type mismatch: cannot assign " + type.getName() + " to " + target.getName() + ".");
    }
}