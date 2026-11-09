package plc.project;

import java.io.PrintWriter;
import java.util.List;

public final class Generator implements Ast.Visitor<Void> {

    private final PrintWriter writer;
    private int indent = 0;

    public Generator(PrintWriter writer) {
        this.writer = writer;
    }

    private void print(Object... objects) {
        for (Object object : objects) {
            if (object instanceof Ast) {
                visit((Ast) object);
            } else {
                writer.write(object.toString());
            }
        }
    }

    private void newline(int indent) {
        writer.println();
        for (int i = 0; i < indent; i++) {
            writer.write("    ");
        }
    }

    // helpers

    private void block(List<Ast.Statement> statements) {
        print(" {");
        if (!statements.isEmpty()) {
            indent++;
            for (Ast.Statement statement : statements) {
                newline(indent);
                print(statement);
            }
            indent --;
            newline(indent);
        }
        print("}");
    }

    private void printAssignment(Ast.Statement.Assignment assignment) {
        print(assignment.getReceiver(), " = ", assignment.getValue());
    }

    // visitor functions

    @Override
    public Void visit(Ast.Source ast) {
        print("public class Main {");
        newline(0);
        indent++;

        if (!ast.getFields().isEmpty()) {
            for (Ast.Field field : ast.getFields()) {
                newline(indent);
                print(field);
            }
            newline(0);
        }

        newline(indent);
        print("public static void main(String[] args) {");
        newline(indent + 1);
        print("System.exit(new Main().main());");
        newline(indent);
        print("}");
        newline(0);

        for (Ast.Method method : ast.getMethods()) {
            newline(indent);
            print(method);
            newline(0);
        }

        indent--;
        newline(0);
        print("}");
        return null;
    }

    @Override
    public Void visit(Ast.Field ast) {
        if (ast.getConstant()) {
            print("final ");
        }
        print(ast.getVariable().getType().getJvmName(), " ", ast.getVariable().getJvmName());
        if (ast.getValue().isPresent()) {
            print(" = ", ast.getValue().get());
        }
        print(";");
        return null;
    }

    @Override
    public Void visit(Ast.Method ast) {
        Environment.Function function = ast.getFunction();
        print(function.getReturnType().getJvmName(), " ", function.getJvmName(), "(");
        for (int i = 0; i < ast.getParameters().size(); i++) {
            if (i > 0) {
                print(", ");
            }
            print(function.getParameterTypes().get(i).getJvmName(), " ", ast.getParameters().get(i));
        }
        print(")");
        block(ast.getStatements());
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Expression ast) {
        print(ast.getExpression(), ";");
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Declaration ast) {
        print(ast.getVariable().getType().getJvmName(), " ", ast.getVariable().getJvmName());
        if (ast.getValue().isPresent()) {
            print(" = ", ast.getValue().get());
        }
        print(";");
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Assignment ast) {
        print(ast.getReceiver(), " = ", ast.getValue(), ";");
        return null;
    }

    @Override
    public Void visit(Ast.Statement.If ast) {
        print("if (", ast.getCondition(), ")");
        block(ast.getThenStatements());
        if (!ast.getElseStatements().isEmpty()) {
            print(" else");
            block(ast.getElseStatements());
        }
        return null;
    }

    @Override
    public Void visit(Ast.Statement.For ast) {
        print("for ( ");
        if (ast.getInitialization() != null) {
            printAssignment((Ast.Statement.Assignment) ast.getInitialization());
        }
        print("; ", ast.getCondition(), ";");
        if (ast.getIncrement() != null) {
            print(" ");
            printAssignment((Ast.Statement.Assignment) ast.getIncrement());
        }
        print(" )");
        block(ast.getStatements());
        return null;
    }

    @Override
    public Void visit(Ast.Statement.While ast) {
        print("while (", ast.getCondition(), ")");
        block(ast.getStatements());
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Return ast) {
        print("return ", ast.getValue(), ";");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Literal ast) {
        Object literal = ast.getLiteral();
        switch (literal) {
            case null -> print("null");
            case String s -> print("\"", literal, "\"");
            case Character c -> print("'", literal, "'");
            default -> print(literal);
        }
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Group ast) {
        print("(", ast.getExpression(), ")");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Binary ast) {
        String operator = ast.getOperator();
        if (operator.equals("AND")) {
            operator = "&&";
        } else if (operator.equals("OR")) {
            operator = "||";
        }
        print(ast.getLeft(), " ", operator, " ", ast.getRight());
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Access ast) {
        if (ast.getReceiver().isPresent()) {
            print(ast.getReceiver().get(), ".");
        }
        print(ast.getVariable().getJvmName());
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Function ast) {
        if (ast.getReceiver().isPresent()) {
            print(ast.getReceiver().get(), ".");
        }
        print(ast.getFunction().getJvmName(), "(");
        for (int i = 0; i < ast.getArguments().size(); i++) {
            if (i > 0) {
                print(", ");
            }
            print(ast.getArguments().get(i));
        }
        print(")");
        return null;
    }
}
